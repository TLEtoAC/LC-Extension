# PROGRESS.md — LeetCode Contest Acceptance Monitor v5

> Single source of truth for build progress. Updated after every phase.  
> Architecture: Chrome MV3 Extension (DOM scraping) ↔ HTTP/JSON ↔ Local Spring Boot Backend (Java 17)

---

## Build Status

| Component | Status |
|---|---|
| Backend (`mvn test`) | ✅ **87 / 87 tests passing** |
| Extension (Jest) | ✅ **8 / 8 tests passing** |
| Extension (syntax check) | ✅ All JS files pass `node --check` |
| GitHub (`origin/phase1`) | ✅ Latest commit: `5c0ea2e` |

---

## Phase Completion Tracker

| # | Phase | Lead Agent | Status | Commit / Notes |
|:---:|---|---|:---:|---|
| 1 | Backend Skeleton | REST API Agent | ✅ Done | `88ecec7` |
| 2 | Extension Scaffold + Tab Lifecycle | Extension/Background Agent | ✅ Done | `88ecec7` |
| 3a | Discovery — `contestPageScript.js` | Content Script/DOM Agent | ✅ Done | `5c0ea2e` |
| 3b | Config API — `ContestConfigController` + Models | REST API Agent | ✅ Done | `5c0ea2e` |
| 3c | background.js discovery wiring | Extension/Background Agent | ✅ Done | `5c0ea2e` |
| 4 | Single-Q Scraping — `problemPageScript.js` + `ContestIngestController` | Content Script/DOM + REST API Agents | ✅ Done | `5c0ea2e` |
| 5 | Parser Hardening — `AcceptanceStatsParser` + BigDecimal tests | Parser/Data Agent + Testing/QA Agent | ✅ Done | files present, 40 parser tests passing |
| 6 | Four Questions + Concurrency | Extension/Background + Backend/Core Agents | ✅ Done | alarm + first cycle pulled into this phase (ADR-015) |
| 7 | Alarm Scheduler | Extension/Background Agent | ✅ Done | Interval 1–60 + ENDED hook (`feat/phase7-alarm-polish`); core loop was Phase 6 (ADR-015) |
| 8 | State + Ranking | Backend/Core Agent | ✅ Done | `RankingService` descending %; 72 tests |
| 9 | Overtaking Detection | Backend/Core Agent | ✅ Done | `ComparisonService` pairwise; 79 tests |
| 10 | Status/Health API + Side Panel UI | REST API + UI Agents | ✅ Done | status + health + side panel/popup |
| 11 | Robustness + Lifecycle | Backend/Core + Ext/Background + Edge-Case Red Team | ✅ Done | ENDED + recoverMissingTabs; 87 JUnit + 8 Jest |
| — | Continuous Docs | Logging/Docs Agent | 🔄 Active | All 5 artifacts maintained |

---

## Files Implemented (as of 2026-09-05)

### Extension (`extension/`)

| File | Phase | Description |
|---|:---:|---|
| `manifest.json` | 2 | MV3 manifest — permissions, side panel, content script patterns, key placeholder |
| `package.json` | 2 | Jest ^29 test config |
| `background/background.js` | 2 / 3c / 6 / 7 / 10 | Service worker — discovery, ingest, interval, `CONTEST_ENDED`, `OPEN_SIDE_PANEL` |
| `background/tabLifecycleManager.js` | 2 / 11 | Tab persistence, event-driven reload, `recoverMissingTabs` |
| `background/alarmScheduler.js` | 6 / 7 / 11 | scrapeCycle; recover tabs; `observeEndedAndStop` → `handleContestEnded` |
| `background/backendClient.js` | 2 / 6 / 10 | `fetch()` wrapper — ingest + `getStatus` / `checkHealth` |
| `content-scripts/contestPageScript.js` | 3a | Contest homepage discovery — `MutationObserver`, href-first + click-and-capture fallback, 5 selector strategies |
| `content-scripts/problemPageScript.js` | 4 | Problem page scraper — `MutationObserver`, 3-tier selector chain, double-read stability, login-wall & 404 detection |
| `ui/options.html` | 2 / 7 | Contest URL + monitoring interval (1–60 min) |
| `ui/options.js` | 2 / 7 | Persists URL + `monitoringIntervalMinutes`; `CONTEST_URL_SAVED` / `MONITORING_INTERVAL_SAVED` |
| `ui/sidepanel.html/js/css` | 10 | Dashboard — 5s status poll, ranking, overtakes, unreachable/empty/ENDED |
| `ui/popup.html/js` | 10 | Lightweight ranking + open side panel |
| `tests/httpBoundaryProof.md` | 2 | Manual test guide for the extension↔backend HTTP boundary |
| `tests/tabLifecycle.test.js` | 11 | Jest — cycleInProgress + recoverMissingTabs |
| `tests/backendClient.test.js` | 11 | Jest — Qn normalize, drop-on-unreachable, refused fetch |
| `tests/alarmEnded.test.js` | 11 | Jest — ENDED observe uses Phase 7 hook |

