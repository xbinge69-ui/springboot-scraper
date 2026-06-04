# springboot-scraper

A Spring Boot web application for scraping web content and videos from supported websites, with advanced video ingestion and CDN management capabilities.

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

## 📌 Overview

This application is designed to:
- **Scrape web content** from any website (links, metadata, etc.)
- **Extract videos** from supported video hosting sites
- **Bypass CDN hotlink protection** through transparent proxying
- **Ingest videos** into a managed catalog with automatic processing
- **Upload to multiple hosts** (Doodstream, Vidara, Bunny CDN)

## 🎯 Core Features

### 1. Generic Web Scraping (`/scrape`)
Extracts all anchor links from any given URL.

**Features:**
- Parses HTML and extracts all `<a>` tags
- Returns: link text, href URL, and title attributes
- Uses Jsoup with Mozilla user-agent spoofing
- HTML form interface for easy access

**Returns:**
- Page title
- List of scraped items (text, href, description)
- Item count

---

### 2. Video Scraping (`/video/scrape`)
Extracts video URLs from supported video hosting sites.

**Supported Sites:**
- **Erome** (`erome.com`)
- Extensible: add new sites by implementing `SiteScraper` interface

**Erome Scraper Strategy:**
The EromeScraper uses multiple strategies to locate video URLs:

1. **Direct `<video>` tags**: `<video src="...">`
2. **Source elements**: `<video><source src="..."></video>`
3. **Lazy-loaded**: `[data-src]` attributes
4. **Inline JS**: MP4 URLs embedded in script tags via regex

Results are deduplicated and returned in discovery order.

**Supported Formats:**
- `.mp4` - MPEG-4 video
- `.m3u8` - HLS streaming
- `.webm` - WebM video codec
- `.mov` - QuickTime video

---

### 3. Video Proxy (`/video/stream`)
Bypasses CDN hotlink protection to stream videos without 403 Forbidden errors.

**How It Works:**
- Receives video URL and optional referer
- Spoofs HTTP headers:
  - Browser User-Agent (Chrome 124)
  - Referer header
  - Origin header
  - Accept-Language header
- Streams video content with proper buffering (64 KB)
- Supports partial content for seeking

**Security Features:**
- Blocks localhost and private IP addresses (SSRF protection)
- Validates URL hosts against allow-list
- Checks for link-local, site-local, and multicast addresses
- Proper timeout handling (20 seconds)

**Response Features:**
- Sets correct Content-Type (video/mp4, application/x-mpegURL, etc.)
- Forwards Accept-Ranges header for seeking
- Streams content-length header for progress bars

---

### 4. Video Ingestion Pipeline (`/api/video/pipeline`)
Comprehensive workflow to automatically process and catalog scraped videos.

**Pipeline Steps:**

1. **Validate Request**
   - Check source URL is provided and valid
   - Must start with `http://` or `https://`

2. **Scrape Video**
   - Extract first video from source page URL
   - Throw error if no videos found

3. **Upload to Hosts**
   - Upload video to Doodstream (primary)
   - Upload video to Vidara (backup)
   - Returns embed URLs for both

4. **Generate Derivatives**
   - Download first 180 KB as JPG candidate (thumbnail)
   - Download first 1.6 MB as MP4 candidate (preview)
   - Upload to Bunny CDN
   - Warning: lightweight mode (use FFmpeg for production quality)

5. **Create Catalog Entry**
   - Generate URL slug from title
   - Set metadata: description, tags, category
   - Assign actress info (ID or unknown name)
   - Record view count
   - Timestamp: `Instant.now()`

6. **Save to Catalog**
   - Append entry to `videos.json`
   - Return entry with warnings

**Request Format (JSON):**
```json
{
  "sourcePageUrl": "https://www.erome.com/a/abc123",
  "title": "Video Title",
  "description": "Video description",
  "tags": "tag1, tag2, tag3",
  "category": "Category Name",
  "unknownActressName": "Actress Name",
  "actressId": "actress-123",
  "views": 1000
}
```

**Response Format:**
```json
{
  "videoCatalogEntry": {
    "slug": "video-title",
    "title": "Video Title",
    "description": "Video description",
    "durationSeconds": 0,
    "thumbnailKey": "https://bunny.cdn/thumbnail.jpg",
    "previewUrl": "https://bunny.cdn/preview.mp4",
    "embedUrl": "https://doodstream.com/d/video-id",
    "backupEmbedUrl": "https://vidara.to/v/video-id",
    "tags": ["tag1", "tag2", "tag3"],
    "category": "Category Name",
    "publishedAt": "2026-06-03T12:34:56.789Z",
    "actressId": "actress-123",
    "unknownActressName": "Actress Name",
    "views": 1000
  },
  "warnings": ["Derivatives generated in lightweight mode. Replace with ffmpeg..."]
}
```

