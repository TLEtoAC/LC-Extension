# LEARN — LeetCode Contest Monitor v5

## How It Works (One Sentence)
The Chrome extension scrapes raw "Users Accepted" strings from LeetCode and POSTs them to a local Spring Boot server that parses, calculates, ranks, detects overtakes, and serves the result back to the extension's side panel.

## Module Walkthrough

### Extension Side
- **`background.js`**: The service worker. Wires together alarm events, message events, and the tab lifecycle. Think of it as the extension's main loop — but event-driven, not a literal loop.
- **`tabLifecycleManager.js`**: Keeps 4 background tabs alive for Q1–Q4. Persists tab IDs to `chrome.storage.local` because the service worker can be killed and restarted at any time.
- **`alarmScheduler.js`**: Registers `scrapeCycle` at `monitoringIntervalMinutes` (default 5, clamp 1–60). `runScrapeCycle()` walks Q1→Q4 sequentially: register pending scrape → reload → wait 20s. Timeout POSTs `NAVIGATION_TIMEOUT`. `stopMonitoringAlarm()` / `handleContestEnded()` is the Phase 11 ENDED hook — no ENDED detection in this module. Pending map lives here; `background.js` calls `resolvePendingScrape`.
- **`backendClient.js`**: Thin HTTP client. All calls to the backend go through here. If the backend is unreachable, sets `backendUnreachable: true` in storage and drops the request — no queuing, no aggressive retry.
- **`contestPageScript.js`**: Runs on the contest homepage. Finds the 4 problem links (href-first, click-and-capture fallback). Never hardcodes URLs.
- **`problemPageScript.js`**: Runs on each problem page. Uses `MutationObserver` to wait for "Users Accepted", does a double-read stability check, detects login walls, and sends the raw string to the background.

### Backend Side
- **`AcceptanceStatsParser.java`**: Converts `"28,903 / 31.1K"` to two `BigDecimal` values. Uses `BigDecimal` multiplication for K/M/B — never `double`.
- **`AcceptanceCalculationService.java`**: `acceptedUsers × 100 / totalUsers`, scale 10, HALF_UP. Zero `totalUsers` throws — never store 0%.
- **`ContestStateService.java`**: The heart of the backend. A `synchronized` method wraps deep-copy → parse → calculate → `RankingService.computeRanking` → `ComparisonService.detectOvertakes` → `AtomicReference.set()`. This is the only place a new `ContestStats` snapshot is published.
- **`RankingService.java`**: Ranks Q1–Q4 by `usersAcceptedPercentage` descending (ADR-022). Ties by question number. Null percentages last. No I/O; called only from the critical section.
- **`ComparisonService.java`**: Pairwise percentages (ADR-023). Emits `RankingChange` only on Qx <= Qy → Qx > Qy (EQ counts as <=). Dedup via `pairwiseRelationships` on the snapshot. Null % keeps the prior relation so one PARSE_ERROR cannot wipe others.
- **`ContestLifecycleService.java`**: Phase 11 — watches for 3 consecutive unchanged cycles across all 4 questions → marks contest ENDED. The extension already has `handleContestEnded()` (Phase 7) waiting for that signal.

## Full-Cycle Trace: One Scraped Value, Both Processes

Follow a single value from cycle start to snapshot:

1. **`alarmScheduler.runScrapeCycle()`** (service worker)
   - Guard: `cycleInProgress` already true → return
   - `setCycleInProgress(true)`
   - For Q1: `registerPendingScrape(tabId, "Q1")` then `reloadTab(tabId)` then `waitForScrapeResult(tabId, 20000)`

2. **`problemPageScript.js`** (Q1 problem page, content script context)
   - `MutationObserver` fires: "Users Accepted" element appears
   - Two reads 300ms apart: both say `"28,903 / 31.1K"` — stable
   - Sends: `{ type: "SCRAPE_RESULT", rawUsersAccepted: "28,903 / 31.1K", scrapingStatus: "SUCCESS", selectorStrategyUsed: "text-anchored", url }`
   - No `questionNumber` in the payload — background maps tab id / URL → Qn

3. **`background.js` `handleScrapeResult`**
   - Invert persisted `tabIds` (`tabId → Q1`); fallback: match `message.url` to `discoveredQuestions[].problemUrl`
   - Unmapped → `{ ok: false, error: "UNKNOWN_TAB" }`
   - `backendClient.postIngest("Q1", payload)` — slot normalized to `Qn`
   - `resolvePendingScrape(tabId)` so the cycle waiter advances
   - Single-question failure is logged, not thrown

4. **`backendClient.js`**
   - `fetch("http://localhost:8080/api/contest/ingest/Q1", { method: "POST", body: JSON.stringify(payload) })`
   - Unreachable → `null` (ADR-009). Handler still resolves the pending scrape.

5. **`ContestIngestController.java`** (Spring HTTP thread)
   - Validates slot Q1–Q4 and payload
   - `contestStateService.applyIngest("Q1", ingestRequest)`

