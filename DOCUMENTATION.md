# LeetCode Contest Monitor v5 — Documentation

> [!IMPORTANT]
> **Start the backend before using the extension.** The extension will display a "Backend not running" banner and all monitoring will be inactive until the Spring Boot server is running.

## Quick Start

### 1. Start the Backend
```bash
cd backend
mvn spring-boot:run
# Server starts on http://localhost:8080 (localhost only)
```

### 2. Install the Extension
1. Open Chrome → `chrome://extensions`
2. Enable "Developer mode" (top right)
3. Click "Load unpacked" → select the `extension/` directory
4. Note the Extension ID shown on the card

### 3. Pin the Extension ID (Required for CORS)
To keep the extension ID stable across reloads:
```bash
openssl genrsa 2048 | openssl pkcs8 -topk8 -nocrypt -out key.pem
openssl rsa -in key.pem -pubout -outform DER | openssl base64 -A
```
Paste the base64 output into the `"key"` field in `extension/manifest.json`.
Update the `EXTENSION_ORIGIN` constant in `backend/src/main/java/com/leetcode/monitor/config/CorsConfig.java` with `chrome-extension://<your-id>`.

### 4. Configure the Contest URL
1. Click the extension icon → options page
2. Enter the LeetCode contest URL (e.g. `https://leetcode.com/contest/weekly-contest-400/`)
3. Optionally set the monitoring interval (default 5 minutes, allowed 1–60)
4. Click Save — discovery begins automatically when the contest URL is new or changed. After the 4 problem tabs open, the extension runs one scrape cycle immediately and registers `scrapeCycle` at the saved interval. Changing only the interval re-registers the alarm; it does not re-run discovery.

## Architecture

| Component | Technology | Responsibility |
|---|---|---|
| Extension | Chrome MV3, JavaScript | DOM scraping, tab lifecycle, alarm scheduling |
| Backend | Spring Boot 3.x, Java 17 | Parsing, calculation, ranking, state, REST API |
| Communication | HTTP/JSON, localhost:8080 | POST ingest, GET status/health |

## REST API Contract

### POST /api/contest/config
Registers contest metadata after discovery.
```json
{
  "contestUrl": "https://leetcode.com/contest/...",
  "questions": [
    { "questionNumber": "Q1", "problemName": "...", "problemUrl": "..." }
  ]
}
```

### POST /api/contest/ingest/{questionNumber}
Called once per question per monitoring cycle. Path slot is `Q1`–`Q4` (the extension normalizes `1` / `"Q1"` to `Qn`).

Request:
```json
// On success:
{ "rawUsersAccepted": "28,903 / 31.1K", "scrapingStatus": "SUCCESS", "selectorStrategyUsed": "text-anchored" }
// On scrape failure / timeout:
{ "scrapingStatus": "NAVIGATION_TIMEOUT", "rawUsersAccepted": null, "errorMessage": "Scrape timed out after 20000ms for Q1" }
```

Response `200` — updated `QuestionStats`:
```json
{
  "questionNumber": "Q1",
  "problemName": "min-chairs",
  "acceptedUsers": 28903,
  "totalUsers": 31100,
  "usersAcceptedPercentage": 92.9356913183,
  "scrapingStatus": "SUCCESS",
  "selectorStrategyUsed": "text-anchored",
  "timestamp": "2026-09-05T06:55:00Z"
}
```
SUCCESS with unparseable text or `totalUsers == 0` still returns 200; `scrapingStatus` is `PARSE_ERROR` and the three metric fields are `null`.

### GET /api/contest/status
Returns the latest ContestStats snapshot.
```json
{
  "lastUpdated": "2024-01-01T12:00:00Z",
  "questions": [ ... ],
  "ranking": ["Q3", "Q1", "Q4", "Q2"],
  "recentChanges": [ { "description": "Q3 overtook Q1", "timestamp": "..." } ]
}
```

### GET /api/contest/health
```json
{
  "status": "OK",
  "lifecycleState": "MONITORING",
  "backendVersion": "1.0.0",
  "lastIngestReceivedAt": { "Q1": "...", "Q2": "...", "Q3": "...", "Q4": "..." }
}
```

## Scraping Status Values
| Status | Meaning |
|---|---|
| `SUCCESS` | Raw string scraped and sent |
| `LOGIN_WALL` | Login form detected — user must be logged in |
| `SELECTOR_NOT_FOUND` | "Users Accepted" element not found |
| `PARSE_ERROR` | Backend could not parse the raw string |
| `NAVIGATION_TIMEOUT` | Page load timed out |
| `PAGE_UNAVAILABLE` | Page returned error or is inaccessible |
| `BROWSER_ERROR` | Chrome extension error |
| `TAB_MISSING` | Background tab was closed; recovery attempted |
| `UNKNOWN_ERROR` | Unexpected error |

## Security Notes
- Backend binds to `127.0.0.1` only — never accessible from LAN
- CORS restricted to `chrome-extension://<pinned-id>` — never `*`
- No LeetCode credentials, cookies, or tokens are read, stored, or logged on either side
- Backend only ever receives already-scraped text strings

## Module Reference

### Extension
| File | Purpose |
|---|---|
| `background/background.js` | Service worker — discovery, `handleScrapeResult` → `postIngest`, interval re-register, `CONTEST_ENDED` hook, SW alarm restore |
| `background/tabLifecycleManager.js` | 4-tab persistence, reload cycle, recovery |
| `background/alarmScheduler.js` | Configurable `scrapeCycle` (default 5 min, clamp 1–60); `runScrapeCycle` Q1→Q4; pending-scrape map; `stopMonitoringAlarm` / `handleContestEnded` Phase 11 hook; 20s `NAVIGATION_TIMEOUT` |
| `background/backendClient.js` | HTTP client; `postIngest` normalizes slot to `Qn` |
| `content-scripts/contestPageScript.js` | Contest homepage discovery |
| `content-scripts/problemPageScript.js` | Problem page scraping |
| `ui/sidepanel.html/js/css` | Dashboard, polls backend every 5s |
| `ui/popup.html/js` | Lightweight fallback |
| `ui/options.html/js` | Contest URL + scrape interval (1–60 min) |

### Backend
| File | Purpose |
|---|---|
| `ContestConfigController.java` | POST /api/contest/config |
| `ContestIngestController.java` | POST /api/contest/ingest/{q} |
| `ContestStatusController.java` | GET /api/contest/status |
| `ContestHealthController.java` | GET /api/contest/health |
| `ContestStateService.java` | Critical section, AtomicReference snapshot |
| `AcceptanceCalculationService.java` | BigDecimal percentage |
| `ComparisonService.java` | Pairwise overtake detection |
| `ContestLifecycleService.java` | Signal-based ENDED detection |
| `AcceptanceStatsParser.java` | Raw string → BigDecimal |
| `CorsConfig.java` | CORS restricted to extension origin |

### Repo root
| File | Purpose |
|---|---|
| `models.md` | Agent Council role → plan model vs the model that actually ran |