### Backend (`backend/`)

| File | Phase | Description |
|---|:---:|---|
| `pom.xml` | 1 | Spring Boot 3.2.5, Java 17, Web + Test — no Playwright |
| `src/main/resources/application.properties` | 1 | Binds to `127.0.0.1:8080` (loopback only) |
| `config/CorsConfig.java` | 1 | CORS restricted to `chrome-extension://PLACEHOLDER_EXTENSION_ID` |
| `controller/ContestHealthController.java` | 1 / 10 | `GET /api/contest/health` — lifecycle, lastIngestReceivedAt, questionStatuses |
| `controller/ContestStatusController.java` | 10 | `GET /api/contest/status` — lock-free snapshot or empty envelope |
| `controller/ContestConfigController.java` | 3b | `POST /api/contest/config` — validates 4 questions, initializes state |
| `controller/ContestIngestController.java` | 4 | `POST /api/contest/ingest/{Q1-Q4}` — validates, delegates to `ContestStateService.applyIngest` |
| `dto/ContestConfigRequest.java` | 3b | Inbound DTO for contest configuration |
| `dto/ContestIngestRequest.java` | 4 | Inbound DTO for scrape result ingestion |
| `model/ScrapingStatus.java` | 3b | Enum: `SUCCESS`, `LOGIN_WALL`, `SELECTOR_NOT_FOUND`, `PARSE_ERROR`, `NAVIGATION_TIMEOUT`, `PAGE_UNAVAILABLE`, `BROWSER_ERROR`, `TAB_MISSING`, `UNKNOWN_ERROR` |
| `model/Question.java` | 3b | Immutable record: `questionNumber`, `problemName`, `problemUrl` |
| `model/QuestionStats.java` | 3b | Metric container with `uninitialised(Question)` factory |
| `model/ContestStats.java` | 3b | Immutable snapshot — `questions`, `ranking`, `recentChanges`, `lifecycleState`, `pairwiseRelationships` |
| `model/RankingChange.java` | 3b | Overtake event record |
| `parser/AcceptanceStatsParser.java` | 5 | Parses `"28,903 / 31.1K"` → `BigDecimal` — K/M/B suffix support, zero `double` math |
| `parser/ParsedAcceptance.java` | 5 | Record: `BigDecimal acceptedUsers`, `BigDecimal totalUsers` |
| `parser/ParseException.java` | 5 | Unchecked (`IllegalArgumentException`) for malformed input |
| `service/AcceptanceCalculationService.java` | 6 | `acceptedUsers × 100 / totalUsers`, scale 10 HALF_UP; zero total throws |
| `service/ContestStateService.java` | 3b / 4 / 6 / 8 / 9 / 11 | `synchronized applyIngest`: parse, rank, overtakes, lifecycle, publish |
| `service/RankingService.java` | 8 | Descending % ranking; null last; ties by Qn |
| `service/ComparisonService.java` | 9 | Pairwise Qx <= Qy → Qx > Qy; snapshot pairwise map dedup |
| `service/ContestLifecycleService.java` | 11 | 3 identical complete rounds → ENDED |

### Test Suite (`backend/src/test/`)

| Test Class | Tests | Phase | Coverage |
|---|:---:|:---:|---|
| `ContestHealthControllerTest` | 2 | 1 / 10 | Health shape + lastIngestReceivedAt after ingest |
| `ContestStatusControllerTest` | 2 | 10 | Empty envelope + configured ranking snapshot |
| `ContestConfigControllerTest` | 7 | 3b | Valid config, 4-question constraint, duplicate slots, blank fields |
| `ContestIngestControllerTest` | 9 | 4 / 6 | Valid ingest + numeric fields, zero-total `PARSE_ERROR`, `LOGIN_WALL`, `SELECTOR_NOT_FOUND` |
| `AcceptanceStatsParserTest` | 40 | 5 | Plain integers, commas, K/M/B suffixes, float-drift regression, null/malformed error handling |
| `AcceptanceCalculationServiceTest` | 3 | 6 | 28903/31100 → 92.9356913183; zero total; null args |
| `ContestStateServiceConcurrencyTest` | 4 | 6 / 8 | 4 simultaneous ingests, last-write-wins, isolated parse failure, concurrent readers + ranking |
| `RankingServiceTest` | 5 | 8 | Descending order, ties, null %, all-null, empty input |
| `ContestStateServiceRankingTest` | 3 | 8 | Empty on init, snapshot publish, PARSE_ERROR last |
| `ComparisonServiceTest` | 5 | 9 | Overtake, no-duplicate, tie, PARSE_ERROR isolation, lead→tie |
| `ContestStateServiceOvertakeTest` | 2 | 9 | Snapshot publish + no duplicate after unchanged ingest |
| `ContestLifecycleServiceUnitTest` | 3 | 11 | 3-cycle ENDED, change reset, PARSE_ERROR no-end |
| `ContestStateServiceLifecycleTest` | 2 | 11 | applyIngest publishes ENDED; change keeps MONITORING |
| **Total** | **87** | — | **100% passing** |

