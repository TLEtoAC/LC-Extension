# Master Prompt — LeetCode Contest Acceptance Monitor (v5 — Hybrid: Extension + Local Spring Boot Backend)

You are the **Architect agent** coordinating a council of specialized agents (Section 31) to build a system that monitors the **Users Accepted percentage** of all 4 questions of a LeetCode contest in near real time, split across:

* a **thin Chrome extension (Manifest V3, JavaScript)** — the only thing that can touch the LeetCode page DOM, so it owns discovery and scraping
* a **local Spring Boot backend (Java)**, running as its own process on the user's machine — owns parsing, calculation, ranking, overtake detection, state, and the REST API

> **v5 changelog (this document):** Reintroduces Spring Boot/Java for everything that *isn't* DOM access, per your request. Playwright is **not** part of this version's runtime — the extension's own background tabs (v4, Section 9) still do the scraping; the backend never touches the browser at all, which actually simplifies the backend relative to v2/v3 (no Playwright dependency, no Playwright thread-safety concerns in Java anymore). What Java gets back: `BigDecimal` parsing, a real REST API, in-memory Java state, and Spring's request/response model. What the extension keeps: content scripts, tab lifecycle management, `chrome.alarms`. New surface area from the split itself: the extension must call the backend over HTTP, the backend must handle CORS from an extension origin, and both sides need to handle **the backend not being started** gracefully — this is the main new failure mode this architecture introduces, and it doesn't exist in a pure-JS or pure-Playwright build.

---

# 1. CORE GOAL

During a LeetCode contest, the contest homepage contains a **Problem List** with four contest questions. On each question page, LeetCode displays:

```
Users Accepted 28,903 / 31.1K
Total Accepted 30,674 / 49.7K
```

Monitor **Users Accepted**, never Total Accepted. Calculate `percentage = acceptedUsers / totalUsers × 100`. Monitor all four questions and detect when one question's percentage overtakes another (e.g. `Q4 overtook Q3`).

---

# 2. ONLY INPUT REQUIRED

The user provides only the contest homepage URL, once, via the extension's options page. Discovery of Q1–Q4 is automatic — never require the four problem URLs manually.

---

# 3. DISCOVERING THE FOUR QUESTIONS

Unchanged: the contest homepage's Problem List has four entries; discovered order determines question number. Do NOT hard-code names, URLs, or a fixed URL pattern.

---

# 4. HOW TO DISCOVER THE URL (extension-side, unchanged from v4)

1. Extension background opens/reuses a tab on the contest homepage.
2. `contestPageScript.js` (content script) queries the DOM for the four Problem List entries.
3. **href-first**: try `element.getAttribute("href")` per entry, resolved against base URL — no navigation needed.
4. **Click-and-capture fallback** if no href: click the entry, background listens for `chrome.tabs.onUpdated` for URL change, reads `tab.url`, then navigates back via a **fresh** `chrome.tabs.update({url: contestUrl})` (not `history.back()`, to avoid a re-render corrupting the mapping).
5. Once all four URLs (and problem names) are discovered, the extension background **registers them with the backend** — `POST /api/contest/config` (Section 22.1) — rather than discovery data staying extension-local. The backend needs this metadata to build its `QuestionStats` records, even though it never did the discovering itself.

---

# 5. DOM SCRAPING, NOT GRAPHQL (still V1 scope, extension-side)

Still DOM scraping via content scripts, not GraphQL, per the earlier decision. This logic lives entirely in the extension — the backend has no browser access at all in this architecture, so it isn't a candidate to ever do scraping itself, only to receive already-scraped raw values.

---

# 6. TECHNOLOGY

**Extension (thin client):**
* Manifest V3, JavaScript, content scripts + background service worker
* `chrome.alarms` for the monitoring cadence
* `chrome.storage.local` for extension-local state only (tab IDs, cycle-in-progress guard, last-known backend-reachability) — **not** contest stats; those live in the backend now
* `chrome.sidePanel` for the dashboard, polling/fetching the backend directly
* No Playwright, no headless browser — the extension's own background tabs are the browser