---

### 5. Direct Video Upload (`/api/video/ingest-file`)
Upload video files directly with metadata.

**Request Format (Multipart Form):**
- `videoFile` (required): Video file binary
- `title` (required): Video title
- `description` (optional): Video description
- `category` (optional): Video category
- `tags` (optional): Comma-separated tags
- `unknownActressName` (optional): Actress name if unknown
- `actressId` (optional): Actress ID if known

**Response:** Same as pipeline endpoint

---

## 🛠 Technology Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| **Java** | 21 | Language |
| **Spring Boot** | 3.3.4 | Web framework |
| **Spring Web MVC** | 3.3.4 | REST API & Controllers |
| **Thymeleaf** | 3.x | HTML templating |
| **Jsoup** | 1.17.2 | HTML parsing & CSS selectors |
| **Jackson** | 2.x | JSON processing |
| **Spring DevTools** | 3.3.4 | Hot reload development |
| **Maven** | - | Build tool |

**Optional Integrations:**
- Bunny CDN - asset storage & CDN
- Ollama - local AI model inference
- Doodstream - video hosting
- Vidara - video hosting (backup)

## 📁 Project Structure

```
springboot-scraper/
├── pom.xml                          # Maven configuration
├── README.md                        # This file
├── src/
│   └── main/
│       ├── java/com/example/scraper/
│       │   ├── ScraperApplication.java           # Main entry point
│       │   ├── controller/
│       │   │   ├── ScraperController.java        # Web scraping endpoints
│       │   │   └── VideoProxyController.java     # Video proxy endpoint
│       │   ├── model/
│       │   │   ├── ScrapedItem.java              # Link result model
│       │   │   ├── VideoResult.java              # Video extraction result
│       │   │   ├── PipelineRequest.java          # Pipeline API request
│       │   │   ├── PipelineOutcome.java          # Pipeline API response
│       │   │   ├── DirectVideoIngestRequest.java # Direct upload metadata
│       │   │   ├── VideoCatalogEntry.java        # Catalog entry model
│       │   │   └── ...
│       │   ├── scraper/
│       │   │   ├── SiteScraper.java              # Strategy interface
│       │   │   ├── EromeScraper.java             # Erome implementation
│       │   │   └── GenericVideoScraper.java      # Base scraper
│       │   └── service/
│       │       ├── ScraperService.java           # Generic web scraper
│       │       ├── VideoScraperService.java      # Video site router
│       │       ├── VideoIngestionPipelineService.java # Main pipeline
│       │       ├── DirectVideoIngestService.java # Direct upload handler
│       │       ├── VideoHostUploadService.java   # Host upload logic
│       │       ├── BunnyAssetService.java        # Bunny CDN integration
│       │       ├── MediaDerivativeService.java   # Thumbnail/preview gen
│       │       ├── VideoCatalogService.java      # Catalog persistence
│       │       ├── VideoEnricherService.java     # Metadata enrichment
│       │       └── OllamaService.java            # AI inference
│       └── resources/
│           ├── application.properties            # Configuration
│           └── templates/
│               ├── index.html                    # Generic scraper UI
│               ├── result.html                   # Scraping results
│               ├── video-form.html               # Video scraper UI
│               ├── video-result.html             # Video results
│               ├── video-pipeline.html           # Pipeline UI
│               └── video-upload.html             # Direct upload UI
└── target/                          # Compiled artifacts

```

## 🔌 Endpoints

### Web Scraping

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Show web scraper form |
| `POST` | `/scrape` | Scrape URL for links |

### Video Scraping

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/video` | Show video scraper form |
| `POST` | `/video/scrape` | Extract videos from page |

### Video Ingestion

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/video/pipeline` | Show pipeline form |
| `POST` | `/api/video/pipeline` | Ingest video from URL (JSON) |
| `GET` | `/video/upload` | Show direct upload form |
| `POST` | `/api/video/ingest-file` | Upload & ingest video file |

### Video Proxy

| Method | Path | Parameters | Description |
|--------|------|-----------|-------------|
| `GET` | `/video/stream` | `url`, `referer` (opt) | Proxy video stream |

---

## 🏗 Architecture

### Design Patterns Used

