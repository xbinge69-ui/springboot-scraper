# springboot-scraper

A Spring Boot web application for scraping web content and videos from supported websites, with end-to-end video ingestion, real ffmpeg-powered derivatives, and AI-assisted topical-authority metadata.

## 📋 Table of Contents

- [Overview](#overview)
- [Core Features](#core-features)
- [Technology Stack](#technology-stack)
- [Project Structure](#project-structure)
- [Endpoints](#endpoints)
- [Architecture](#architecture)
- [Setup & Installation](#setup--installation)
- [Configuration](#configuration)
- [Usage Guide](#usage-guide)
- [Use Cases](#use-cases)
- [Security Considerations](#security-considerations)
- [Adding a New Site Scraper](#adding-a-new-site-scraper)
- [Known Limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)

## 📌 Overview

This application is designed to:
- **Scrape web content** from any website (links, metadata, etc.)
- **Extract videos** from supported video hosting sites
- **Bypass CDN hotlink protection** through transparent proxying
- **Ingest videos** with a real ffmpeg pipeline (probe → compressed + preview + thumbnail)
- **Upload to Bunny CDN** (the only CDN — all 3 derivatives go here)
- **Enrich metadata** with a local Ollama LLM using a "Topical Authority" prompt that considers the existing catalog

## 🎯 Core Features

### 1. Generic Web Scraping (`/scrape`)
Extracts all anchor links from any given URL using Jsoup. Returns page title and a list of `{text, href, description}` items.

### 2. Video Scraping (`/video/scrape`)
Extracts video URLs from supported video hosting sites via a `SiteScraper` strategy interface.

**Supported Sites:**
- **Erome** (`erome.com`)
- **Generic fallback** (regex on `<video>`, `<source>`, `[data-src]`, inline JS)

**Supported Formats:** `.mp4`, `.m3u8`, `.webm`, `.mov`

### 3. Video Proxy (`/video/stream`)
Bypasses CDN hotlink protection by streaming remote videos with browser-like headers (Chrome 124 UA, Referer, Origin). SSRF protection blocks loopback / link-local / site-local / multicast hosts.

### 4. Video Enrichment (`/api/video/enrich`) — **recommended endpoint**
End-to-end ingestion that takes a video (file upload or remote URL) plus draft metadata, and returns a fully enriched, persisted `VideoCatalogEntry` in the shape of the example below.

```json
{
  "id": "v3",
  "slug": "passionate-amateur-couples-intimate-bedroom-advent",
  "title": "Passionate Amateur Couple's Intimate Bedroom Adventure - SpankyCouples",
  "description": "Experience the authentic passion of an amateur couple...",
  "durationSeconds": 6,
  "thumbnailKey": "https://example.b-cdn.net/videos/sample-test-35be011c0d31.thumbnail.jpg",
  "previewUrl":   "https://example.b-cdn.net/videos/sample-test-35be011c0d31.preview.mp4",
  "embedUrl":     "https://example.b-cdn.net/videos/sample-test-35be011c0d31.compressed.mp4",
  "backupEmbedUrl": "https://example.b-cdn.net/videos/sample-test-35be011c0d31.compressed.mp4",
  "tags": ["amateur", "couple", "passionate", "intimate", "bedroom", "spanky", "adult", "encounter"],
  "category": "AmateurBedroomEncounters",
  "publishedAt": "2026-06-04T14:08:16.332Z",
  "actressId": null,
  "unknownActressName": "Anonymous Couple",
  "views": 300000
}
```

**Pipeline steps:**

1. **Resolve source** — either a multipart upload or a remote URL. The URL is downloaded to a temp file with SSRF protection and a hard byte cap.
2. **Probe** via `ffprobe` to read real width / height / duration / hasAudio.
3. **Process** via `ffmpeg`:
   - **compressed** — full-length 720p-capped re-encode (`force_original_aspect_ratio=decrease` so tiktok 9:16 stays 9:16 and small sources are never upscaled)
   - **preview** — 5-second clip from ~40% of duration, same 720p cap
   - **thumbnail** — single frame from ~20% of duration at the **source's native resolution** (no scale filter, so it's never stretched)
4. **Upload to Bunny CDN** (3 streaming `PUT` requests, no `byte[]` allocation). 12-hex-char UUID shared across the 3 object keys.
5. **Upload to Bunny** (3 streaming `PUT`s, no `byte[]` allocation) → thumbnailKey / previewUrl / embedUrl. `backupEmbedUrl` mirrors `embedUrl` since there's no second CDN.
6. **Ollama "Topical Authority" pass** — sends the draft metadata + a compact catalog summary (top-30 tags with counts, category histogram, 30 most recent titles) to Ollama and gets back refined title/description/category/tags/slug + a starting view count. Server-side `options.format=json` + `options.temperature=0.2`.
7. **Persist** to `videos.json` (atomic write to `.tmp` + `ATOMIC_MOVE`).
8. **Cleanup** — all 4 local files deleted via `try-with-resources`. A startup sweeper also clears stale temp dirs from prior crashed JVMs.

**Multipart request:**

```bash
curl -X POST http://localhost:8080/api/video/enrich \
  -F "title=My Video Title" \
  -F "description=Authentic bedroom scene" \
  -F "category=Amateur" \
  -F "tags=amateur,couple,bedroom" \
  -F "videoFile=@/path/to/video.mp4"   # OR
  # -F "videoUrl=https://example.com/video.mp4"
```

**Validation (returns HTTP 400 + `{"error": "..."}`):**
- `title` is required
- Exactly one of `videoFile` / `videoUrl` is required
- Both `videoFile` and `videoUrl` → 400

**Response (HTTP 200):** the full persisted `VideoCatalogEntry` plus a `warnings: []` list. Warnings are populated on soft failures (e.g. Ollama offline → fallback metadata used).

### 5. Page-URL Pipeline (`/api/video/pipeline`)
Page-URL → first-video-URL → same enrichment orchestrator. Accepts the same JSON shape as before, but the new code path runs real ffmpeg + Ollama + Bunny instead of the byte-prefix hack.

### 6. Direct File Upload (`/api/video/ingest-file`)
Multipart upload → same enrichment orchestrator. Equivalent to `POST /api/video/enrich` with a file source.

---

## 🛠 Technology Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| **Java** | 21 | Language |
| **Spring Boot** | 3.3.4 | Web framework |
| **Spring Web MVC** | 3.3.4 | REST API & controllers |
| **Thymeleaf** | 3.x | HTML templating |
| **Jsoup** | 1.17.2 | HTML parsing & CSS selectors |
| **Jackson** | 2.x | JSON processing |
| **FFmpeg / FFprobe** | any modern build | Video processing (system binary, must be on `PATH`) |
| **ffmpeg-cli-wrapper** | 0.9.2 | Java fluent builder for ffmpeg/ffprobe |
| **Spring DevTools** | 3.3.4 | Hot reload in dev |
| **Maven** | - | Build tool |
| **JUnit 5 + Mockito + AssertJ** | (via spring-boot-starter-test) | Test stack |

**Optional integrations:**
- **Bunny CDN** — asset storage & CDN (storage zone, pull base URL, API key)
- **Ollama** — local AI model for the Topical Authority pass (default `http://localhost:11434`, model `mistral`)
- **Ollama** — local AI model for the Topical Authority pass (default `http://localhost:11434`, model `mistral`)

## 📁 Project Structure

```
springboot-scraper/
├── pom.xml
├── README.md
├── src/
│   ├── main/
│   │   ├── java/com/example/scraper/
│   │   │   ├── ScraperApplication.java
│   │   │   ├── controller/
│   │   │   │   ├── ScraperController.java          # /scrape, /video/*, /api/video/* (incl. /enrich)
│   │   │   │   └── VideoProxyController.java       # /video/stream
│   │   │   ├── model/
│   │   │   │   ├── ScrapedItem.java
│   │   │   │   ├── VideoResult.java
│   │   │   │   ├── PipelineRequest.java
│   │   │   │   ├── PipelineOutcome.java            # { entry, warnings }
│   │   │   │   ├── VideoCatalogEntry.java          # id, slug, title, ..., views
│   │   │   │   └── ...
│   │   │   ├── scraper/
│   │   │   │   ├── SiteScraper.java                # strategy interface
│   │   │   │   ├── EromeScraper.java
│   │   │   │   └── GenericVideoScraper.java
│   │   │   ├── service/
│   │   │   │   ├── ScraperService.java             # generic Jsoup scraper
│   │   │   │   ├── VideoScraperService.java        # strategy router
│   │   │   │   ├── VideoEnrichmentService.java     # orchestrator (ffmpeg + bunny + ollama + catalog)
│   │   │   │   ├── VideoIngestionPipelineService.java # thin shim: page-URL → enrich
│   │   │   │   ├── (Bunny is the only CDN; no upload-host service anymore)
│   │   │   │   ├── BunnyAssetService.java          # streaming Path-based upload
│   │   │   │   ├── VideoCatalogService.java        # atomic JSON I/O, slug uniqueness, summarize()
│   │   │   │   └── OllamaService.java              # /api/generate with timeout + JSON options
│   │   │   ├── util/
│   │   │   │   ├── HttpVideoDownloader.java        # SSRF-guarded URL → file
│   │   │   │   └── Slugify.java
│   │   │   └── video/
│   │   │       ├── FfmpegDerivativeService.java    # probe + process → 3 derivatives
│   │   │       ├── FfmpegProperties.java
│   │   │       ├── VideoMetadata.java               # record(width, height, durationSeconds, hasAudio)
│   │   │       ├── Derivatives.java                 # record(compressedPath, previewPath, thumbnailPath)
│   │   │       ├── EnrichmentSource.java            # sealed: LocalFile | RemoteUrl
│   │   │       ├── EnrichmentMetadata.java
│   │   │       ├── EnrichmentTempFiles.java         # AutoCloseable 4-file cleanup
│   │   │       ├── TopicalAuthorityResult.java
│   │   │       ├── TopicalAuthorityPrompt.java      # buildPrompt + defensive JSON parser
│   │   │       └── VideoDerivativeCliRunner.java    # --cli.input CLI smoke runner
│   │   └── resources/
│   │       ├── application.properties
│   │       └── templates/
│   │           ├── index.html                       # generic scraper
│   │           ├── result.html
│   │           ├── video-form.html
│   │           ├── video-result.html
│   │           ├── video-pipeline.html
│   │           └── video-upload.html
│   └── test/
│       └── java/com/example/scraper/
│           ├── video/
│           │   ├── FfmpegDerivativeServiceTest.java # 7 cases
│           │   └── TopicalAuthorityPromptTest.java  # 19 cases
│           ├── service/
│           │   └── VideoEnrichmentServiceTest.java   # 3 cases (real ffmpeg, mocked Ollama + Bunny)
│           └── util/
│               └── HttpVideoDownloaderTest.java      # 8 cases
└── target/
    ├── classes/                                       # compiled
    ├── test-fixtures/                                 # generated by tests
    └── ...
```

## 🔌 Endpoints

### Web Scraping
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Generic scraper form |
| `POST` | `/scrape` | Extract `<a href>` links from a URL |

### Video Scraping
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/video` | Video scraper form |
| `POST` | `/video/scrape` | Extract video URLs from a page |
| `GET` | `/video/pipeline` | Pipeline form |
| `GET` | `/video/upload` | Direct upload form |

### Video Ingestion (API)
| Method | Path | Body | Description |
|--------|------|------|-------------|
| `POST` | `/api/video/enrich` | multipart | **Recommended.** Real ffmpeg + Bunny + Ollama. Accepts `videoFile` **or** `videoUrl`. |
| `POST` | `/api/video/pipeline` | JSON | Page-URL → first video URL → enrich. |
| `POST` | `/api/video/ingest-file` | multipart | Direct file upload → enrich. |

### Video Proxy
| Method | Path | Parameters | Description |
|--------|------|-----------|-------------|
| `GET` | `/video/stream` | `url`, `referer` (opt) | Proxy a video with browser-like headers. |

### Internal (CLI smoke)
```
./mvnw spring-boot:run -Dspring-boot.run.arguments="--cli.input=path/to/video.mp4 --cli.out=/tmp/out --cli.name=demo"
```
Runs the ffmpeg pipeline on `cli.input` and writes `{demo.compressed.mp4, demo.preview.mp4, demo.thumbnail.jpg}` into `cli.out`. The web app stays running on port 8080 after the CLI work finishes.

---

## 🏗 Architecture

### Service graph

```
                    HTTP
                      │
                      ▼
            ScraperController  ─────────────────────────────────────────────┐
            │  /api/video/enrich (multipart, file OR url)                 │
            │  /api/video/pipeline (page-URL JSON)                        │
            │  /api/video/ingest-file (multipart)                         │
            └─────┬────────────────────────────────────────────┬──────────┘
                  │                                            │
                  │ (URL scrape)                  (file/url)   │
                  ▼                                            ▼
       VideoIngestionPipelineService            VideoEnrichmentService
       (page-URL → first video URL)            (orchestrator)
                  │                                    │
                  └──────────────┬─────────────────────┘
                                 │
                ┌────────────────┼────────────────┐
                │                │                │
                ▼                ▼                ▼
       HttpVideoDownloader   FfmpegDerivativeService     BunnyAssetService
       (URL → temp file,      (probe + process →         (streaming Path
        SSRF guard,           compressed + preview +      upload × 3, no
        size cap)             thumbnail, all on disk)     byte[] allocation)
                                                                 │
                ┌────────────────┐                               ▼
                │                │                       Bunny CDN
                ▼                ▼
       (all 3 derivatives       OllamaService  ──►  local LLM
        to Bunny CDN)            (Topical Authority
                                 prompt + JSON-mode)
                │                │
                ▼                │
       CatalogService ◄──────────┘
       (atomic JSON I/O,
        slug uniqueness,
        summarize())
                │
                ▼
           videos.json
```

### Design patterns

- **Strategy** — `SiteScraper` per host, auto-discovered as `@Component`.
- **Sealed sum types** — `EnrichmentSource` (LocalFile | RemoteUrl) so the orchestrator has a closed set of inputs.
- **AutoCloseable record** — `EnrichmentTempFiles` makes try-with-resources the canonical cleanup pattern.
- **Sealed-style helper** — `TopicalAuthorityPrompt` is a pure-function class (no Spring) for easy unit testing.
- **Sweeper for crash recovery** — `VideoEnrichmentService.@PostConstruct` removes stale `scraper-enrich-*` dirs from prior crashed JVMs.

### Data flow: a single `/api/video/enrich` request

```
Controller
  ├─ Validate (title present, exactly one of file/url)
  ├─ MultipartFile.transferTo(tmp)  OR  pass URL through
  ▼
VideoEnrichmentService
  ├─ Create per-request tempdir (UUID)
  ├─ For URL: HttpVideoDownloader → tempdir/input.bin (SSRF guard, 2 GB cap)
  ├─ FfmpegDerivativeService.probe(input)        → VideoMetadata (duration!)
  ├─ FfmpegDerivativeService.process(input, dir) → Derivatives (3 paths)
  ├─ BunnyAssetService.uploadBytes × 3           → 3 URLs (shared 12-hex UUID)
  ├─ VideoCatalogService.summarize()             → compact JSON
  ├─ OllamaService.generate(prompt, {format:json, temperature:0.2})
  ├─ TopicalAuthorityPrompt.parse(response)      → TopicalAuthorityResult
  ├─ Build VideoCatalogEntry (duration from probe, all fields populated)
  ├─ VideoCatalogService.append(entry)           → atomic write to videos.json
  └─ Cleanup: EnrichmentTempFiles.close() (4 deletes) + tempdir removal
```

---

## 🚀 Setup & Installation

### Prerequisites
- **Java 21** or higher
- **Maven 3.8+** (the bundled `mvnw` works)
- **ffmpeg** + **ffprobe** on `PATH` (used by the enrichment pipeline). Install via your package manager; on Windows a portable build at `D:\PATH_Programs\` works as long as both `.exe`s are on `PATH`.
- **Ollama** *(optional but recommended)* — `ollama serve` on `localhost:11434` with a model pulled (default `mistral`). The endpoint still works without Ollama — it'll just use the input metadata unchanged and add a warning.

### Steps

1. **Clone & build**
   ```bash
   git clone <repo-url>
   cd springboot-scraper
   ./mvnw clean package
   ```

2. **Run the app**
   ```bash
   ./mvnw spring-boot:run
   ```
   Web UI at **http://localhost:8080**.

3. **Hit the enrichment endpoint**
   ```bash
   curl -X POST http://localhost:8080/api/video/enrich \
     -F "title=Test" \
     -F "videoFile=@/path/to/video.mp4"
   ```

### Dev mode (hot reload)
```bash
./mvnw spring-boot:run -Dspring-boot.run.fork=false
```
Spring DevTools auto-reloads on class changes.

### Tests
```bash
./mvnw test                                                    # full suite (37 tests, ~45s)
./mvnw -Dtest=FfmpegDerivativeServiceTest test                # just the ffmpeg tests
./mvnw -Dtest=TopicalAuthorityPromptTest test                 # just the prompt parser
./mvnw -Dtest=VideoEnrichmentServiceTest test                 # the orchestrator
./mvnw -Dtest=HttpVideoDownloaderTest test                    # the URL downloader
```
Tests that need real `ffmpeg` (FfmpegDerivativeServiceTest, VideoEnrichmentServiceTest) skip cleanly when `ffmpeg` is not on `PATH`.

---

## ⚙️ Configuration

All settings live in `src/main/resources/application.properties`.

```properties
# --- Server ---
server.port=8080
spring.application.name=scraper
spring.thymeleaf.cache=false

# --- Multipart upload limits (POST /api/video/enrich accepts up to ~2 GB) ---
spring.servlet.multipart.max-file-size=2GB
spring.servlet.multipart.max-request-size=2GB
server.tomcat.max-swallow-size=-1
server.tomcat.connection-timeout=120s

# --- Catalog ---
app.catalog.videos-file=../src/data/videos.json   # relative to JVM CWD

# --- Bunny CDN ---
app.bunny.enabled=false                            # set to true to upload
app.bunny.storage-zone=
app.bunny.api-key=
app.bunny.storage-region=
app.bunny.pull-base-url=https://example.b-cdn.net
app.bunny.folder=videos

# --- Ollama ---
app.ollama.enabled=true
app.ollama.url=http://localhost:11434
app.ollama.model=mistral
app.ollama.timeout-seconds=120

# --- FFmpeg / FFprobe (system binaries) ---
app.ffmpeg.path=ffmpeg
app.ffprobe.path=ffprobe
app.ffmpeg.preview-duration-seconds=5
app.ffmpeg.thumbnail-position=0.20
app.ffmpeg.preview-position=0.40
app.ffmpeg.target-max-width=1280
app.ffmpeg.target-max-height=720
app.ffmpeg.preview-crf=28
app.ffmpeg.preview-preset=fast
app.ffmpeg.compressed-crf=23
app.ffmpeg.compressed-preset=medium
app.ffmpeg.thumbnail-quality=2

# --- Enrichment endpoint ---
app.enrichment.ffmpeg-timeout-seconds=300
app.enrichment.ollama-format=json
app.enrichment.ollama-temperature=0.2
app.enrichment.max-download-bytes=2147483648
app.enrichment.download-connect-timeout-seconds=30
app.enrichment.download-read-timeout-seconds=300
app.enrichment.sweeper-stale-minutes=60
```

### Property reference

| Property | Default | Purpose |
|----------|---------|---------|
| `server.port` | `8080` | HTTP port |
| `spring.servlet.multipart.max-file-size` | `2GB` | Max upload size |
| `app.catalog.videos-file` | `../src/data/videos.json` | Where the catalog JSON is read/written |
| `app.pipeline.mock-uploads` | `true` | (legacy, no longer used) |
| `app.bunny.enabled` | `false` | If false, bunny "uploads" short-circuit and return a would-be public URL |
| `app.bunny.storage-zone` / `api-key` / `storage-region` / `pull-base-url` | empty / `https://example.b-cdn.net` | Bunny credentials + CDN base |
| `app.ollama.url` / `model` / `timeout-seconds` | `http://localhost:11434` / `mistral` / `120` | LLM endpoint |
| `app.ffmpeg.path` / `app.ffprobe.path` | `ffmpeg` / `ffprobe` | Must resolve on `PATH` |
| `app.ffmpeg.target-max-width/height` | `1280` / `720` | Cap applied to compressed + preview |
| `app.ffmpeg.thumbnail-position` | `0.20` | Where in the timeline the thumbnail frame is taken |
| `app.ffmpeg.preview-position` | `0.40` | Where the 5-second preview clip starts |
| `app.enrichment.ffmpeg-timeout-seconds` | `300` | Per ffmpeg sub-encode (probe/process); 3× for process |
| `app.enrichment.max-download-bytes` | `2147483648` | Cap on URL-based video download (2 GB) |
| `app.enrichment.sweeper-stale-minutes` | `60` | Temp dirs older than this are swept on app start |

---

## 📖 Usage Guide

### Example 1: Scrape a website
```
http://localhost:8080/  →  enter a URL  →  see all <a> links
```

### Example 2: Extract videos from Erome
```
http://localhost:8080/video  →  enter an Erome album URL
```

### Example 3: Enrich a video via the API
```bash
curl -X POST http://localhost:8080/api/video/enrich \
  -F "title=Amateur Couple Bedroom" \
  -F "description=Authentic bedroom scene" \
  -F "category=Amateur" \
  -F "tags=amateur,couple,bedroom" \
  -F "videoFile=@./my-video.mp4"
```
Response is a fully populated `VideoCatalogEntry` (see the JSON example in [Core Features §4](#4-video-enrichment-apivideoenrich--recommended-endpoint)).

### Example 4: Enrich from a remote URL
```bash
curl -X POST http://localhost:8080/api/video/enrich \
  -F "title=My Video" \
  -F "videoUrl=https://example.com/path/to/video.mp4"
```
The URL is downloaded server-side (SSRF-guarded, size-capped), processed, uploaded, and cataloged.

### Example 5: Page-URL → enrich
```bash
curl -X POST http://localhost:8080/api/video/pipeline \
  -H "Content-Type: application/json" \
  -d '{
    "sourcePageUrl": "https://www.erome.com/a/abc123",
    "title": "My Video Title",
    "category": "Amateur",
    "tags": "couple, bedroom"
  }'
```

### Example 6: CLI smoke (no HTTP)
```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--cli.input=./test.mp4 --cli.out=/tmp/out --cli.name=demo"
```
Generates `{demo.compressed.mp4, demo.preview.mp4, demo.thumbnail.jpg}` in `/tmp/out`. Useful for verifying the ffmpeg pipeline without the catalog.

### Example 7: Stream via the proxy
```bash
curl "http://localhost:8080/video/stream?url=https://hotlink-protected.example.com/v.mp4&referer=https://hotlink-protected.example.com/"
```

---

## 💡 Use Cases

### 1. End-to-end video archival
Scrape a video from a supported host, enrich it via the LLM for SEO metadata, and persist it to the catalog with thumbnails and previews ready to ship to a CDN.

### 2. Bulk ingestion
The `POST /api/video/enrich` endpoint is designed for a pipeline. Send batches of `{videoUrl, title, ...}` records through curl/Postman; each call produces a catalog entry.

### 3. Topical Authority building
The Ollama prompt asks the LLM to *prefer existing categories and tags* from the existing catalog, so a single site's entries naturally cluster — better internal linking, better SEO.

### 4. CDN hotlink bypass
Stream any CDN-protected video through `/video/stream` with browser-like headers.

### 5. Multi-host uploads
All three derivatives (thumbnail/preview/compressed) go to Bunny CDN as separate objects sharing a 12-hex-char UUID.

---

## 🔒 Security Considerations

### Implemented protections

1. **SSRF (Server-Side Request Forgery) prevention** — both `VideoProxyController` and `HttpVideoDownloader` reject:
   - `localhost`, `*.local`
   - Any resolved IP that is loopback / link-local / site-local / multicast
2. **Download size cap** — `app.enrichment.max-download-bytes` (default 2 GB) checked against `Content-Length` and enforced mid-stream.
3. **Multipart limits** — file size + request size + Tomcat swallow size all configured.
4. **Filename sanitization** — uploaded files are written to UUID temp paths; the original filename is used for the slug only (not the on-disk path).
5. **Atomic catalog writes** — `videos.json` is never partially written; a crash mid-write preserves the previous good copy.
6. **Per-fmpeg timeout** — ffmpeg calls are wrapped with `Future.get(timeout)` + cancel so a wedged encoder can't hang the JVM.
7. **ffmpeg concurrency cap** — `Semaphore(availableProcessors / 2)` around ffmpeg so concurrent requests don't saturate CPU.

### Recommendations for production

1. **Authentication** — currently the API is open. Add a shared-secret header check or Spring Security.
2. **Rate limiting** — per-IP rate limits on the enrichment endpoint (it does ~10-30s of CPU work per request).
3. **Reverse proxy timeouts** — the enrichment request takes ~70s end-to-end. Configure your reverse proxy (`nginx`, Cloudflare) to allow 100-120s upstream timeouts.
4. **TLS** — run behind HTTPS in production; the cookie/session model is currently insecure for any non-localhost use.
5. **Secrets** — store Bunny API key, Ollama URL, etc. in environment variables rather than the properties file.

---

## 📦 Adding a New Site Scraper

1. Create a `@Component` implementing `SiteScraper`:
   ```java
   @Component
   @Order(20)
   public class MyNewScraper implements SiteScraper {
       @Override public boolean supports(String url) { return url != null && url.contains("mysite.com"); }
       @Override public String siteName() { return "MyNewSite"; }
       @Override public VideoResult extractVideos(String url) throws IOException { /* ... */ }
   }
   ```
2. Spring auto-discovers the bean.
3. `VideoScraperService` routes to it via `supports(url)`.
4. No other changes needed.

---

## ⚠️ Known Limitations

These are documented in the project plan (`plans/`) and flagged for follow-up:

| # | Limitation | Mitigation today | Workaround |
|---|------------|------------------|------------|
| L1 | **Enrichment endpoint is synchronous (~70s)** | Tomcat `connection-timeout=120s` | Set reverse-proxy timeouts ≥ 100s; or rewrite as `202 Accepted` + poll |
| L2 | **No auth on `/api/video/enrich`** | Bound to localhost by default | Add a shared-secret header check or Spring Security |
| L3 | **Concurrent enrich requests see a stale catalog** (TOCTOU) | None | Documented; serialize via a single-thread executor or accept the race |
| L4 | **Bunny orphans on partial upload** (uploads 1-2 succeed, 3 fails → catalog never written) | None | Documented; future: a daily sweep that diffs Bunny ↔ catalog |
| L5 | *removed — Bunny is now the only CDN* | n/a | n/a |
| L6 | **No progress reporting** during long ffmpeg encodes | SLF4J logs `start` / `done` per output | Future: wire `FFmpegProgressListener` |
| L7 | **`videos.json` path is CWD-relative** | Resolved with `toAbsolutePath().normalize()` on read | Document; consider making absolute in `application.properties` |

---

## 🐛 Troubleshooting

### `UnsupportedClassVersionError` / Spring Boot fails to start
**Cause:** Java 8/11 in `JAVA_HOME`; Spring Boot 3.3.4 needs Java 17+ and the project targets Java 21.  
**Fix:** Set `JAVA_HOME` to a JDK 21 install (e.g. `C:\Users\nicol\.jdks\openjdk-21.0.1`), then `./mvnw -v` to confirm.

### `ffmpeg not found` / tests skip
**Cause:** ffmpeg/ffprobe not on `PATH`.  
**Fix:** Install ffmpeg (`winget install Gyan.FFmpeg` on Windows, `apt install ffmpeg` on Debian/Ubuntu, `brew install ffmpeg` on macOS), or set `app.ffmpeg.path=D:/path/to/ffmpeg.exe` in `application.properties`.

### Ollama timeout / no response
**Cause:** Ollama not running or wrong URL.  
**Fix:** `curl http://localhost:11434/api/tags` to verify; the endpoint still works without Ollama but uses fallback metadata and adds a warning.

### `/api/video/enrich` returns 500 with `One of videoFile or videoUrl is required`
**Cause:** Validation fired (you sent neither, or both).  
**Fix:** Send exactly one of `videoFile` / `videoUrl`. (Older `400` vs `500` is now fixed — this is a 400.)

### Port 8080 already in use
**Fix:** `server.port=8081` in `application.properties` (or stop the other process).

### Bunny CDN upload fails with non-2xx
**Cause:** API key/zone wrong, or quota exceeded.  
**Fix:** Verify `app.bunny.*` properties. With `app.bunny.enabled=false`, uploads short-circuit and return mock URLs — useful for local dev.

### Spring DevTools keeps reloading mid-encode
**Cause:** Auto-restart triggered by some file change.  
**Fix:** Disable DevTools in production profile, or exclude the catalog path from the trigger set.

---

## 📝 License

[Add your license here]

---

## 👥 Contributors

[Add contributors here]

---

## 📞 Support

For issues and questions:
- Open an issue on GitHub
- Check existing documentation
- Review code comments

---

**Last Updated:** June 4, 2026 (rev: removed Doodstream/Vidara, Bunny-only)