**Backend (local Spring Boot process):**
* Java, Spring Boot, Maven
* No Playwright dependency — the backend never opens a browser or a page
* In-memory state (`AtomicReference`/synchronized snapshot, Section 16)
* REST controllers (Section 22) consumed by the extension and the side panel
* Runs locally, e.g. `mvn spring-boot:run`, bound to `localhost` only — **never** `0.0.0.0** (Section 24C)

**Between them:** plain HTTP over `localhost`, JSON payloads, CORS scoped to the extension's origin (Section 24C).

**Testing:** Jest for the extension (parsing-adjacent logic that remains extension-side, tab lifecycle); JUnit for the backend (parser, ranking, overtake detection, concurrency); optionally Playwright as a **test-only** tool to drive Chrome with the unpacked extension loaded for E2E tests against a running backend instance.

---

# 7. ARCHITECTURE

```
extension/                              (JavaScript — DOM access only)
  manifest.json
  background/
    background.js
    alarmScheduler.js
    tabLifecycleManager.js              — owns 4 background tab IDs, reload
                                            cycle, recovery (Section 7A-1)
    backendClient.js                    — ⭐ NEW: fetch wrapper for all
                                            calls to the local Spring Boot
                                            API; handles backend-unreachable
                                            gracefully (Section 24C)
  content-scripts/
    contestPageScript.js                — discovery
    problemPageScript.js                — scraping raw X/Y strings +
                                            scrapingStatus, sent to background
  ui/
    sidepanel.html / sidepanel.js / sidepanel.css
    popup.html / popup.js
    options.html / options.js
  tests/
    tabLifecycle.test.js
    backendClient.test.js
    e2e/ (optional Playwright, test-only)

backend/                                (Java — everything else)
  src/main/java/...
    controller/
      ContestConfigController.java      — ⭐ NEW: POST /api/contest/config
      ContestIngestController.java      — ⭐ NEW: POST /api/contest/ingest/{q}
      ContestStatusController.java      — GET /api/contest/status
      ContestHealthController.java      — GET /api/contest/health
    service/
      ContestStateService.java          — owns the synchronized
                                            ingest→recompute→publish
                                            transaction (Section 7A-2)
      AcceptanceCalculationService.java
      ComparisonService.java
      ContestLifecycleService.java
    parser/
      AcceptanceStatsParser.java        — BigDecimal, back to Java
    model/
      Question.java
      QuestionStats.java
      ContestStats.java
      RankingChange.java
      ScrapingStatus.java
    config/
      CorsConfig.java                   — ⭐ NEW: restricts allowed origin
                                            to the extension's chrome-extension://<id>
```

Documentation (repo root, spans both halves — Section 32):

```
CHANGELOG.md
DECISIONS.md
FLOW.md
DOCUMENTATION.md
LEARN.md
```

---

# 7A. CONCURRENCY MODEL — TWO HALVES ⭐

This architecture has two genuinely different concurrency problems, one per side. Do not solve them the same way.

## 7A-1. Extension side (JS, event-driven — same as v4)

The service worker is non-persistent; sleep-based waiting doesn't keep it alive; background tabs can be closed by the user; overlapping `chrome.alarms` ticks need a guard. Same four gotchas as v4 Section 7A, unchanged — they're about the browser/extension runtime, not about who does the computation, so the hybrid split doesn't remove them. `tabLifecycleManager.js` still persists tab IDs and a `cycleInProgress` flag to `chrome.storage.local`, still waits on `chrome.tabs.onUpdated` / `chrome.runtime` messaging rather than `setTimeout`.

## 7A-2. Backend side (Java, real threads — reintroduced) ⭐ NEW

Spring handles each incoming HTTP request on its own thread from a pool. The extension will call `POST /api/contest/ingest/{q}` roughly once per question per cycle (up to four close-together calls), while the side panel concurrently calls `GET /api/contest/status`. This is a **real** multi-threading problem, unlike the old Playwright-object thread-safety issue — here it's about **compound state updates**, not single-object access:

* Applying one ingested reading, recomputing the ranking, and running the overtake comparison against *all four* questions is a multi-step read-modify-write — not a single atomic operation. An `AtomicReference` swap alone is not enough if two ingest calls interleave mid-computation.
* `ContestStateService` must wrap the full "apply ingest → recompute ranking → compute overtakes → publish new snapshot" sequence in a **single critical section** (a `synchronized` method, or a `ReentrantLock` around that block) so ingests are applied one at a time, in order, each against a fully-consistent prior state.
* Once the critical section produces a new complete `ContestStats` snapshot, publish it via `AtomicReference.set()` — reads via `GET /api/contest/status` then only ever need the cheap `AtomicReference.get()`, no locking on the read path.
* Keep the critical section small (just the state transition) — do not hold the lock during parsing or HTTP I/O.

---

# 8. CONTEST DISCOVERY FLOW

```
User enters contest URL (extension options page)
    ↓
