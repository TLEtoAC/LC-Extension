# Decisions

## ADR-001: Extension + Local Spring Boot Backend Split
- Context: Need DOM access (extension) + reliable numeric parsing (Java)
- Decision: Split into two processes communicating over HTTP on localhost
- Rationale: Chrome extensions can access the LeetCode DOM; Spring Boot gives us BigDecimal, real threading, and a proper REST API. Playwright is NOT used at runtime.
- Consequences: User must start the backend before the extension does anything useful. Backend-unreachable is a new failure mode.

## ADR-002: Raw String Over the Wire (Extension sends raw string, backend parses)
- Context: "28,903 / 31.1K" must be parsed with BigDecimal
- Decision: Content script sends raw DOM string to background, which POSTs it to the backend. Backend parses.
- Rationale: BigDecimal lives in Java. Parsing extension-side with JS floats risks numeric drift on K/M/B suffixes.
- Consequences: Backend is the single source of truth for parsed values. Extension is purely DOM extraction.

## ADR-003: ContestStateService Critical Section Design
- Context: Up to 4 concurrent ingest POSTs can arrive simultaneously from the extension
- Decision: `synchronized` method (or `ReentrantLock`) wraps the full `apply ingest → recompute ranking → compute overtakes → AtomicReference.set()` sequence
- Rationale: This is a multi-step compound state update. AtomicReference alone does not protect against two calls interleaving mid-computation.
- Consequences: Ingests are serialized. Reads (GET /api/contest/status) are lock-free via AtomicReference.get().

## ADR-004: BigDecimal for K/M/B Suffix Multiplication
- Context: Display strings like "31.1K" are multiplied by 1,000
- Decision: Use `new BigDecimal("31.1").multiply(new BigDecimal("1000"))` — never `double`
- Rationale: double arithmetic produces drift (31.1 * 1000 = 31099.999... in IEEE 754). The parsed value is already approximate (rounded display); don't add a second layer of imprecision.
- Consequences: Parser is slightly more verbose but drift-free.

## ADR-005: CORS Scoped to Pinned Extension ID
- Context: Backend is a local HTTP server; CORS must not be open to all origins
- Decision: `CorsConfig.java` allows only `chrome-extension://<pinned-id>`. Extension manifest uses a `"key"` field to stabilize the ID.
- Rationale: `*` would allow any local web page to call the backend. The extension ID changes on every unpacked reload without a pinned key.
- Consequences: User must run a one-time `openssl` command to generate a stable key. Documented in DOCUMENTATION.md.
- Note: `CorsConfig.java` currently uses the constant `EXTENSION_ORIGIN = "chrome-extension://PLACEHOLDER_EXTENSION_ID"`. The user must replace `PLACEHOLDER_EXTENSION_ID` with their actual 32-character extension ID after loading the unpacked extension.

## ADR-006: Signal-Based Contest-End Detection (V1)
- Context: Need to detect contest end and stop the alarm cycle
- Decision: If all 4 questions show unchanged percentages across 3 consecutive ingest cycles, mark lifecycle ENDED
- Rationale: Contest end-time is not forwarded from the extension in V1 (optional enhancement). Signal-based is sufficient and simpler.
- Consequences: There is a lag of up to 3 cycles (15 minutes at default interval) before ENDED is declared. Edge case: a completely static contest could be falsely marked ENDED.
## ADR-008: Tab Reload is Event-Driven Only
- Context: Reliability of tab lifecycle.
- Decision: `reloadTab()` uses `chrome.tabs.onUpdated` listener, never `setTimeout`.
- Rationale: Ensures page load completion before scraping.

## ADR-009: Drop-on-Unreachable for postIngest
- Context: Backend might be unavailable.
- Decision: When `backendUnreachable=true`, `postIngest()` returns `null` immediately — no queue, no retry.
- Rationale: Queuing stale data risks state corruption. Next alarm cycle (5 min) will provide fresh data.

## ADR-010: cycleInProgress Stored in chrome.storage.local
- Context: Service worker can be killed and restarted.
- Decision: Persist `cycleInProgress` in `chrome.storage.local`.
- Rationale: Survives service worker restarts, unlike in-memory variables.

## ADR-011: Manifest "key" field is a Placeholder
- Context: Stable extension ID needed for CORS.
- Decision: Use placeholder key in `manifest.json` with generation instructions in comment.
- Rationale: Security; allows stable ID across reloads.

## ADR-012: ES Module Service Worker
- Context: Project structure and modern JS.
- Decision: Use `"type": "module"` in `manifest.json`.
- Rationale: Allows static import of `tabLifecycleManager` and `backendClient`.

## ADR-013: 3-Tier Selector Fallback Chain & 300ms Double-Read Stability Check
- Context: Problem pages render asynchronously with React hydration; acceptance statistics markup can vary across UI updates.
- Decision: Use a 3-tier fallback chain (1. Text-anchored, 2. Attribute-based, 3. Aria-label) combined with a MutationObserver and a 300ms double-read stability check before dispatching `SCRAPE_RESULT`.
- Rationale: Prevents brittle selector breakage when LeetCode modifies utility class names while eliminating transient DOM hydration race conditions. Login walls and page errors are detected early to emit `LOGIN_WALL` or `PAGE_UNAVAILABLE` immediately.
- Consequences: Content script dispatches stable, unparsed strings to background without float math drift.

