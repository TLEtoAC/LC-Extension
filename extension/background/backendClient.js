/**
 * backendClient.js — HTTP Client for the LeetCode Contest Monitor Backend
 *
 * Purpose : Wraps all fetch() calls to the local Spring Boot backend.
 *           Provides a clean API surface for background.js and other modules
 *           to communicate with the backend without duplicating error-handling
 *           logic.
 *
 * Author  : Extension/Background Agent
 * Phase   : 2 (scaffold + full implementation)
 *
 * Context : Runs exclusively in the Chrome Extension Service Worker context.
 *
 * Backend contract
 * ─────────────────
 *   • Base URL : http://localhost:8080
 *   • On TypeError (connection refused / server not running):
 *       - Sets chrome.storage.local key `backendUnreachable: true`
 *       - Returns null
 *       - Does NOT retry — the next alarm cycle will produce a fresh scrape
 *   • On success:
 *       - Clears `backendUnreachable` (sets false)
 *       - Returns parsed JSON (or `true` for 204 No Content)
 *   • On non-2xx HTTP response:
 *       - Logs status + body
 *       - Returns null
 *   • POST requests set Content-Type: application/json
 *
 * Drop-on-unreachable policy
 * ───────────────────────────
 * ingest calls are deliberately dropped (return null immediately) when the
 * backend is flagged unreachable. There is no retry queue — stale data is
 * worthless; the next alarm cycle will produce a fresh scrape.
 */

const BACKEND_BASE_URL = 'http://localhost:8080';

// ─── Internal helpers ─────────────────────────────────────────────────────────

/**
 * Writes the `backendUnreachable` flag to chrome.storage.local.
 *
 * Other modules (e.g. the side-panel UI) may read this key to display a
 * "backend offline" banner without initiating their own network requests.
 *
 * @param {boolean} val  true → backend is unreachable; false → backend is up.
 * @returns {Promise<void>}
 * @sideeffects Writes to chrome.storage.local key "backendUnreachable".
 * @note Runs in the extension service worker context.
 */
async function _setBackendUnreachable(val) {
  await chrome.storage.local.set({ backendUnreachable: Boolean(val) });
}

/**
 * Core fetch wrapper used by all public functions.
 *
 * Handles:
 *   • TypeError (network-level failure / connection refused)
 *   • Non-2xx HTTP responses
 *   • JSON parsing
 *   • Console logging of URL and response status
 *
 * @param {string} url            Full URL to fetch.
 * @param {RequestInit} [options] Standard fetch init options.
 * @returns {Promise<object|true|null>}
 *   - Parsed JSON on 2xx with a body
 *   - `true` on 204 No Content
 *   - `null` on any error
 * @note Runs in the extension service worker context.
 */
async function _fetch(url, options = {}) {
  console.log(`[backendClient] → ${options.method ?? 'GET'} ${url}`);

  let response;
  try {
    response = await fetch(url, options);
  } catch (err) {
    if (err instanceof TypeError) {
      // Network-level failure: backend not running, CORS preflight blocked, etc.
      console.warn(`[backendClient] TypeError — backend unreachable: ${err.message}`);
      await _setBackendUnreachable(true);
      return null;
    }
    // Unexpected error type — rethrow so callers can see it.
    throw err;
  }

  console.log(`[backendClient] ← ${response.status} ${response.statusText} (${url})`);

  if (!response.ok) {
    let body = '(no body)';
    try {
      body = await response.text();
    } catch { /* swallow — we're already in an error path */ }
    console.error(`[backendClient] Non-2xx response ${response.status} from ${url}:`, body);
    return null;
  }

  // 204 No Content — no body to parse.
  if (response.status === 204) {
    await _setBackendUnreachable(false);
    return true;
  }

  const json = await response.json();
  await _setBackendUnreachable(false);
  return json;
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Pings the backend health endpoint.
 *
 * Use on service worker startup and before the first scrape cycle to
 * determine whether the backend is available. The result also updates the
 * `backendUnreachable` storage flag consumed by the UI.
 *
 * @returns {Promise<object|null>}
 *   Parsed health response on success; null if the backend is unreachable or
 *   returns a non-2xx status.
 * @sideeffects Updates chrome.storage.local key "backendUnreachable".
 * @note Runs in the extension service worker context.
 */
export async function checkHealth() {
  return _fetch(`${BACKEND_BASE_URL}/api/contest/health`);
}

/**
 * Posts contest configuration (URL, participant list, etc.) to the backend.
 *
 * Typically called once after the user saves a contest URL in options.html.
 *
 * @param {object} payload  Configuration object — shape defined by the backend
 *                          API (Phase 1). Serialised as JSON.
 * @returns {Promise<object|null>}
 *   Parsed response on success; null on error.
 * @sideeffects Updates chrome.storage.local key "backendUnreachable".
 * @note Runs in the extension service worker context.
 */
export async function postConfig(payload) {
  return _fetch(`${BACKEND_BASE_URL}/api/contest/config`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/**
 * Posts scraped acceptance data for a single problem to the backend.
 *
 * DROP-ON-UNREACHABLE: if `backendUnreachable` is true this function returns
 * null immediately without making a network request. Stale data is worthless;
 * the next alarm cycle will produce a fresh scrape.
 *
 * @param {number|string} questionNumber  1–4 identifying the problem slot.
 * @param {object}        payload         Scraped data object. Serialised as JSON.
 * @returns {Promise<object|null>}
 *   Parsed response on success; null if unreachable or on error.
 * @sideeffects Reads and updates chrome.storage.local key "backendUnreachable".
 * @note Runs in the extension service worker context.
 */
export async function postIngest(questionNumber, payload) {
  // Drop-on-unreachable: read current flag before touching the network.
  const { backendUnreachable } = await chrome.storage.local.get('backendUnreachable');
  if (backendUnreachable) {
    console.warn(
      `[backendClient] postIngest(Q${questionNumber}) dropped — backend is unreachable.`
    );
    return null;
  }

  return _fetch(`${BACKEND_BASE_URL}/api/contest/ingest/${questionNumber}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/**
 * Retrieves the current monitoring status from the backend.
 *
 * Used by the side panel and popup to display live stats without triggering
 * a new scrape cycle.
 *
 * @returns {Promise<object|null>}
 *   Parsed status object on success; null if the backend is unreachable or
 *   returns a non-2xx status.
 * @sideeffects Updates chrome.storage.local key "backendUnreachable".
 * @note Runs in the extension service worker context.
 */
export async function getStatus() {
  return _fetch(`${BACKEND_BASE_URL}/api/contest/status`);
}