Extension background opens/reuses a tab on the contest homepage
    ↓
contestPageScript.js finds Problem List → finds four entries
    ↓
href-first extraction → click-and-capture fallback if needed
    ↓
Extension background: POST /api/contest/config { contestUrl, questions: [...] } to backend
    ↓
Backend stores question metadata, initializes ContestStats
    ↓
tabLifecycleManager opens 4 persistent background tabs (Q1–Q4)
    ↓
chrome.alarms scheduled → monitoring begins
```

---

# 9. BACKGROUND TABS (extension-side, unchanged from v4)

`tabLifecycleManager` opens four background tabs, keeps them alive across cycles, reloads rather than recreates them, persists tab IDs per Section 7A-1.

---

# 10. MONITORING INTERVAL

Default 5 minutes, configured in the extension's options page. `chrome.alarms` triggers a cycle: reload Q1 → scrape → **POST ingest to backend** → Q2 → … → Q4. Sequential in V1; note that even if reloads are parallelized later, ingest POSTs arriving close together are exactly what Section 7A-2's critical section is for.

---

# 11. QUESTION PAGE SCRAPING (extension-side, unchanged from v4)

`problemPageScript.js`: MutationObserver-based wait for "Users Accepted", double-read stability check, explicit login-wall detection (report `LOGIN_WALL` rather than attempting to parse), selector fallback chain (Section 12). Sends the **raw** matched string (e.g. `"28,903 / 31.1K"`) plus status back to the background script — **parsing itself now happens in the backend** (Section 13), not in the content script, since `BigDecimal` lives in Java. The extension's job is DOM extraction only.

---

# 12. DOM SELECTOR DESIGN — fallback chain (extension-side, unchanged from v4)

Same ordered strategy inside `problemPageScript.js`: text-anchored → attribute-based → `aria-label` → configurable escape hatch. Log which strategy succeeded in the message sent to background, which forwards it to the backend's ingest payload so it shows up in backend logs too.

---

# 13. PARSING ⭐ (back to the backend, back to BigDecimal)

Moves to `AcceptanceStatsParser.java`, receiving the raw string via the ingest endpoint. Support the same formats (`28,903 / 31.1K`, `1.2M / 4.5M`, etc.), same K/M/B suffixes. **Use `BigDecimal` for suffix multiplication, not `double`** — this was the whole reason it's worth having Java here at all; don't undermine it by parsing loosely. Same precision note as v2/v3: a value derived from a rounded display string is inherently approximate.

---

# 14. ACCEPTANCE CALCULATION

`percentage = acceptedUsers / totalUsers × 100`, via `BigDecimal`, in `AcceptanceCalculationService.java`. Round only for display.

---

# 15. QUESTION STATS

Same shape as v2/v3: `questionNumber, problemName, problemUrl, acceptedUsers, totalUsers, usersAcceptedPercentage, timestamp, scrapingStatus, errorMessage` — a real Java class again (`QuestionStats.java`).

---

# 16. FOUR-QUESTION STATE ⭐ (AtomicReference is back, with a critical section)

Maintain Q1–Q4 state, ranking, in `ContestStats`. Per Section 7A-2: the full ingest→recompute→publish sequence runs inside a critical section in `ContestStateService`; the resulting immutable snapshot is published via `AtomicReference<ContestStats>`. `GET /api/contest/status` reads the reference directly, lock-free.

---

# 17. OVERTAKING DETECTION

Unchanged: compare previous vs. current pairwise relationships across all questions, in `ComparisonService.java`, inside the same critical section as the state update (Section 7A-2) — the comparison needs a fully-consistent "before" state, not one that could be concurrently mutated mid-comparison.

---

# 18. NO DUPLICATE EVENTS

Unchanged: only report a transition from `Qx <= Qy` to `Qx > Qy`, ties included. Store last-known pairwise relationships as part of the same `ContestStats` snapshot.

---

# 19. DASHBOARD

Same layout, rendered in the extension's `sidepanel.html`, but now fetching from the local backend instead of `chrome.storage`/`chrome.runtime` messaging:

```js
fetch("http://localhost:8080/api/contest/status").then(r => r.json())...
```

---

# 20. TOP-RIGHT MONITORING WINDOW

Unchanged from v4: `chrome.sidePanel` as the primary dashboard, `popup.html` as a lightweight fallback.

---

# 21. UI UPDATE FREQUENCY

Side panel polls `GET http://localhost:8080/api/contest/status` every 5 seconds while open. Backend computes on ingest (event-driven, as fast as the extension posts), not on its own timer — the backend has no independent schedule; it's purely reactive to what the extension sends it.

