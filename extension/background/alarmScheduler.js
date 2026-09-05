/**
 * alarmScheduler.js — Monitoring Alarm + Sequential Scrape Cycle
 *
 * Purpose : Registers the scrapeCycle alarm on a configurable interval
 *           (default 5 min) and runs Q1→Q4 reloads sequentially
 *           (Section 7A-1 / Section 10). Owns the in-memory pending-scrape
 *           map so background.js can resolve a wait when SCRAPE_RESULT arrives.
 *
 * Author  : Extension/Background Agent
 * Phase   : 7 (Alarm Scheduler polish — interval config + ENDED stop)
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
 *   • Interval is persisted as monitoringIntervalMinutes (ADR-020). Changing it
 *     clears and recreates scrapeCycle; it does not start an extra cycle.
 *   • stopMonitoringAlarm() / handleContestEnded() is the Phase 11 ENDED hook
 *     (ADR-006 / ADR-021). Do not invent ENDED detection here.
 */

import {
  getPersistedTabIds,
  getCycleInProgress,
  setCycleInProgress,
  reloadTab,
  recoverMissingTabs
} from './tabLifecycleManager.js';
import { postIngest, getStatus, checkHealth } from './backendClient.js';

export const ALARM_NAME = 'scrapeCycle';
export const QUESTION_SLOTS = ['Q1', 'Q2', 'Q3', 'Q4'];
export const DEFAULT_PERIOD_MINUTES = 5;
export const MIN_PERIOD_MINUTES = 1;
export const MAX_PERIOD_MINUTES = 60;
export const STORAGE_KEY_INTERVAL = 'monitoringIntervalMinutes';
export const STORAGE_KEY_MONITORING_STOPPED = 'monitoringStopped';
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
 * Clamps a user/storage interval to Chrome's 1-minute alarm floor and the
 * 60-minute personal-use ceiling (ADR-020). Non-finite values fall back to 5.
 *
 * @param {unknown} value
 * @returns {number}
 */
export function clampPeriodMinutes(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) {
    return DEFAULT_PERIOD_MINUTES;
  }
  return Math.min(MAX_PERIOD_MINUTES, Math.max(MIN_PERIOD_MINUTES, Math.round(n)));
}

/**
 * Reads the persisted scrape interval, falling back to the 5-minute default.
 *
 * @returns {Promise<number>}
 * @note Runs in the extension service worker or options-page context.
 */
export async function readStoredPeriodMinutes() {
  const result = await chrome.storage.local.get(STORAGE_KEY_INTERVAL);
  return clampPeriodMinutes(result[STORAGE_KEY_INTERVAL] ?? DEFAULT_PERIOD_MINUTES);
}

/**
 * True when Phase 11 (or a test) has called stopMonitoringAlarm / handleContestEnded.
 *
 * @returns {Promise<boolean>}
 */
export async function isMonitoringStopped() {
  const result = await chrome.storage.local.get(STORAGE_KEY_MONITORING_STOPPED);
  return result[STORAGE_KEY_MONITORING_STOPPED] === true;
}

/**
 * True when at least one Q1–Q4 background tab ID is persisted.
 *
 * @returns {Promise<boolean>}
 */
async function hasPersistedProblemTabs() {
  const tabIds = await getPersistedTabIds();
  return QUESTION_SLOTS.some((slot) => tabIds?.[slot] != null);
}

/**
 * Registers (or re-registers) the periodic scrapeCycle alarm.
 * Clears any existing alarm first so this is idempotent.
 *
 * delayInMinutes: 0 fires as soon as Chrome allows; periodInMinutes repeats.
 * Discovery still calls runScrapeCycle() immediately so the first cycle does
 * not wait for the period. Interval changes pass delayInMinutes = period so
 * the next tick follows the new cadence without a bonus scrape.
 *
 * @param {number} [periodMinutes]  Omitted → read monitoringIntervalMinutes
 * @param {{ delayInMinutes?: number }} [options]
 * @returns {Promise<number>} The clamped period that was registered
 * @sideeffects chrome.alarms.clear / chrome.alarms.create; writes interval + monitoringStopped=false
 * @note Runs in the extension service worker context.
 */
