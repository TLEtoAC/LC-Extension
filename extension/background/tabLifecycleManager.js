/**
 * tabLifecycleManager.js — Tab Persistence & Lifecycle Management
 *
 * Purpose : Owns the lifecycle of the four problem-page tabs (Q1–Q4).
 *           All tab IDs and the cycleInProgress flag are stored in
 *           chrome.storage.local so they survive service worker restarts.
 *           NEVER cache these values in module-level variables only — the
 *           service worker can be killed and restarted between any two
 *           event firings, wiping all in-memory state.
 *
 * Author  : Extension/Background Agent
 * Phase   : 2 (scaffold); recoverMissingTabs is a stub until Phase 11.
 *
 * Context : Runs exclusively in the Chrome Extension Service Worker context.
 *
 * Section 7A-1 compliance:
 *   [G1] Non-persistent SW  — reloadTab() uses chrome.tabs.onUpdated (event-
 *        driven), NOT setTimeout.
 *   [G2] Tab survival        — openOrReuseTab() detects closed tabs via
 *        chrome.tabs.get rejection and opens a fresh one.
 *   [G3] cycleInProgress     — persisted to chrome.storage.local, not memory.
 *   [G4] Tab ID persistence  — all IDs written through setPersistedTabIds().
 */

// Storage key constants — centralised to avoid typo drift across files.
const STORAGE_KEY_TAB_IDS       = 'tabIds';
const STORAGE_KEY_CYCLE         = 'cycleInProgress';

// ─── Tab ID persistence ───────────────────────────────────────────────────────

/**
 * Reads the persisted tab-ID map from chrome.storage.local.
 *
 * The returned object has shape `{ Q1, Q2, Q3, Q4 }` where each value is
 * either a numeric Chrome tab ID or `null` if no tab has been opened yet.
 *
 * @returns {Promise<{Q1: number|null, Q2: number|null, Q3: number|null, Q4: number|null}>}
 * @note Runs in the extension service worker context.
 */
export async function getPersistedTabIds() {
  const result = await chrome.storage.local.get(STORAGE_KEY_TAB_IDS);
  return result[STORAGE_KEY_TAB_IDS] ?? { Q1: null, Q2: null, Q3: null, Q4: null };
}

/**
 * Writes a new tab-ID map to chrome.storage.local, replacing the previous one.
 *
 * Call this whenever any tab ID changes so the state survives a SW restart.
 *
 * @param {{ Q1: number|null, Q2: number|null, Q3: number|null, Q4: number|null }} tabIds
 * @returns {Promise<void>}
 * @sideeffects Writes to chrome.storage.local key "tabIds".
 * @note Runs in the extension service worker context.
 */
export async function setPersistedTabIds(tabIds) {
  await chrome.storage.local.set({ [STORAGE_KEY_TAB_IDS]: tabIds });
}

// ─── cycleInProgress guard ───────────────────────────────────────────────────

/**
 * Reads the `cycleInProgress` flag from chrome.storage.local.
 *
 * Persisting this flag (rather than keeping it in memory) is critical: the
 * service worker can be suspended mid-cycle and restarted by the next alarm,
 * which would otherwise start a second overlapping cycle.
 *
 * @returns {Promise<boolean>}
 * @note Runs in the extension service worker context.
 */
export async function getCycleInProgress() {
  const result = await chrome.storage.local.get(STORAGE_KEY_CYCLE);
  return result[STORAGE_KEY_CYCLE] ?? false;
}

/**
 * Sets the `cycleInProgress` flag in chrome.storage.local.
 *
 * Set to `true` at the start of a scrape cycle and back to `false` at the
 * end (including in error paths) to prevent the alarm handler from starting
 * an overlapping cycle.
 *
 * @param {boolean} val
 * @returns {Promise<void>}
 * @sideeffects Writes to chrome.storage.local key "cycleInProgress".
 * @note Runs in the extension service worker context.
 */
export async function setCycleInProgress(val) {
  await chrome.storage.local.set({ [STORAGE_KEY_CYCLE]: Boolean(val) });
}

// ─── Tab open/reuse ──────────────────────────────────────────────────────────