---

# 22. REST API ⭐ (real HTTP again, plus two new endpoints for the split)

## 22.1 `POST /api/contest/config`
Called once after discovery. Registers contest metadata.

```json
// Request
{
  "contestUrl": "https://leetcode.com/contest/...",
  "questions": [
    { "questionNumber": "Q1", "problemName": "Nearest Available Drone", "problemUrl": "https://leetcode.com/contest/.../problems/..." },
    ...
  ]
}
```

## 22.2 `POST /api/contest/ingest/{questionNumber}` ⭐ NEW
Called once per question per monitoring cycle by the extension.

```json
// Request
{ "rawUsersAccepted": "28,903 / 31.1K", "scrapingStatus": "SUCCESS", "selectorStrategyUsed": "text-anchored" }
// or, on failure:
{ "scrapingStatus": "LOGIN_WALL" }
```

Backend parses (if `SUCCESS`), calculates, applies the state transition (Section 7A-2), and returns the updated `QuestionStats` for that question as confirmation.

## 22.3 `GET /api/contest/status`
Same payload shape as v3 — `lastUpdated`, `questions[]`, `ranking[]`, `recentChanges[]`.

## 22.4 `GET /api/contest/health`
Monitoring status, per-question status, discovery status, contest-lifecycle status, **and** a `backendReachableFromExtension` style flag isn't meaningful here (the extension calling health *proves* reachability) — but do include an explicit `lastIngestReceivedAt` per question so a stalled extension (vs. a stalled backend) is distinguishable from the health payload alone.

---

# 23. ERROR HANDLING — status taxonomy, plus the new failure mode

Same `ScrapingStatus` enum as v4 (`SUCCESS`, `LOGIN_WALL`, `SELECTOR_NOT_FOUND`, `PARSE_ERROR`, `NAVIGATION_TIMEOUT`, `PAGE_UNAVAILABLE`, `BROWSER_ERROR`, `TAB_MISSING`, `UNKNOWN_ERROR`) — still lives as a Java enum now (`ScrapingStatus.java`) since parsing/validation moved to the backend.

**New failure mode from the split:** the backend might not be running. `backendClient.js` (extension) must treat a failed/refused `fetch()` to `localhost` as its own distinct state — surfaced in the side panel as something like "Backend not running" rather than silently failing or crashing the extension. Queue or drop ingest attempts (your call, document the choice in `DECISIONS.md`) rather than retrying aggressively against a backend that isn't there.

