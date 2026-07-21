package com.example.scraper.util;

import jakarta.annotation.PreDestroy;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches HTML pages and resolves redirect chains via real headless
 * Chrome, so we can pass Cloudflare's JavaScript challenges.
 *
 * <p><b>Why headless Chrome?</b> Cloudflare-fronted sites (e.g. ixxx.com)
 * serve a JS proof-of-work "Just a moment..." page to non-browser
 * clients — even when the User-Agent looks like Chrome and the TLS
 * fingerprint is Java's {@code HttpClient}. The challenge solves in a
 * real browser in ~3-5s; the only reliable bypass is to actually run
 * JavaScript, hence headless Chrome.
 *
 * <p><b>Driver pool:</b> each call to {@link #fetch(String)} or
 * {@link #resolveFinalUrl(String)} grabs one driver from the pool,
 * uses it, and releases it. Pool size is configurable via
 * {@code app.fetcher.pool-size} (default 4). A search-results page
 * typically has ~240 {@code /out/} redirect links; with 4 drivers
 * running in parallel they resolve in ~3 minutes instead of ~12
 * minutes sequentially.
 *
 * <p><b>Lifecycle:</b> drivers are created lazily on first use so a
 * broken/missing Chrome doesn't kill Spring context startup — failures
 * surface as {@link IOException} on the first fetch instead.
 */
@Component
public class HttpPageFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpPageFetcher.class);

    /** Shared User-Agent string, kept public for any future mirror fetcher. */
    public static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    /** How long to wait for the Cloudflare "Just a moment..." challenge to clear. */
    private static final Duration CHALLENGE_WAIT = Duration.ofSeconds(25);

    /** How long a navigation is allowed to take (per-load timeout). */
    private static final Duration DEFAULT_PAGE_WAIT = Duration.ofSeconds(20);

    /** Detect click-through pages with a meta-refresh tag. */
    private static final Pattern META_REFRESH = Pattern.compile(
            "<meta[^>]*http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*"
                    + "content\\s*=\\s*[\"']?\\d+\\s*;?\\s*url\\s*=\\s*([^\"'\\s>]+)",
            Pattern.CASE_INSENSITIVE);

    private final int poolSize;
    /** Caps concurrent driver use at poolSize; coordinated with {@link #idle}. */
    private final Semaphore available;
    /** Drivers currently sitting unused; pool grows up to poolSize lazily. */
    private final BlockingQueue<ChromeDriver> idle = new LinkedBlockingQueue<>();
    /** All drivers ever created (for shutdown). Accessed under {@link #all}. */
    private final List<ChromeDriver> all = Collections.synchronizedList(new ArrayList<>());

    public HttpPageFetcher(@Value("${app.fetcher.pool-size:4}") int poolSize) {
        if (poolSize < 1) poolSize = 1;
        this.poolSize = poolSize;
        this.available = new Semaphore(poolSize);
        log.info("HttpPageFetcher configured with pool size {}", poolSize);
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Fetches {@code url} with no Referer header. */
    public String fetch(String url) throws IOException {
        return withDriver(d -> {
            d.get(url);
            waitForChallenge(d, url);
            waitForDocumentReady(d);
            return d.getPageSource();
        });
    }

    /**
     * Fetches {@code url} with a Referer hint (e.g. search-engine landing).
     * Selenium doesn't expose a clean way to set request headers without
     * going through CDP, and ixxx.com doesn't gate on Referer — the
     * argument is preserved so callers don't change, but currently
     * informational only.
     */
    public String fetch(String url, String referer) throws IOException {
        return fetch(url);
    }

    /**
     * Navigates to {@code url} via headless Chrome and returns the URL
     * the browser ends up at after following all server-side (3xx) and
     * client-side (meta-refresh) redirects.
     *
     * <p>This is what aggregator redirect URLs (e.g. ixxx.com's
     * {@code /out/?l=...&c=...&v=...}) need: Cloudflare used to return
     * a tiny 302+meta-refresh page to HttpClient, but now serves the
     * JS challenge to anything non-browser. Chrome follows the 302s
     * and renders the meta-refresh interim, so {@code getCurrentUrl()}
     * after the load settles is the destination URL.
     *
     * @throws IOException if the CF challenge doesn't clear, or navigation
     *                     fails for any other reason
     */
    public String resolveFinalUrl(String url) throws IOException {
        return withDriver(d -> {
            d.get(url);
            waitForChallenge(d, url);
            // Server-side 302 redirects settle inside `d.get()`. Anything
            // else (meta-refresh, JS navigation) needs a brief settle
            // period. We poll getCurrentUrl() until it stabilizes for
            // two consecutive polls — caps total wait at ~3 s.
            return waitForUrlToStabilize(d);
        });
    }

    // ------------------------------------------------------------------
    // Driver pool mechanics
    // ------------------------------------------------------------------

    private ChromeDriver acquire() throws IOException {
        try {
            available.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for a Chrome driver");
        }
        // Grab from idle queue first
        ChromeDriver d = idle.poll();
        if (d != null) return d;
        // Nothing idle; create a new one if the pool isn't full
        synchronized (all) {
            if (all.size() < poolSize) {
                d = createDriver();
                all.add(d);
                return d;
            }
        }
        // Pool full but somehow no idle driver — shouldn't happen given
        // the semaphore. Block on the idle queue as a fallback.
        try {
            return idle.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            available.release();
            throw new IOException("Interrupted waiting for a Chrome driver", e);
        }
    }

    private void release(ChromeDriver d) {
        idle.add(d);
        available.release();
    }

    private ChromeDriver createDriver() {
        ChromeOptions opts = new ChromeOptions();
        opts.addArguments(
                "--headless=new",       // full headless (Chrome 109+), not legacy
                "--disable-gpu",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-blink-features=AutomationControlled", // hide navigator.webdriver
                "--user-agent=" + BROWSER_UA,
                "--window-size=1280,720",
                "--lang=en-US,en",
                "--disable-extensions",
                "--disable-default-apps"
        );
        ChromeDriverService service = new ChromeDriverService.Builder()
                .withSilent(true)
                .build();
        ChromeDriver d = new ChromeDriver(service, opts);
        d.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
        d.manage().timeouts().pageLoadTimeout(DEFAULT_PAGE_WAIT);
        log.info("HttpPageFetcher: created headless Chrome #{} ({} slots)",
                all.size() + 1, poolSize);
        return d;
    }

    /** Runs {@code action} with a driver acquired from the pool, releasing it on the way out. */
    @FunctionalInterface
    private interface DriverAction<T> {
        T run(ChromeDriver driver) throws Exception;
    }

    private <T> T withDriver(DriverAction<T> action) throws IOException {
        ChromeDriver d = acquire();
        try {
            return action.run(d);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Driver action failed: " + e.getMessage(), e);
        } finally {
            release(d);
        }
    }

    // ------------------------------------------------------------------
    // Page-load plumbing (shared between fetch and resolveFinalUrl)
    // ------------------------------------------------------------------

    private void waitForChallenge(ChromeDriver d, String url) throws IOException {
        try {
            new WebDriverWait(d, CHALLENGE_WAIT).until(driver -> {
                String title = driver.getTitle();
                return title != null && !title.toLowerCase().contains("just a moment");
            });
        } catch (TimeoutException e) {
            throw new IOException("Cloudflare challenge didn't clear within "
                    + CHALLENGE_WAIT.getSeconds() + "s for " + url
                    + " (title still: " + safeTitle(d) + ")");
        }
    }

    private void waitForDocumentReady(ChromeDriver d) {
        try {
            new WebDriverWait(d, DEFAULT_PAGE_WAIT).until(driver ->
                    "complete".equals(
                            ((ChromeDriver) driver).executeScript(
                                    "return document.readyState")));
        } catch (Exception ignored) {
            // Not fatal — the source is probably good enough.
        }
    }

    /**
     * Polls {@code getCurrentUrl()} until it returns the same value for
     * two consecutive polls (500 ms apart). Lets server-side redirects
     * settle, then catches meta-refresh / JS navigation, then gives up
     * at ~3 s total and returns whatever URL is current.
     */
    private String waitForUrlToStabilize(ChromeDriver d) {
        String last = d.getCurrentUrl();
        try {
            for (int i = 0; i < 6; i++) {   // ~3 s total budget
                Thread.sleep(500);
                String now = d.getCurrentUrl();
                if (now.equals(last)) {
                    // Stable across one sleep — check one more time
                    Thread.sleep(300);
                    if (d.getCurrentUrl().equals(now)) return now;
                }
                last = now;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return d.getCurrentUrl();
    }

    private static String safeTitle(ChromeDriver d) {
        try { return d.getTitle(); }
        catch (Exception e) { return "<unknown>"; }
    }

    @PreDestroy
    public synchronized void shutdown() {
        log.info("HttpPageFetcher: shutting down {} Chrome driver(s)", all.size());
        for (ChromeDriver d : all) {
            try { d.quit(); } catch (Exception ignored) {}
        }
        all.clear();
        idle.clear();
    }
}