## ADR-014: Deep-Copy QuestionStats Before Ingest Mutation
- Context: `QuestionStats` is a mutable POJO. `ContestStats` stores those same object references in the published snapshot. Mutating in place meant a lock-free reader (`getCurrentStats()`) could observe a torn triple (e.g. percentage set, `acceptedUsers` still null).
- Decision: `applyIngest` deep-copies every `QuestionStats` into new objects, mutates only the copies, then `AtomicReference.set(newSnapshot)`.
- Rationale: Section 7A-2 / ADR-003 already serializes writers. Deep-copy is what makes published snapshots actually immutable from the reader's point of view.
- Consequences: Slightly more allocation per ingest. Readers never share mutable objects with the next writer.

## ADR-015: Alarm Scheduler Landed in Phase 6 (Master Plan Phase 7)
- Context: The master plan listed `alarmScheduler.js` as Phase 7. Phase 6 needs all 4 tabs in a scrape cycle and an immediate first cycle after discovery.
- Decision: Implement `registerMonitoringAlarm` + `runScrapeCycle` in Phase 6. Phase 7 remains for later alarm polish (interval config, ENDED stop) — not for the core loop.
- Rationale: Architect approved immediate first cycle **and** 5-minute alarm registration after the 4 tabs are persisted. Splitting that across phases would leave monitoring inert after discovery.
- Consequences: Phase 6 owns the scrape loop. `chrome.alarms` `delayInMinutes: 0` plus an explicit `runScrapeCycle()` call; `cycleInProgress` prevents a double start.

## ADR-016: postIngest Always Uses Qn in the URL
- Context: `backendClient.postIngest` JSDoc said 1–4; the backend path is `/api/contest/ingest/Q1`–`Q4`. Passing `1` produced `/ingest/1` (400). Passing `"Q1"` through a `Q${questionNumber}` interpolator would produce `QQ1`.
- Decision: `normalizeQuestionSlot` accepts `1`, `"1"`, `"q1"`, `"Q1"` and always builds `/api/contest/ingest/Qn`.
- Rationale: Callers (alarm timeout, SCRAPE_RESULT handler) should not need to remember the path format.
- Consequences: Invalid slots still go on the wire and the backend returns 400.

## ADR-017: Immediate First Cycle After Discovery
- Context: After Q1–Q4 tabs open, waiting up to 5 minutes for the first scrape leaves the dashboard empty.
- Decision (Architect): After discovery persists tab IDs, call `runScrapeCycle()` once immediately **and** `registerMonitoringAlarm()`.
- Rationale: Tabs already auto-scrape on first load, but the cycle reload is the guaranteed path that registers pending-scrape waits and timeout ingest.
- Consequences: Discovery `sendResponse` fires before the cycle so the message channel does not sit open for up to 80s.

## ADR-018: 20s Scrape Timeout Posts NAVIGATION_TIMEOUT
- Context: A hung problem tab must not stall the sequential Q1→Q4 loop, and must not be dropped silently (empty slot looks like "never scraped").
- Decision (Architect): `waitForScrapeResult` times out at 20s and POSTs `{ scrapingStatus: "NAVIGATION_TIMEOUT", rawUsersAccepted: null, errorMessage }`. Only `handleScrapeResult` POSTs real scrapes.
- Rationale: Backend already has `NAVIGATION_TIMEOUT`. A synthetic ingest keeps lifecycle MONITORING and lastUpdated moving.
- Consequences: The waiter uses `setTimeout` for a 20s deadline (chrome.alarms cannot do sub-minute delays). The in-flight reload/message/fetch work keeps the SW alive for that window. Duplicate `SCRAPE_RESULT` after resolve is ignored.

## ADR-019: Zero totalUsers Is PARSE_ERROR
- Context: `"10 / 0"` parses, but `accepted / 0 × 100` is undefined. Storing `0%` would rank the question as hardest and could fire false overtakes later.
- Decision (Architect): `AcceptanceCalculationService` throws `IllegalArgumentException` when `totalUsers == 0`. `applyIngest` catches it (with `ParseException`) and marks `PARSE_ERROR`, nulling metric fields.
- Rationale: Same failure class as a malformed string — we do not have a usable percentage.
- Consequences: Ingest HTTP status stays 200; the slot status is `PARSE_ERROR`.