---

# 24. AUTHENTICATION

Unchanged from v4: the extension runs in the user's already-logged-in Chrome; no separate profile management needed. The backend never sees LeetCode credentials or cookies at all in this architecture — it only ever receives already-scraped text strings, which is arguably a nice side effect of the split from a credential-exposure standpoint.

---

# 24A. REQUEST ETIQUETTE / SCOPE NOTE (unchanged)

Same as v4: 5-minute default interval, stagger reloads if ever parallelized, personal-use scope.

---

# 24B. CONTEST-END HANDLING

`ContestLifecycleService.java` (backend) detects contest end via signal-based detection (percentages unchanged across N ingested cycles) since the backend has no independent visibility into contest timing unless the extension also scrapes and forwards a contest end-time during discovery (optional enhancement — document as a `DECISIONS.md` entry either way). On detection, mark lifecycle `ENDED` in `/api/contest/health`; the extension should stop its `chrome.alarms` cycle once it observes `ENDED`, rather than continuing to POST ingests indefinitely.

---

# 24C. LOCAL BACKEND SECURITY & CORS ⭐ NEW

Because the backend is a real local HTTP server, treat it with the same care as any local dev server:

* **Bind to `localhost` only**, never `0.0.0.0` — this contest data is harmless, but there's no reason to expose a local HTTP server to the LAN by default.
* **CORS**: restrict `Access-Control-Allow-Origin` in `CorsConfig.java` to the extension's specific `chrome-extension://<extension-id>` origin, not `*`. Pin the extension's ID during development by setting the `"key"` field in `manifest.json` (generated once from your dev keypair) so the ID — and therefore the CORS allow-list — stays stable across reloads instead of changing every time you reload the unpacked extension.
* Document the exact port and the "you must start the backend before the extension will do anything useful" requirement prominently in `DOCUMENTATION.md` — this is the main new onboarding step this architecture adds versus a pure extension.

---

# 25. LOGGING

Extension: `console.log` in the background/content scripts, visible via the service worker inspector. Backend: Spring Boot logging as in v2/v3. Log which discovery method and selector strategy were used, on both sides where relevant (extension logs it locally; backend logs it again from the ingest payload, for a single combined picture when both logs are read together). Never log credentials, cookies, or tokens on either side.

---

# 26. TESTING ⭐

**Extension (Jest):** tab lifecycle persistence/recovery, `cycleInProgress` guard, `backendClient.js` handling of a refused connection.
**Backend (JUnit):** parser (`BigDecimal`, no float drift), percentage calculation, ranking, overtake detection, duplicate-event prevention, ties, the `ContestStateService` critical section under concurrent ingest calls (a genuine concurrency test — spin up multiple threads calling the ingest path simultaneously and assert the resulting snapshot is internally consistent).
**Optional E2E (Playwright, test-only):** drive Chrome with the unpacked extension loaded against a real running backend instance, to verify the full round trip.

---

# 27. DEVELOPMENT PHASES ⭐ (11 phases — split reflects the two halves)

**Phase 1 — Backend Skeleton.** Spring Boot app, `GET /api/contest/health` returning a static OK, `CorsConfig.java` scoped to a placeholder extension ID. Confirm it starts and responds on `localhost`.

**Phase 2 — Extension Scaffold + Tab Lifecycle.** `manifest.json`, background service worker skeleton, `tabLifecycleManager.js` with storage-backed persistence and the `cycleInProgress` guard. No backend calls yet.

**Phase 3 — Discovery + Config Registration.** `contestPageScript.js` (href-first + click fallback) → `POST /api/contest/config` wired end-to-end; backend stores question metadata and initializes empty `ContestStats`.

**Phase 4 — Single Question Scraping + Ingest (Q1 only).** `problemPageScript.js` wait/login-wall/selector-chain logic → raw string sent to background → `POST /api/contest/ingest/Q1` → backend parses and returns updated stats. Prove the full round trip on one question before scaling to four.