export async function registerMonitoringAlarm(periodMinutes, options = {}) {
  const period = clampPeriodMinutes(
    periodMinutes != null ? periodMinutes : await readStoredPeriodMinutes()
  );
  const delayInMinutes = options.delayInMinutes != null ? options.delayInMinutes : 0;

  await chrome.storage.local.set({
    [STORAGE_KEY_INTERVAL]: period,
    [STORAGE_KEY_MONITORING_STOPPED]: false
  });

  await chrome.alarms.clear(ALARM_NAME);
  await chrome.alarms.create(ALARM_NAME, {
    delayInMinutes,
    periodInMinutes: period
  });
  console.log(
    `[alarmScheduler] Registered '${ALARM_NAME}' every ${period} min (delay ${delayInMinutes}).`
  );
  return period;
}

/**
 * Re-registers scrapeCycle when the user changes the options-page interval.
 * Does not start a cycle — the existing cycleInProgress guard still applies
 * if a cycle is already running.
 *
 * @param {number} periodMinutes
 * @returns {Promise<number>}
 * @sideeffects Clears and recreates scrapeCycle
 */
export async function updateMonitoringInterval(periodMinutes) {
  const period = clampPeriodMinutes(periodMinutes);
  const stopped = await isMonitoringStopped();
  const monitoring = await hasPersistedProblemTabs();

  await chrome.storage.local.set({ [STORAGE_KEY_INTERVAL]: period });
  console.log(`[alarmScheduler] Interval persisted: ${period} min.`);

  if (stopped || !monitoring) {
    console.log(
      '[alarmScheduler] Interval saved; alarm not re-registered (stopped or not yet monitoring).'
    );
    return period;
  }

  return registerMonitoringAlarm(period, { delayInMinutes: period });
}

/**
 * Clears the scrapeCycle alarm and refuses to start new cycles.
 * Persists monitoringStopped so a service-worker restart does not recreate it.
 *
 * Phase 11 call site: handleContestEnded() wraps this. Do not detect ENDED here.
 *
 * @returns {Promise<void>}
 * @sideeffects chrome.alarms.clear; writes monitoringStopped=true
 */
export async function stopMonitoringAlarm() {
  await chrome.alarms.clear(ALARM_NAME);
  await chrome.storage.local.set({ [STORAGE_KEY_MONITORING_STOPPED]: true });
  console.log(`[alarmScheduler] Cleared '${ALARM_NAME}' — no new cycles until re-registered.`);
}

/**
 * Phase 11 hook — invoke when backend lifecycleState becomes ENDED.
 *
 * ContestLifecycleService (Phase 11, ADR-006) is the detector: 3 consecutive
 * unchanged cycles. GET /api/contest/health and GET /api/contest/status do
 * not yet expose ENDED (Phase 10/11). This function must not invent that
 * signal. Phase 11 should call handleContestEnded() once health/status
 * reports lifecycleState === "ENDED".
 *
 * @param {string} [source='phase11']  Log label for the caller
 * @returns {Promise<void>}
 */
export async function handleContestEnded(source = 'phase11') {
  console.log(`[alarmScheduler] handleContestEnded (${source}) — stopping scrapeCycle.`);
  await stopMonitoringAlarm();
  await setCycleInProgress(false);
}

/**
 * Observes backend lifecycleState and invokes the Phase 7 ENDED hook.
 * Single stop path: handleContestEnded → stopMonitoringAlarm.
 *
 * @param {string} [source]
 * @returns {Promise<boolean>} true if ENDED was observed and the hook ran
 */
export async function observeEndedAndStop(source = 'status-poll') {
  if (await isMonitoringStopped()) {
    return true;
  }
  let snapshot = null;
  try {
    snapshot = await getStatus();
  } catch (err) {
    console.warn('[alarmScheduler] getStatus for ENDED check failed:', err);
  }
  if (!snapshot || snapshot.lifecycleState !== 'ENDED') {
    return false;
  }
  await handleContestEnded(source);
  return true;
}