## ADR-020: Configurable Scrape Interval, Clamped 1–60 Minutes
- Context: Section 10 says the interval is configurable from the options page with a 5-minute default. The master plan does not name a min/max. `chrome.alarms` will not schedule `periodInMinutes` below 1.
- Decision: Persist `monitoringIntervalMinutes` in `chrome.storage.local`. Default 5. Clamp to 1–60 (integer minutes). Changing the interval clears `scrapeCycle` and creates a new alarm with `delayInMinutes = period` so the next tick follows the new cadence — it does **not** start an extra scrape cycle.
- Rationale: 1 minute is Chrome's floor; 60 minutes is a personal-use ceiling so a typo cannot park the monitor for hours. Discovery still uses delay 0 + an explicit `runScrapeCycle()` (ADR-017).
- Consequences: Interval-only saves must not re-fire `CONTEST_URL_SAVED` (that would re-run discovery). `registerMonitoringAlarm()` with no args reads storage.

## ADR-021: ENDED Stops the Alarm via a Phase 11 Hook (No Fake Detection)
- Context: Section 24B / ADR-006: stop `chrome.alarms` once lifecycle is ENDED. `ContestLifecycleService` (3 unchanged cycles) is Phase 11. Health/status do not yet return `lifecycleState: ENDED` (Phase 10/11).
- Decision: Export `stopMonitoringAlarm()` and `handleContestEnded()`. Wire a `CONTEST_ENDED` runtime message. Persist `monitoringStopped: true` so a service-worker restart does not recreate `scrapeCycle`. Do **not** invent ENDED detection in Phase 7.
- Rationale: Inventing a 3-cycle detector in the extension would duplicate Phase 11 and could false-stop on a static mid-contest snapshot.
- Consequences: Until Phase 11 calls `handleContestEnded()` (or sends `CONTEST_ENDED`), the alarm keeps running after a real contest ends. Phase 11 must invoke the hook when health/status reports ENDED.

## ADR-022: Ranking Is Descending Acceptance Percentage
- Context: The Architect prompt suggested ascending % (hardest = rank 1). The master plan is silent on sort order. Phase 3 `ContestStats` and PROGRESS already documented descending.
- Decision: `ranking[]` is question numbers sorted by `usersAcceptedPercentage` **descending** (highest acceptance first). Ties break by question number ascending (`Q1` before `Q2`). Null percentages (PARSE_ERROR, NAVIGATION_TIMEOUT, not yet scraped) sort last and never throw.
- Rationale: Matches the existing snapshot contract and the overtake language ("Q4 overtook Q3" = Q4's % moved above Q3's). `BigDecimal.compareTo` only.
- Consequences: The dashboard "rank 1" is the easiest (highest %) question, not the hardest.

## ADR-023: Pairwise Overtake Is Percentage Transition, Not Rank Hop
- Context: Section 18 says only report Qx <= Qy → Qx > Qy, ties included. Ranking order (ADR-022) is a display sort.
- Decision: `ComparisonService` compares `usersAcceptedPercentage` pairwise. Canonical key `QavsQb` (a < b) stores how Qa compares to Qb (`GT`/`LT`/`EQ`). An event fires only when a stored relation flips across that inequality (EQ counts as <=). First observation stores the relation and emits nothing. Null percentage skips that pair and keeps the previous relation.
- Rationale: Dedup lives on the snapshot's `pairwiseRelationships`. PARSE_ERROR on one slot must not wipe or re-emit other pairs.
- Consequences: Becoming tied (GT/LT → EQ) is not an overtake. History is newest-first, capped at 20.

## ADR-024: Extension host_permissions Include Local Backend
- Context: Side panel and popup `fetch` localhost:8080. Manifest previously allowed only `https://leetcode.com/*`.
- Decision: Add `http://127.0.0.1:8080/*` and `http://localhost:8080/*` only. Do not add `*` or LAN hosts.
- Rationale: MV3 needs host permission for extension-page fetch. CORS stays pinned to the extension origin (ADR-005).
- Consequences: User still must start the backend; unreachable remains a first-class UI state (ADR-009).

## ADR-025: Uninitialized Status Is 200 Empty Envelope
- Context: Side panel must not treat "no contest yet" as a backend error.
- Decision: `GET /api/contest/status` returns 200 with `initialized: false` and empty collections when `getCurrentStats()` is null. Configured snapshots add `initialized: true` plus ContestStats fields.
- Rationale: A 404/500 would look like backend-unreachable (ADR-009).
- Consequences: UI keys off `initialized` and `backendUnreachable`, not HTTP status alone.

## ADR-026: ENDED Stops via Phase 7 Hook After Status/Health Observe
- Context: ADR-006 detects ENDED on the backend. ADR-021 already exported `handleContestEnded` / `CONTEST_ENDED`. Must not invent a second alarm-clear path.
- Decision: After each scrape cycle, `observeEndedAndStop()` reads `GET /api/contest/status`. Side panel and popup also send `{ type: "CONTEST_ENDED" }` when they see `lifecycleState === "ENDED"`. SW startup uses `GET /health` (`observeEndedFromHealth`). All three call `handleContestEnded()` → `stopMonitoringAlarm()`.
- Rationale: The backend is the detector; the extension only observes. One hook keeps `monitoringStopped` consistent across SW restarts.
- Consequences: Up to one extra cycle can run after ENDED until the post-cycle status read (or the 5s UI poll). No ingest-response field was added — status/health already carry `lifecycleState`.
