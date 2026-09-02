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