/**
 * Opens a new tab at `url` if `tabId` no longer refers to a live tab,
 * otherwise returns `tabId` unchanged.
 *
 * Uses chrome.tabs.get to probe liveness. A rejected promise (tab not found)
 * means the tab was closed by the user or the browser — a new tab is created
 * and its ID is persisted automatically.
 *
 * Note: this function does NOT wait for the new tab to finish loading.
 * Call reloadTab() (or listen to chrome.tabs.onUpdated) if you need the
 * page to be fully loaded before interacting with it.
 *
 * @param {number|null} tabId   Previously stored tab ID, or null on first run.
 * @param {string}      url     URL to open if a new tab is needed.
 * @returns {Promise<number>}   The live tab ID (existing or newly created).
 * @sideeffects May create a Chrome tab; does NOT update the persisted map
 *              on its own — callers must call setPersistedTabIds() after
 *              updating their local copy.
 * @note Runs in the extension service worker context.
 */
export async function openOrReuseTab(tabId, url) {
  if (tabId !== null && tabId !== undefined) {
    try {
      await chrome.tabs.get(tabId);
      console.log(`[tabLifecycle] Tab ${tabId} is alive — reusing.`);
      return tabId;
    } catch {
      // Tab no longer exists (closed by user or browser GC).
      console.warn(`[tabLifecycle] Tab ${tabId} is gone — opening a new tab for ${url}`);
    }
  }

  const newTab = await chrome.tabs.create({ url, active: false });
  console.log(`[tabLifecycle] Opened new tab ${newTab.id} for ${url}`);
  return newTab.id;
}

// ─── Tab reload (event-driven) ───────────────────────────────────────────────

/**
 * Reloads a tab and returns a Promise that resolves once the tab reports
 * `status === 'complete'` via chrome.tabs.onUpdated.
 *
 * CRITICAL — uses chrome.tabs.onUpdated (event-driven), NOT setTimeout.
 * Using setTimeout in a service worker is unreliable because the SW may be
 * suspended before the timeout fires.
 *
 * The listener is cleaned up immediately after the first matching update to
 * avoid accumulating ghost listeners across many reload cycles.
 *
 * @param {number} tabId  The tab to reload.
 * @returns {Promise<void>} Resolves when the tab has finished loading.
 * @sideeffects Calls chrome.tabs.reload; attaches and detaches a temporary
 *              chrome.tabs.onUpdated listener.
 * @note Runs in the extension service worker context.
 */
export function reloadTab(tabId) {
  return new Promise((resolve, reject) => {
    // Guard: if the tab doesn't exist, reject immediately.
    chrome.tabs.get(tabId).then(() => {
      /**
       * Temporary onUpdated listener — cleaned up as soon as the target tab
       * reaches status 'complete'.
       *
       * @param {number} updatedTabId
       * @param {chrome.tabs.TabChangeInfo} changeInfo
       */
      function onUpdatedListener(updatedTabId, changeInfo) {
        if (updatedTabId === tabId && changeInfo.status === 'complete') {
          chrome.tabs.onUpdated.removeListener(onUpdatedListener);
          console.log(`[tabLifecycle] Tab ${tabId} reload complete.`);
          resolve();
        }
      }

      chrome.tabs.onUpdated.addListener(onUpdatedListener);
      chrome.tabs.reload(tabId);
      console.log(`[tabLifecycle] Reload initiated for tab ${tabId} — waiting for 'complete'.`);
    }).catch((err) => {
      console.error(`[tabLifecycle] reloadTab: tab ${tabId} not found.`, err);
      reject(new Error(`Tab ${tabId} does not exist and cannot be reloaded.`));
    });
  });
}

// ─── Phase 11 stub ───────────────────────────────────────────────────────────

/**
 * Recovers any tabs that are missing from the persisted tabIds map.
 *
 * STUB — full implementation deferred to Phase 11 (TAB_MISSING recovery).
 *
 * When implemented this function will:
 *   1. Read getPersistedTabIds().
 *   2. For each key (Q1–Q4) whose value is null or whose tab no longer
 *      exists, call openOrReuseTab() with the correct problem URL.
 *   3. Update the persisted tab ID map via setPersistedTabIds().
 *
 * @param {string} contestUrl  Base contest URL, e.g.
 *                             "https://leetcode.com/contest/weekly-contest-123/"
 * @returns {Promise<void>}
 * @note Runs in the extension service worker context.
 */
export async function recoverMissingTabs(contestUrl) {
  console.warn(
    '[tabLifecycle] recoverMissingTabs() called — Phase 11 stub only. ' +
    `contestUrl="${contestUrl}" — no recovery action taken yet.`
  );
}
