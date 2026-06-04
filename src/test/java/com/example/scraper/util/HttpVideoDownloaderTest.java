package com.example.scraper.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpVideoDownloaderTest {

    /** Tiny in-process HTTP server. Accepts one connection, replies with a
     *  configurable body + Content-Length, then closes. */
    private static class TestServer implements AutoCloseable {
        final ServerSocket socket;
        final Thread acceptor;
        volatile int contentLength = -1;
        volatile byte[] body = new byte[0];
        volatile int served = 0;
        volatile String path = "/";
        volatile boolean dropConnection = false;

        TestServer() throws IOException {
            this.socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            this.acceptor = new Thread(this::acceptLoop, "TestServer-" + socket.getLocalPort());
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        int port() { return socket.getLocalPort(); }
        String host() { return "127.0.0.1"; }
        String url() { return "http://" + host() + ":" + port() + path; }

        private void acceptLoop() {
            try (Socket client = socket.accept()) {
                // Read the request line + headers until CRLFCRLF.
                var in = client.getInputStream();
                var buf = new java.io.ByteArrayOutputStream();
                int b;
                int matched = 0;
                String target = "/";
                while ((b = in.read()) != -1) {
                    buf.write(b);
                    if (b == "\r\n\r\n".charAt(matched)) {
                        matched++;
                        if (matched == 4) break;
                    } else {
                        matched = 0;
                    }
                }
                String request = buf.toString(StandardCharsets.US_ASCII);
                int sp1 = request.indexOf(' ');
                int sp2 = request.indexOf(' ', sp1 + 1);
                if (sp1 > 0 && sp2 > sp1) {
                    target = request.substring(sp1 + 1, sp2);
                }
                path = target;

                if (dropConnection) {
                    return;  // client gets nothing
                }

                byte[] respBody = body;
                int declared = (contentLength < 0) ? respBody.length : contentLength;
                String headers = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: video/mp4\r\n"
                        + "Content-Length: " + declared + "\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";
                OutputStream out = client.getOutputStream();
                out.write(headers.getBytes(StandardCharsets.US_ASCII));
                out.write(respBody);
                out.flush();
                served++;
            } catch (IOException ignored) {
                // Best-effort.
            }
        }

        @Override public void close() throws IOException { socket.close(); }
    }

    @TempDir Path tempDir;
    private TestServer server;

    @BeforeEach void start() throws IOException { server = new TestServer(); }
    @AfterEach  void stop()  throws IOException { if (server != null) server.close(); }

    @Test
    void download_happyPath_writesExactBody() throws IOException {
        byte[] body = new byte[2048];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i & 0xff);
        server.body = body;
        server.contentLength = body.length;

        Path dest = tempDir.resolve("out.bin");
        long written = HttpVideoDownloader.downloadForTest(server.url(), dest, 1_000_000, 1000, 1000);

        assertThat(written).isEqualTo(body.length);
        assertThat(Files.size(dest)).isEqualTo(body.length);
        assertThat(Files.readAllBytes(dest)).isEqualTo(body);
    }

    @Test
    void download_zeroByteBody_writesEmptyFile() throws IOException {
        server.body = new byte[0];
        server.contentLength = 0;

        Path dest = tempDir.resolve("out.bin");
        long written = HttpVideoDownloader.downloadForTest(server.url(), dest, 1_000_000, 1000, 1000);

        assertThat(written).isZero();
        assertThat(Files.exists(dest)).isTrue();
        assertThat(Files.size(dest)).isZero();
    }

    @Test
    void download_declaredContentLengthExceedsMax_rejectedBeforeRead() throws IOException {
        server.body = new byte[100];
        server.contentLength = 100_000;  // server lies about size, way over max

        Path dest = tempDir.resolve("out.bin");
        assertThatThrownBy(() -> HttpVideoDownloader.downloadForTest(server.url(), dest, 10_000, 1000, 1000))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds max size");
    }

    @Test
    void download_serverLiesAboutContentLength_streamingCapKillsIt() throws IOException {
        // Server says Content-Length: 1 KB but actually sends 5 KB. The streaming
        // hard cap should reject the download mid-flight.
        server.body = new byte[5_000];
        server.contentLength = 1_000;

        Path dest = tempDir.resolve("out.bin");
        assertThatThrownBy(() -> HttpVideoDownloader.downloadForTest(server.url(), dest, 2_000, 1000, 1000))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeded max size");
    }

    @Test
    void download_httpsProtocol_unsupported() {
        assertThatThrownBy(() -> HttpVideoDownloader.download("ftp://example.com/x", tempDir.resolve("x"), 1000, 1000, 1000))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported protocol");
    }

    @Test
    void download_malformedUrl_rejected() {
        assertThatThrownBy(() -> HttpVideoDownloader.download("not a url", tempDir.resolve("x"), 1000, 1000, 1000))
                .isInstanceOf(IOException.class);
    }

    @Test
    void isAllowedRemoteHost_rejectsLoopback() {
        assertThat(HttpVideoDownloader.isAllowedRemoteHostForTest("127.0.0.1")).isFalse();
        assertThat(HttpVideoDownloader.isAllowedRemoteHostForTest("localhost")).isFalse();
        assertThat(HttpVideoDownloader.isAllowedRemoteHostForTest("something.local")).isFalse();
        assertThat(HttpVideoDownloader.isAllowedRemoteHostForTest("")).isFalse();
        assertThat(HttpVideoDownloader.isAllowedRemoteHostForTest(null)).isFalse();
    }

    @Test
    void isAllowedRemoteHost_acceptsPublicHostName() {
        // The check resolves DNS; use a well-known public host. If DNS is
        // unavailable, skip rather than fail.
        try {
            InetAddress[] addrs = InetAddress.getAllByName("example.com");
            // If it resolves to a private IP for any reason, the check would
            // block it. Skip the assertion in that case.
            boolean anyPublic = false;
            for (InetAddress a : addrs) {
                if (!a.isLoopbackAddress() && !a.isSiteLocalAddress()
                        && !a.isLinkLocalAddress() && !a.isAnyLocalAddress()
                        && !a.isMulticastAddress()) {
                    anyPublic = true;
                    break;
                }
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(anyPublic,
                    "DNS for example.com is private - skipping");
            assertThat(HttpVideoDownloader.isAllowedRemoteHostForTest("example.com")).isTrue();
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.abort("DNS unavailable: " + e.getMessage());
        }
    }
}