#### 1. **Strategy Pattern** (SiteScraper)
Each supported website has its own scraper implementation:
```
SiteScraper (interface)
├── EromeScraper (Erome.com)
├── GenericVideoScraper (fallback)
└── (+ new implementations for new sites)
```

**Benefits:**
- Isolates site-specific logic
- Easy to add new sites without modifying service layer
- Spring auto-discovers all `@Component` implementations
- Open/Closed Principle

#### 2. **Service Layer**
Organized by concern:
- `VideoScraperService` - Route to correct scraper
- `VideoIngestionPipelineService` - Orchestrate workflow
- `VideoHostUploadService` - Abstract host uploads
- `BunnyAssetService` - Bunny CDN integration
- `VideoEnricherService` - Metadata enrichment
- `OllamaService` - AI features

#### 3. **Dependency Injection**
Spring Boot automatically:
- Discovers all `SiteScraper` implementations
- Injects them into `VideoScraperService`
- Wires all service dependencies

### Data Flow

```
User Input (URL/File)
    ↓
Controller
    ↓
VideoScraperService (route to scraper)
    ↓
SiteScraper (EromeScraper, etc.)
    ↓
VideoResult (extracted URLs)
    ↓
VideoIngestionPipelineService
    ├→ VideoHostUploadService (Doodstream, Vidara)
    ├→ MediaDerivativeService (generate thumbnail/preview)
    ├→ BunnyAssetService (upload assets)
    └→ VideoCatalogService (persist)
    ↓
PipelineOutcome (result + warnings)
```

---

## 🚀 Setup & Installation

### Prerequisites
- **Java 21** or higher
- **Maven 3.8+**
- **Git**

### Steps

1. **Clone the repository**
   ```bash
   git clone https://github.com/your-repo/springboot-scraper.git
   cd springboot-scraper
   ```

2. **Build the project**
   ```bash
   ./mvnw clean package
   ```
   Or on Windows:
   ```batch
   mvnw.cmd clean package
   ```

3. **Run the application**
   ```bash
   ./mvnw spring-boot:run
   ```
   Or directly:
   ```bash
   java -jar target/scraper-0.0.1-SNAPSHOT.jar
   ```

4. **Access the application**
   - Open browser to: **http://localhost:8080**
   - Main menu with links to all features

### Development Setup

For hot reload during development:
```bash
./mvnw spring-boot:run -Dspring-boot.run.fork=false
```

Spring DevTools will auto-reload on file changes.

---

## ⚙️ Configuration

Edit `src/main/resources/application.properties`:

```properties
# Server
server.port=8080
spring.application.name=scraper

# Thymeleaf
spring.thymeleaf.cache=false    # Set to true in production

# Video Catalog
app.catalog.videos-file=../src/data/videos.json

# Pipeline
app.pipeline.mock-uploads=true   # Set to false to use real uploads

# Video Host Configuration
app.upload.doodstream.prefix=https://doodstream.com/d
app.upload.vidara.prefix=https://vidara.to/v

# Bunny CDN Configuration
app.bunny.enabled=false                    # Set to true to enable
app.bunny.storage-zone=your-zone
app.bunny.api-key=your-api-key
app.bunny.storage-region=us-west
app.bunny.pull-base-url=https://example.b-cdn.net
app.bunny.folder=videos

# Ollama (AI Model) Configuration
app.ollama.enabled=true
app.ollama.url=http://localhost:11434
app.ollama.model=mistral
app.ollama.timeout-seconds=120
```

### Configuration Guide

| Property | Purpose | Example Value |
|----------|---------|---------------|
| `server.port` | HTTP server port | `8080` |
| `app.catalog.videos-file` | Catalog JSON location | `../src/data/videos.json` |
| `app.pipeline.mock-uploads` | Mock uploads for testing | `true` |
| `app.bunny.enabled` | Enable Bunny CDN | `false` |
| `app.bunny.api-key` | Bunny API key | `your-key-here` |
| `app.ollama.enabled` | Enable AI inference | `true` |

---

## 📖 Usage Guide

### Example 1: Scrape Generic Website

1. Navigate to **http://localhost:8080/**
2. Enter URL: `https://example.com`
3. Click "Scraper"
4. View extracted links

### Example 2: Extract Videos from Erome

1. Navigate to **http://localhost:8080/video**
2. Enter Erome album URL: `https://www.erome.com/a/xxxxxxxx`
3. Click "Scraper"
4. View extracted video URLs

### Example 3: Run Ingestion Pipeline (API)