/**
 * Startup path: health payload also carries lifecycleState (Phase 10).
 *
 * @returns {Promise<boolean>}
 */
export async function observeEndedFromHealth() {
  if (await isMonitoringStopped()) {
    return true;
  }
  let health = null;
  try {
    health = await checkHealth();
  } catch (err) {
    console.warn('[alarmScheduler] checkHealth for ENDED check failed:', err);
    return false;
  }
  if (!health || health.lifecycleState !== 'ENDED') {
    return false;
  }
  await handleContestEnded('health-startup');
  return true;
}

/**
 * After a service-worker restart: restore scrapeCycle if monitoring is active
 * and the alarm was lost (unpacked reload clears alarms). Does not start a
 * cycle. Honors monitoringStopped so ENDED stays stopped.
 *
 * @returns {Promise<void>}
 */
export async function ensureMonitoringAlarm() {
  if (await isMonitoringStopped()) {
    console.log('[alarmScheduler] ensureMonitoringAlarm: monitoringStopped — leaving alarm cleared.');
    return;
  }
  if (!(await hasPersistedProblemTabs())) {
    return;
  }
  const existing = await chrome.alarms.get(ALARM_NAME);
  if (existing) {
    console.log('[alarmScheduler] scrapeCycle already present after SW start — leaving it.');
    return;
  }
  const period = await readStoredPeriodMinutes();
  await registerMonitoringAlarm(period, { delayInMinutes: period });
  console.log('[alarmScheduler] Restored missing scrapeCycle after SW start.');
}

/**
 * A mid-cycle SW death leaves cycleInProgress=true in storage while the
 * in-memory pending map is empty — future alarms would skip forever.
 * Clear the orphaned guard on startup. A live overlapping cycle still
 * uses the persisted guard (runScrapeCycle checks it first).
 *
 * @returns {Promise<void>}
 */
export async function recoverOrphanedCycleGuard() {
  if (await getCycleInProgress()) {
    await setCycleInProgress(false);
    console.log('[alarmScheduler] Cleared orphaned cycleInProgress after SW restart.');
  }
}

/**
 * Runs one sequential scrape cycle across persisted Q1–Q4 tabs.
 * Overlapping calls return immediately if cycleInProgress is already true.
 * Returns immediately if monitoring was stopped (ENDED hook).
 *
 * @returns {Promise<void>}
 * @sideeffects Reloads tabs, writes cycleInProgress, may POST NAVIGATION_TIMEOUT
 * @note Runs in the extension service worker context.
 */
export async function runScrapeCycle() {
  if (await isMonitoringStopped()) {
    console.log('[alarmScheduler] Monitoring stopped (ENDED) — not starting a cycle.');
    return;
  }

  const alreadyRunning = await getCycleInProgress();
  if (alreadyRunning) {
    console.log('[alarmScheduler] Cycle already in progress — skipping.');
    return;
  }

  await setCycleInProgress(true);
  console.log('[alarmScheduler] Scrape cycle started (Q1→Q4).');

  try {
    try {
      const recovery = await recoverMissingTabs();
      if (recovery.recovered.length > 0) {
        console.log('[alarmScheduler] Recovered missing tabs:', recovery.recovered.join(','));
      }
    } catch (recoverErr) {
      console.warn('[alarmScheduler] recoverMissingTabs failed:', recoverErr);
    }

    const tabIds = await getPersistedTabIds();

    for (const questionNumber of QUESTION_SLOTS) {
      if (await isMonitoringStopped()) {
        console.log('[alarmScheduler] ENDED during cycle — stopping remaining questions.');
        break;
      }

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
    try {
      await observeEndedAndStop('post-cycle-status');
    } catch (endedErr) {
      console.warn('[alarmScheduler] ENDED observe after cycle failed:', endedErr);
    }
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
