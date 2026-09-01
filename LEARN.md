# LEARN — LeetCode Contest Monitor v5

## How It Works (One Sentence)
The Chrome extension scrapes raw "Users Accepted" strings from LeetCode and POSTs them to a local Spring Boot server that parses, calculates, ranks, detects overtakes, and serves the result back to the extension's side panel.

## Module Walkthrough

### Extension Side
- **`background.js`**: The service worker. Wires together alarm events, message events, and the tab lifecycle. Think of it as the extension's main loop — but event-driven, not a literal loop.
- **`tabLifecycleManager.js`**: Keeps 4 background tabs alive for Q1–Q4. Persists tab IDs to `chrome.storage.local` because the service worker can be killed and restarted at any time.
- **`alarmScheduler.js`**: Uses `chrome.alarms` to fire every 5 minutes. On each tick, checks `cycleInProgress` (also persisted to storage) to prevent overlapping cycles.
- **`backendClient.js`**: Thin HTTP client. All calls to the backend go through here. If the backend is unreachable, sets `backendUnreachable: true` in storage and drops the request — no queuing, no aggressive retry.
- **`contestPageScript.js`**: Runs on the contest homepage. Finds the 4 problem links (href-first, click-and-capture fallback). Never hardcodes URLs.
- **`problemPageScript.js`**: Runs on each problem page. Uses `MutationObserver` to wait for "Users Accepted", does a double-read stability check, detects login walls, and sends the raw string to the background.

### Backend Side
- **`AcceptanceStatsParser.java`**: Converts `"28,903 / 31.1K"` to two `BigDecimal` values. Uses `BigDecimal` multiplication for K/M/B — never `double`.
- **`AcceptanceCalculationService.java`**: Divides acceptedUsers by totalUsers, multiplies by 100. All `BigDecimal`.
- **`ContestStateService.java`**: The heart of the backend. A `synchronized` method wraps parse → calculate → rank → compare → `AtomicReference.set()`. This is the only place a new `ContestStats` snapshot is published.
- **`ComparisonService.java`**: Compares all pairs (Qi, Qj). Only emits a `RankingChange` when a relationship flips (e.g., Q3 was below Q1, now above). Handles ties.
- **`ContestLifecycleService.java`**: Watches for 3 consecutive unchanged cycles across all 4 questions → marks contest ENDED.

## Full-Cycle Trace: One Scraped Value, Both Processes

Follow a single value from DOM to dashboard:

1. **`problemPageScript.js`** (Q1 problem page, content script context)
   - `MutationObserver` fires: "Users Accepted" element appears
   - Two reads 300ms apart: both say `"28,903 / 31.1K"` — stable
   - Sends: `{ rawUsersAccepted: "28,903 / 31.1K", scrapingStatus: "SUCCESS", selectorStrategyUsed: "text-anchored" }`

2. **`background.js`** (service worker context)
   - Receives message via `chrome.runtime.onMessage`
   - Calls `backendClient.postIngest("Q1", payload)`

3. **`backendClient.js`**
   - `fetch("http://localhost:8080/api/contest/ingest/Q1", { method: "POST", body: JSON.stringify(payload) })`
   - Awaits response

4. **`ContestIngestController.java`** (Spring HTTP thread)
   - Receives POST, validates payload
   - Calls `contestStateService.applyIngest("Q1", ingestRequest)`

5. **`ContestStateService.java`** (same Spring thread, enters synchronized block)
   - **Critical section begins**
   - Calls `AcceptanceStatsParser.parse("28,903 / 31.1K")`
     - `"28,903"` → strip comma → `new BigDecimal("28903")`
     - `"31.1K"` → strip K → `new BigDecimal("31.1").multiply(new BigDecimal("1000"))` = `31100`
   - Calls `AcceptanceCalculationService.calculate(28903, 31100)`
     - `28903 / 31100 * 100` = `92.94...%` (BigDecimal)
   - Updates Q1 in the working copy of `ContestStats`
   - Recomputes ranking across all 4 questions (sort by percentage descending)
   - Calls `ComparisonService.detectChanges(previousSnapshot, newSnapshot)`
     - If Q1's percentage just crossed Q2's → emits `RankingChange("Q1 overtook Q2")`
   - `atomicReference.set(newContestStats)` — new snapshot published atomically
   - **Critical section ends**
   - Returns updated `QuestionStats` for Q1

6. **`ContestIngestController`** returns 200 + `QuestionStats` JSON to the extension

7. **`sidepanel.js`** (5 seconds later)
   - `fetch("http://localhost:8080/api/contest/status")`
   - `ContestStatusController` calls `atomicReference.get()` (lock-free)
   - Returns full `ContestStats` JSON
   - Side panel renders: ranking, Q1–Q4 percentages, overtake banner

## Debugging Index

| Symptom | First Check | Second Check | Third Check |
|---|---|---|---|
| Side panel shows "Backend not running" | Is `mvn spring-boot:run` actually running? | Does `curl localhost:8080/api/contest/health` return 200? | Is the port 8080? Check `application.properties` and `backendClient.js` |
| Requests reach the backend but are rejected | CORS: is the extension ID in `CorsConfig.java` `EXTENSION_ORIGIN` correct? | Is the `"key"` field in `manifest.json` set? | Did you reload the extension after changing the key? |
| Scraping returns `SELECTOR_NOT_FOUND` | Has LeetCode changed their DOM? | Check `problemPageScript.js` selector fallback chain | Try the aria-label fallback manually in DevTools |
| `LOGIN_WALL` status | User is not logged into LeetCode in Chrome | Check if LeetCode session cookie is present | Log in to LeetCode and reload the problem tab |
| Cycling never starts | Is `cycleInProgress` stuck at `true` in storage? | Service worker restarted mid-cycle — clear storage and reload | Check `alarmScheduler.js` alarm registration |
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