**Request:**
```bash
curl -X POST http://localhost:8080/api/video/pipeline \
  -H "Content-Type: application/json" \
  -d '{
    "sourcePageUrl": "https://www.erome.com/a/abc123",
    "title": "My Video Title",
    "description": "A great video",
    "tags": "awesome, video, content",
    "category": "Entertainment",
    "unknownActressName": "Jane Doe",
    "views": 500
  }'
```

### Example 4: Upload Video File Directly

**Request:**
```bash
curl -X POST http://localhost:8080/api/video/ingest-file \
  -F "videoFile=@/path/to/video.mp4" \
  -F "title=My Uploaded Video" \
  -F "description=Uploaded directly" \
  -F "category=Personal" \
  -F "tags=uploaded, personal" \
  -F "unknownActressName=Admin"
```

### Example 5: Stream Video Through Proxy

**Request:**
```bash
curl "http://localhost:8080/video/stream?url=https://example.com/video.mp4&referer=https://example.com/"
```

**Browser:**
```html
<video width="640" height="480" controls>
  <source src="http://localhost:8080/video/stream?url=https://example.com/video.mp4" type="video/mp4">
</video>
```

---

## 💡 Use Cases

### 1. **Video Archive System**
- Scrape video metadata from existing sites
- Organize in unified catalog
- Support multiple backup hosts

### 2. **Content Distribution Network**
- Ingest from primary source
- Distribute to multiple CDNs
- Manage with unified interface

### 3. **Video Library Management**
- Upload local videos
- Auto-generate thumbnails & previews
- Tag and categorize content
- Track view counts

### 4. **CDN Bypass Solution**
- Stream videos blocked by hotlink protection
- Transparent proxy with header spoofing
- No client-side changes needed

### 5. **Content Aggregation**
- Multi-site scraping capability
- Extract & catalog diverse sources
- Extensible architecture for new sites

### 6. **Metadata Enrichment**
- Custom tagging & categorization
- Actress/performer tracking
- View statistics
- AI-powered description generation (via Ollama)

---

## 🔒 Security Considerations

### Implemented Protections

1. **SSRF (Server-Side Request Forgery) Prevention**
   - Blocks localhost and private IP ranges
   - Validates all host addresses
   - Timeout protection (20 seconds)

2. **URL Validation**
   - Only `http://` and `https://` protocols allowed
   - Domain whitelist support (extensible)

3. **Header Spoofing (Intentional)**
   - Browser User-Agent mimicking
   - Referer header spoofing
   - Origin header manipulation
   - Purpose: bypass CDN hotlink protection

### Recommendations

1. **Production Deployment**
   - Set `spring.thymeleaf.cache=true`
   - Add authentication/authorization layer
   - Use HTTPS only
   - Implement rate limiting
   - Add request logging & monitoring

2. **API Security**
   - Implement OAuth2/JWT tokens
   - Add request signing
   - Rate limiting per IP/token
   - Audit logging

3. **Data Protection**
   - Encrypt sensitive configuration
   - Use environment variables for secrets
   - Rotate API keys regularly
   - Validate all user input

---

## 📦 Adding a New Site Scraper

1. **Create scraper class** implementing `SiteScraper`:
   ```java
   @Component
   @Order(20)
   public class MyNewScraper implements SiteScraper {
       
       @Override
       public boolean supports(String url) {
           return url != null && url.contains("mysite.com");
       }
       
       @Override
       public String siteName() {
           return "MyNewSite";
       }
       
       @Override
       public VideoResult extractVideos(String url) throws IOException {
           // Your scraping logic
       }
   }
   ```

2. **Spring auto-discovers** the component
3. **Router automatically** uses it for matching URLs
4. **No other changes needed!**

---

## 🐛 Troubleshooting

### Issue: "Aucun scraper disponible pour : URL"
- **Cause**: URL not supported by any enabled scraper
- **Solution**: Verify URL is for a supported site (e.g., erome.com)

### Issue: "Erreur lors du scraping"
- **Cause**: Network error, malformed HTML, or timeout
- **Solution**: Check URL is accessible, try again with different URL

### Issue: 403 Forbidden from CDN
- **Cause**: Hotlink protection
- **Solution**: Use proxy endpoint: `/video/stream?url=...`

### Issue: Port 8080 already in use
- **Solution**: Change in `application.properties`:
  ```properties
  server.port=8081
  ```

### Issue: Bunny CDN upload fails
- **Cause**: API key misconfigured or quota exceeded
- **Solution**: Check credentials in `application.properties`

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

**Last Updated:** June 3, 2026