**Phase 5 — Parser.** `AcceptanceStatsParser.java`, `BigDecimal`, full format/suffix coverage, float-drift regression tests.

**Phase 6 — Four Questions + Concurrency.** Extend scraping to all four background tabs; backend's `ContestStateService` critical section (Section 7A-2) proven under near-simultaneous ingest calls.

**Phase 7 — Alarm Scheduler.** `chrome.alarms` driving the full four-question reload-and-ingest cycle on a configurable interval.

**Phase 8 — State + Ranking.** `AtomicReference<ContestStats>` snapshot publishing, ranking calculation.

**Phase 9 — Overtaking Detection.** Pairwise comparison inside the critical section, no duplicates, ties handled.

**Phase 10 — Status/Health API + Side Panel UI.** `GET /api/contest/status`, `GET /api/contest/health` (with `lastIngestReceivedAt`), side panel fetching from the backend, backend-unreachable handling in the UI.

**Phase 11 — Robustness + Lifecycle.** `TAB_MISSING` recovery, `ContestLifecycleService`, retries/backoff on both sides, full status taxonomy, JUnit + Jest suites, optional Playwright E2E harness.

---

# 28. FUTURE OPTIMIZATION

The DOM-scraping boundary (content scripts) and the parsing/business-logic boundary (backend) are already cleanly separated by the HTTP contract in Section 22 — a future GraphQL-based scraper could replace the content scripts' extraction logic without the backend's ingest contract changing at all, since the backend only ever sees a raw string plus a status, regardless of how that string was obtained.

---

# 29. IMPORTANT CONSTRAINTS ⭐

Do NOT:

* hard-code Q1–Q4 URLs, contest-specific problem names, or a fixed URL format
* assume problem entries are normal `<a>` tags (check `href` first)
* reverse-engineer GraphQL in V1
* read, store, or log cookies, tokens, or session data — on either side
* use Total Accepted as the primary metric
* crash the extension or the backend because one question fails
* send duplicate overtake notifications
* hold contest state, tab IDs, or cycle progress only in memory in the extension's background script (Section 7A-1)
* use sleep-based waiting in the background script where event-driven waiting is possible
* use `double` for K/M/B suffix conversion in the backend parser — use `BigDecimal`
* publish a `ContestStats` snapshot without going through the `ContestStateService` critical section (Section 7A-2) — no field-by-field mutation from a controller
* bind the backend to `0.0.0.0`, or set CORS `Access-Control-Allow-Origin` to `*`
* let the extension retry-hammer the backend indefinitely if it's unreachable — surface the state, back off
* keep the alarm cycle running indefinitely after the contest has ended
* let any agent write code without a corresponding comment/doc update (Sections 32–33)

The primary metric is `Users Accepted X / Y`. The primary calculation is `X / Y × 100`.

---

# 30. FINAL EXPECTED USER EXPERIENCE

The user starts the local Spring Boot backend once (`mvn spring-boot:run`), installs the extension, enters the contest URL via the options page. The extension discovers the four problems, registers them with the backend, opens four persistent background tabs, and on a `chrome.alarms`-driven 5-minute cycle: reloads each tab, scrapes the raw "Users Accepted" string with login-wall/selector-chain safeguards, and POSTs it to the backend. The backend parses with `BigDecimal`, applies the update inside a synchronized transaction, ranks, detects overtakes without duplicates, and publishes a snapshot. The side panel polls the backend and displays the dashboard; a clear "backend not running" state appears if the local server isn't up. Monitoring stops once the contest is detected as ended.

---

# 31. AGENT COUNCIL — EXECUTION MODEL ⭐ (roles re-split across extension/backend)

## 31.1 Agent Roster