6. **`ContestStateService.java`** (same thread, `synchronized applyIngest`)
   - **Critical section begins** — no I/O
   - `ContestStats previous = currentStats.get()`; throw if uninitialized
   - Deep-copy **all four** `QuestionStats` (new objects — ADR-014)
   - Target copy: timestamp, status, selector, errorMessage
   - SUCCESS: `AcceptanceStatsParser.parse("28,903 / 31.1K")`
     - `"28,903"` → `28903`; `"31.1K"` → `31100`
     - `AcceptanceCalculationService.calculatePercentage(28903, 31100)` → `92.9356913183`
     - `ParseException` or zero-total `IllegalArgumentException` → `PARSE_ERROR`, metrics nulled
   - Non-SUCCESS (incl. `NAVIGATION_TIMEOUT`): clear metric fields, keep extension status
   - `RankingService.computeRanking(updatedQuestions)` — descending %, null last (ADR-022)
   - `ComparisonService.detectOvertakes(previous, current, pairwise, recentChanges)` — Section 18 / ADR-023
   - `currentStats.set(newSnapshot)`
   - **Critical section ends**

7. **`ContestIngestController`** returns 200 + `QuestionStats` (`acceptedUsers`, `totalUsers`, `usersAcceptedPercentage`)

8. Cycle continues Q2→Q4. `finally`: `setCycleInProgress(false)` always.

9. **`sidepanel.js`** (Phase 10) will `GET /api/contest/status` and read the snapshot lock-free.

## Debugging Index

| Symptom | First Check | Second Check | Third Check |
|---|---|---|---|
| Side panel shows "Backend not running" | Is `mvn spring-boot:run` actually running? | Does `curl localhost:8080/api/contest/health` return 200? | Is the port 8080? Check `application.properties` and `backendClient.js` |
| Requests reach the backend but are rejected | CORS: is the extension ID in `CorsConfig.java` `EXTENSION_ORIGIN` correct? | Is the `"key"` field in `manifest.json` set? | Did you reload the extension after changing the key? |
| Scraping returns `SELECTOR_NOT_FOUND` | Has LeetCode changed their DOM? | Check `problemPageScript.js` selector fallback chain | Try the aria-label fallback manually in DevTools |
| `LOGIN_WALL` status | User is not logged into LeetCode in Chrome | Check if LeetCode session cookie is present | Log in to LeetCode and reload the problem tab |
| Cycling never starts | Is `cycleInProgress` stuck at `true` in storage? | SW start should `recoverOrphanedCycleGuard()` — check that ran; otherwise clear storage and reload | Check `alarmScheduler.js` / `ensureMonitoringAlarm` |
| Interval change does nothing | Did Save persist `monitoringIntervalMinutes`? | SW log for `MONITORING_INTERVAL_SAVED` | Alarm only re-registers after Q1–Q4 tabs exist |
| Alarm keeps firing after contest ends | Phase 11 has not called `handleContestEnded` yet | ENDED is not computed until `ContestLifecycleService` | Do not invent detection in the extension |
| Alarm returns after ENDED + reload | Is `monitoringStopped` still true? | `ensureMonitoringAlarm` must no-op when stopped | Check `stopMonitoringAlarm` wrote storage |
| Only one question updates under load | Four POSTs must all finish — check `ContestStateServiceConcurrencyTest` | Was `applyIngest` mutating snapshot objects in place? (torn read) | Confirm method is `synchronized` and copies `QuestionStats` |
| Percentage set but counts null | Deep-copy missing — reader held a mutating object | Check `copyQuestionStats` is used for all four slots | Run `concurrentIngest_withConcurrentReads_snapshotAlwaysConsistent` |
| Slot stays empty after a hung tab | Timeout must POST `NAVIGATION_TIMEOUT`, not skip | Check `waitForScrapeResult` 20s path | Backend `scrapingStatus` should be `NAVIGATION_TIMEOUT` |
| `0%` stored for a question | Zero `totalUsers` must be `PARSE_ERROR` | `AcceptanceCalculationService` should have thrown | `applyIngest` catch must null metrics |
| Duplicate overtake events | `ComparisonService` bug — check pairwise relationship storage in `ContestStats` | Run `ComparisonServiceTest` | Check that previous snapshot is from `AtomicReference.get()` inside the critical section |
| Backend `500` on ingest | Ingest called before `POST /api/contest/config`? | Check backend logs for `NullPointerException` in `ContestStateService` | Ensure discovery phase completed before monitoring phase |

## Section 7A-1 Gotchas (Extension)
1. **Service worker restarts**: All state must be in `chrome.storage.local`. Never rely on module-level variables surviving.
2. **Tab closed by user**: `tabLifecycleManager.recoverMissingTabs()` detects via `chrome.tabs.get()` failure and re-opens.
3. **Overlapping alarm ticks**: `cycleInProgress` persisted to storage prevents a second cycle from starting while one is running.
4. **Event-driven only**: `reloadTab()` waits on `chrome.tabs.onUpdated` — never `setTimeout`.

## Section 7A-2 Gotchas (Backend)
1. **Compound state updates**: Ingest is a multi-step operation (parse → calculate → rank → compare). Two concurrent ingests CANNOT interleave mid-operation. That's why the critical section wraps the whole thing.
2. **AtomicReference is not enough alone**: `AtomicReference` protects single-operation swaps. It doesn't protect a multi-step computation from being observed in a partial state by a concurrent writer.
3. **Lock-free reads**: Once the snapshot is published via `atomicReference.set()`, all GET requests read it without any lock. This is intentional — reads are cheap.
4. **Don't hold the lock during I/O**: The critical section must not call any external service. Parsing is fast (in-memory string ops); that's fine inside.
