/**
 * alarmScheduler.js — Monitoring Alarm + Sequential Scrape Cycle
 *
 * Purpose : Registers the 5-minute scrapeCycle alarm and runs Q1→Q4 reloads
 *           sequentially (Section 7A-1). Owns the in-memory pending-scrape
 *           map so background.js can resolve a wait when SCRAPE_RESULT arrives.
 *
 * Author  : Extension/Background Agent
 * Phase   : 6 (Four Questions + Concurrency)
 *
 * Context : Runs exclusively in the Chrome Extension Service Worker context.
 *
 * Cycle contract
 * ──────────────
 *   • Sequential Q1→Q4. registerPendingScrape(tabId, q) BEFORE reloadTab.
 *   • Real scrapes: only background.js handleScrapeResult calls postIngest.
 *   • Timeout (20s): this module POSTs a synthetic NAVIGATION_TIMEOUT ingest.
 *   • Duplicate SCRAPE_RESULT: first resolve deletes the pending entry; extras ignored.
 *   • cycleInProgress is persisted (chrome.storage.local) and always cleared in finally.
 */

import {
  getPersistedTabIds,
  getCycleInProgress,
  setCycleInProgress,
  reloadTab
} from './tabLifecycleManager.js';
import { postIngest } from './backendClient.js';

const ALARM_NAME = 'scrapeCycle';
const QUESTION_SLOTS = ['Q1', 'Q2', 'Q3', 'Q4'];
const DEFAULT_PERIOD_MINUTES = 5;
const SCRAPE_TIMEOUT_MS = 20000;

/**
 * In-memory pending scrapes. tabId → { resolve, reject, questionNumber, timerId }.
 * Does not survive a service-worker restart; cycleInProgress in storage is the
 * overlapping-cycle guard. Duplicate results after resolve are ignored.
 *
 * @type {Map<number, { resolve: Function, reject: Function, questionNumber: string, timerId: ReturnType<typeof setTimeout>|null, promise: Promise<void> }>}
 */
const pendingScrapes = new Map();

/**
 * Registers (or re-registers) the periodic scrapeCycle alarm.
 * Clears any existing alarm first so this is idempotent.
 *
 * delayInMinutes: 0 fires as soon as Chrome allows; periodInMinutes repeats.
 * The discovery handler also calls runScrapeCycle() immediately so the first
 * cycle does not wait for the period.
 *
 * @param {number} [periodMinutes=5]
 * @returns {Promise<void>}
 * @sideeffects chrome.alarms.clear / chrome.alarms.create
 * @note Runs in the extension service worker context.
 */
export async function registerMonitoringAlarm(periodMinutes = DEFAULT_PERIOD_MINUTES) {
  await chrome.alarms.clear(ALARM_NAME);
  await chrome.alarms.create(ALARM_NAME, {
    delayInMinutes: 0,
    periodInMinutes: periodMinutes
  });
  console.log(`[alarmScheduler] Registered '${ALARM_NAME}' every ${periodMinutes} min (delay 0).`);
}

/**
 * Runs one sequential scrape cycle across persisted Q1–Q4 tabs.
 * Overlapping calls return immediately if cycleInProgress is already true.
 *
 * @returns {Promise<void>}
 * @sideeffects Reloads tabs, writes cycleInProgress, may POST NAVIGATION_TIMEOUT
 * @note Runs in the extension service worker context.
 */
export async function runScrapeCycle() {
  const alreadyRunning = await getCycleInProgress();
  if (alreadyRunning) {
    console.log('[alarmScheduler] Cycle already in progress — skipping.');
    return;
  }

  await setCycleInProgress(true);
  console.log('[alarmScheduler] Scrape cycle started (Q1→Q4).');

  try {
    const tabIds = await getPersistedTabIds();

    for (const questionNumber of QUESTION_SLOTS) {
      const tabId = tabIds[questionNumber];
      if (tabId == null) {
        console.warn(`[alarmScheduler] No persisted tab for ${questionNumber} — skipping.`);
        continue;
      }

      try {
        registerPendingScrape(tabId, questionNumber);
        await reloadTab(tabId);
        await waitForScrapeResult(tabId, SCRAPE_TIMEOUT_MS);
      } catch (err) {
        console.warn(`[alarmScheduler] ${questionNumber} (tab ${tabId}) failed:`, err);
        cancelPendingScrape(tabId);
      }
    }
  } finally {
    await setCycleInProgress(false);
    console.log('[alarmScheduler] Scrape cycle finished — cycleInProgress cleared.');
  }
}

/**
 * Records a pending scrape for tabId. Must be called BEFORE reloadTab so a
 * fast SCRAPE_RESULT is not dropped.
 *
 * @param {number} tabId
 * @param {string} questionNumber  Q1–Q4
 * @returns {void}
 */
export function registerPendingScrape(tabId, questionNumber) {
  cancelPendingScrape(tabId);

  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });

  pendingScrapes.set(tabId, {
    resolve,
    reject,
    questionNumber,
    timerId: null,
    promise
  });
}

/**
 * Waits until handleScrapeResult resolves the pending entry, or until timeout.
 * On timeout, POSTs a synthetic NAVIGATION_TIMEOUT ingest (does not silently skip).
 *
 * @param {number} tabId
 * @param {number} timeoutMs
 * @returns {Promise<void>}
 */
export function waitForScrapeResult(tabId, timeoutMs) {
  const entry = pendingScrapes.get(tabId);
  if (!entry) {
    return Promise.resolve();
  }

  entry.timerId = setTimeout(() => {
    const current = pendingScrapes.get(tabId);
    if (!current) {
      return;
    }
    pendingScrapes.delete(tabId);
    console.warn(
      `[alarmScheduler] Scrape timed out after ${timeoutMs}ms for ${current.questionNumber} (tab ${tabId}).`
    );
    postIngest(current.questionNumber, {
      scrapingStatus: 'NAVIGATION_TIMEOUT',
      rawUsersAccepted: null,
      errorMessage: `Scrape timed out after ${timeoutMs}ms for ${current.questionNumber}`
    }).catch((err) => {
      console.error('[alarmScheduler] NAVIGATION_TIMEOUT ingest failed:', err);
    }).finally(() => {
      current.resolve();
    });
  }, timeoutMs);

  return entry.promise;
}

/**
 * Resolves a pending scrape (called from background.js after a real SCRAPE_RESULT
 * has been posted). First call wins; later calls are no-ops.
 *
 * @param {number} tabId
 * @returns {boolean} true if a pending entry was resolved
 */
export function resolvePendingScrape(tabId) {
  const entry = pendingScrapes.get(tabId);
  if (!entry) {
    return false;
  }
  if (entry.timerId != null) {
    clearTimeout(entry.timerId);
  }
  pendingScrapes.delete(tabId);
  entry.resolve();
  return true;
}

/**
 * Drops a pending entry without posting. Used when reloadTab throws.
 *
 * @param {number} tabId
 * @returns {void}
 */
function cancelPendingScrape(tabId) {
  const entry = pendingScrapes.get(tabId);
  if (!entry) {
    return;
  }
  if (entry.timerId != null) {
    clearTimeout(entry.timerId);
  }
  pendingScrapes.delete(tabId);
  entry.resolve();
}