| Agent | Role | Primary Model | Backup Model | Why this pairing |
|---|---|---|---|---|
| **Architect** | Task breakdown, integration review (including the extension↔backend contract), conflict resolution, final phase sign-off | Claude Opus 4.6 (Thinking) | Claude Sonnet 4.6 (Thinking) | Needs to hold both halves of the system at once |
| **Extension/Background** | `tabLifecycleManager.js`, `alarmScheduler.js`, `backendClient.js`, Section 7A-1 gotchas | Claude Sonnet 4.6 (Thinking) | Gemini 3.1 Pro | Event-driven correctness still needs a reasoning model |
| **Content Script/DOM** | `contestPageScript.js`, `problemPageScript.js`, wait strategy, selector fallback chain | Gemini 3.1 Pro | GPT-OSS 120B | DOM logic is fiddly but bounded |
| **Backend/Core** | `ContestStateService` critical section (7A-2), `ComparisonService`, `ContestLifecycleService` | Claude Sonnet 4.6 (Thinking) | Gemini 3.1 Pro | Real Java concurrency correctness — the highest-stakes backend piece |
| **Parser/Data** | `AcceptanceStatsParser.java`, `BigDecimal` suffix math | GPT-OSS 120B | Gemini 3.7 Flash | Narrow, well-specified, testable in isolation |
| **REST API** | Spring controllers (`config`, `ingest`, `status`, `health`), `CorsConfig.java` | GPT-OSS 120B | Gemini 3.5 Flash | Mostly wiring service output into HTTP, plus a well-defined CORS config |
| **UI** | `sidepanel`/`popup`/`options`, `backendClient` consumption, backend-unreachable states | Gemini 3.7 Flash | Gemini 3.6 Flash | Mechanical, high-volume boilerplate |
| **Testing/QA** | JUnit (incl. concurrency tests) + Jest; optional Playwright E2E | Claude Sonnet 4.6 (Thinking) | Gemini 3.1 Pro | Concurrency tests especially need failure-mode anticipation |
| **Edge-Case Red Team** | Service-worker restarts, tab-close recovery, ingest race conditions, backend-unreachable handling, login-wall, duplicate events | Claude Opus 4.6 (Thinking) | Claude Sonnet 4.6 (Thinking) | Catching what everyone else missed, on both sides |
| **Logging/Docs** | Logging on both sides, code comment audit, all five knowledge artifacts | Gemini 3.5 Flash | Gemini 3.6 Flash | Low-complexity, high-volume text |

**Known gap (unchanged):** Architect and Edge-Case Red Team share Opus → Sonnet; Gemini 3.1 Pro is the emergency third option for both.

## 31.2 Phase → Agent Assignment

| Phase | Lead Agent(s) | Reviewer |
|---|---|---|
| 1. Backend Skeleton | REST API | Architect |
| 2. Extension Scaffold + Tab Lifecycle | Extension/Background | Architect |
| 3. Discovery + Config Registration | Content Script/DOM + REST API | Architect (contract review) |
| 4. Single Question Scraping + Ingest (Q1) | Content Script/DOM + Backend/Core | Edge-Case Red Team (login-wall, round-trip correctness) |
| 5. Parser | Parser/Data | Testing/QA |
| 6. Four Questions + Concurrency | Extension/Background + Backend/Core | Edge-Case Red Team (ingest race conditions) |
| 7. Alarm Scheduler | Extension/Background | Architect |
| 8. State + Ranking | Backend/Core | Edge-Case Red Team (snapshot atomicity) |
| 9. Overtaking Detection | Backend/Core | Testing/QA (duplicates, ties) |
| 10. Status/Health API + Side Panel | REST API + UI | Architect |
| 11. Robustness + Lifecycle | Backend/Core + Extension/Background + Edge-Case Red Team jointly | Architect (final sign-off) |

Logging/Docs runs continuously, updating both sides' logs, code comments, and all five knowledge artifacts before a phase is marked complete.

## 31.3 Failover Protocol (unchanged)

Same six-step protocol as v3/v4: scoped task spec → failure = truncated/errored/silent output, no same-agent retry → backup resumes from partial output, not from scratch → Architect validates against done-criteria → double failure escalates to Architect → every failover logged in `CHANGELOG.md`.