### Knowledge Artifacts (repo root)

| File | Status |
|---|---|
| `CHANGELOG.md` | ✅ Current through Phase 11 |
| `DECISIONS.md` | ✅ ADR-001–026 (ENDED observe → Phase 7 hook) |
| `FLOW.md` | ✅ Cross-process sequence + interval / ENDED / SW-restart |
| `DOCUMENTATION.md` | ✅ Setup, REST API contract, interval options, security notes |
| `LEARN.md` | ✅ End-to-end trace + debugging index |
| `models.md` | ✅ Agent Council role → plan model vs actual (Cursor Grok 4.6) |

---

## What's Next (Phases 8–11)

### Phase 6 — Four Questions + Concurrency ✅
Done. Immediate first cycle + 5-min alarm after discovery. `applyIngest` parses/calculates inside the synchronized critical section with deep-copied `QuestionStats`.

### Phase 7 — Alarm Scheduler ✅
Done on `feat/phase7-alarm-polish`. Core `alarmScheduler.js` landed in Phase 6 (ADR-015). Phase 7: configurable interval (default 5, clamp 1–60). `stopMonitoringAlarm()` / `handleContestEnded()` / `CONTEST_ENDED` message ready for Phase 11. No fake ENDED detection (ADR-021). SW restart restores a missing alarm if still monitoring.

### Phase 8 — State + Ranking ✅
Done. `RankingService` ranks by `usersAcceptedPercentage` descending (ADR-022). Ties by question number. Null percentages last. Ranking is computed inside the same `synchronized applyIngest` and published on the snapshot.

### Phase 9 — Overtaking Detection ✅
Done. `ComparisonService` emits `RankingChange` only on Qx <= Qy → Qx > Qy (ties included, ADR-023). Dedup via snapshot `pairwiseRelationships`. One PARSE_ERROR does not wipe other pairs. Same synchronized publish as ranking.

### Phase 10 — Status/Health API + Side Panel UI ✅
Done. Lock-free `GET /api/contest/status` (empty envelope if uninitialized). Health includes `lastIngestReceivedAt` and `lifecycleState`. Side panel polls every 5s; popup is a ranking fallback. Backend-unreachable is a banner (ADR-009). CORS still pinned — localhost added only to extension `host_permissions` (ADR-024).

### Phase 11 — Robustness + Lifecycle ✅
Done. `ContestLifecycleService` marks ENDED after 3 identical Q1–Q4 percentage rounds (ADR-006). Extension observes via post-cycle status, UI poll, or startup health and stops **only** through `handleContestEnded` (ADR-026). `recoverMissingTabs` reopens closed slots. Jest 8 + JUnit 87.

---

## How to Run

### Start the Backend
```bash
cd backend
mvn spring-boot:run
# Starts on http://localhost:8080 (loopback only)
```

### Run Backend Tests
```bash
cd backend
mvn test
# Expected: 87 tests, 0 failures

### Run Extension Jest
```bash
cd extension
npm test
# Expected: 8 tests, 0 failures
```
```

### Load the Extension
1. Open `chrome://extensions` → Enable **Developer Mode**
2. **Load unpacked** → select `extension/` directory
3. Note your 32-char extension ID
4. Replace `PLACEHOLDER_EXTENSION_ID` in `backend/src/main/java/com/leetcode/monitor/config/CorsConfig.java`
5. Generate a stable key for `manifest.json`:
   ```bash
   openssl genrsa 2048 | openssl pkcs8 -topk8 -nocrypt -out key.pem
   openssl rsa -in key.pem -pubout -outform DER | openssl base64 -A
   ```
   Paste the output as the `"key"` value in `manifest.json`

### Configure a Contest
1. Click the extension icon → **Options**
2. Enter a LeetCode contest URL (e.g. `https://leetcode.com/contest/weekly-contest-400/`)
3. Click **Save** — discovery begins automatically