---

# 32. KNOWLEDGE ARTIFACTS — MANDATORY, CONTINUOUSLY UPDATED (structure unchanged, content spans both halves)

## 32.1 `CHANGELOG.md`
Same phase/agent-tagged format; entries should note which side (extension/backend/both) a change touched.

## 32.2 `DECISIONS.md`
Record ADRs for, at minimum: the extension/backend split itself (why not pure-JS, why not pure-Playwright), raw-string-over-the-wire vs. parsing extension-side, the `ContestStateService` critical-section design, `BigDecimal` in the backend, CORS scoped to a pinned extension ID, signal-based vs. time-based contest-end detection, and how backend-unreachable is surfaced to the user.

## 32.3 `FLOW.md`
Must cover: extension startup → discovery → config registration → tab lifecycle setup → alarm registration; one full monitoring cycle **across both processes** (alarm fires → tab reload → content script scrape → background → ingest POST → backend parse/calculate → critical section → snapshot publish → side panel fetch); the backend-unreachable path explicitly, since it's the split's signature new failure mode. Include a Mermaid sequence diagram showing both processes and the HTTP boundary between them.

## 32.4 `DOCUMENTATION.md`
Full reference for both halves: extension permissions and manifest details; backend setup, port, and the "start this before using the extension" requirement front and center; the full REST/ingest contract (Section 22); CORS/security notes (Section 24C); module-by-module purpose on both sides.

## 32.5 `LEARN.md`
Module-by-module walkthrough spanning both processes, one worked full-cycle example that follows a single scraped value from `problemPageScript.js` all the way through the ingest POST, the critical section, and back out through `GET /api/contest/status` to the side panel — this cross-process trace is the single most useful thing this file can contain, since it's the part most likely to be confusing when debugging later. Debugging index built from the status taxonomy (Section 23) plus both Section 7A-1 and 7A-2 gotchas, plus explicitly: *"side panel shows 'backend not running' → check the Spring Boot process is actually started, then check the port matches `backendClient.js`, then check CORS if requests are reaching the backend but being rejected."*

---

# 33. CODE COMMENTING STANDARD (unchanged principle, applies to both languages)

Same standard as v3/v4: file-level header, purpose/inputs/outputs/side-effects on every public method, explicit concurrency notes (which thread/process context a piece of code runs in — this matters on both sides now, differently), "why not what" comments, no uncommented cleverness, no ambiguous abbreviations, `TODO(Phase N)`/`FIXME(Phase N)` tags cross-referenced in `CHANGELOG.md`. On the backend specifically, any method touching the critical section (7A-2) must say so explicitly in its comment.

---

# 34. FINAL DELIVERABLES CHECKLIST (unchanged structure)

**Code:**
- [ ] Extension: all modules per Section 7, Jest suite
- [ ] Backend: all modules per Section 7, JUnit suite including a concurrency test for `ContestStateService`
- [ ] Optional Playwright E2E harness, clearly marked test-only

**Knowledge artifacts (repo root):**
- [ ] `CHANGELOG.md`, `DECISIONS.md`, `FLOW.md`, `DOCUMENTATION.md`, `LEARN.md` — current through the latest phase, covering both halves

**Process:**
- [ ] Every phase has a recorded lead agent, reviewer, and any failover event
- [ ] No file exists without a file-level header comment

---

Before writing large amounts of code: get the backend responding to a trivial `GET /api/contest/health` and the extension successfully calling it (even with a hard-coded URL, before real discovery exists) — proving the HTTP boundary works is the single highest-value early step in this architecture, since everything else depends on it. Implement phase by phase, through the agent council above. After each phase, the lead agent explains: what was implemented, which files changed on which side, how to start/reload both halves to test the change, how to run the relevant test suite, any DOM assumptions, and which knowledge artifacts were updated.

Do not silently skip a phase, skip a documentation update, or implement either half in one huge step.
