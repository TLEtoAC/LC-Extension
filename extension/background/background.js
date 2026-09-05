/**
 * background.js — Service Worker Entry Point
 *
 * Purpose : Registers all top-level Chrome runtime listeners and wires
 *           incoming messages / alarms to the appropriate handler modules.
 *           Orchestrates contest discovery, tab setup (Phase 3), and monitoring cycles.
 *           Acts as the single orchestration hub; actual logic lives in
 *           tabLifecycleManager.js, alarmScheduler.js, and backendClient.js.
 *
 * Author  : Extension/Background Agent
 * Phase   : 7 (Alarm Scheduler polish — interval config + ENDED stop)
 *
 * Context : Runs exclusively in the Chrome Extension Service Worker context.
 *           The service worker may be suspended and restarted at any time by
 *           the browser; NO in-memory state survives a restart — use
 *           chrome.storage.local for all persistent state.
 *
 * IMPORTANT — MV3 Service Worker constraints:
 *   • Never use setTimeout/setInterval for deferred work; the SW may be
 *     killed before the callback fires. Use chrome.alarms instead.
 *   • All waits on tab navigation must be event-driven (chrome.tabs.onUpdated).
 */

import {
  openOrReuseTab,
  reloadTab,
  getPersistedTabIds,
  setPersistedTabIds,
  getCycleInProgress,
  setCycleInProgress,
  recoverMissingTabs
} from './tabLifecycleManager.js';
import {
  checkHealth,
  postConfig,
  postIngest,
  getStatus,
  normalizeQuestionSlot
} from './backendClient.js';
import {
  ALARM_NAME,
  registerMonitoringAlarm,
  updateMonitoringInterval,
  runScrapeCycle,
  resolvePendingScrape,
  handleContestEnded,
  ensureMonitoringAlarm,
  recoverOrphanedCycleGuard,
  isMonitoringStopped
} from './alarmScheduler.js';

// ─── onInstalled ─────────────────────────────────────────────────────────────

/**
 * Handles extension installation and update events.
 *
 * On a fresh install: initialises default storage keys so every other module
 * can safely read them without null-checking.
 *
 * @param {chrome.runtime.InstalledDetails} details
 * @returns {void}
 * @note Runs in the extension service worker context.
 */
function onInstalled(details) {
  if (details.reason === 'install') {
    console.log('[background] Extension installed — initialising storage defaults.');
    chrome.storage.local.set({
      contestUrl: null,
      contestPageTabId: null,
      discoveredQuestions: null,
      discoveryStatus: null,
      discoveryReason: null,
      tabIds: { Q1: null, Q2: null, Q3: null, Q4: null },
      cycleInProgress: false,
      backendUnreachable: false,
      monitoringIntervalMinutes: 5,
      monitoringStopped: false,
    });
  } else if (details.reason === 'update') {
    console.log(`[background] Extension updated to v${chrome.runtime.getManifest().version}.`);
  }
}

chrome.runtime.onInstalled.addListener(onInstalled);

// ─── Phase 3 Discovery Handlers ──────────────────────────────────────────────

/**
 * Handles CONTEST_URL_SAVED message from options.js.
 * Saves the contest URL, opens or reuses the contest homepage tab,
 * and records the contest homepage tab ID in storage.
 *
 * @param {object} message
 * @param {string} message.contestUrl
 * @param {chrome.runtime.MessageSender} sender
 * @param {function} sendResponse
 * @returns {Promise<void>}
 * @sideeffects Updates chrome.storage.local keys "contestUrl" and "contestPageTabId",
 *              creates or focuses a Chrome tab.
 * @note Runs in the extension service worker context.
 */
async function handleContestUrlSaved(message, sender, sendResponse) {
  const contestUrl = message.contestUrl;
  console.log(`[background] Handling CONTEST_URL_SAVED for: ${contestUrl}`);

  try {
    // 1. Save contestUrl in storage
    await chrome.storage.local.set({ contestUrl });

    // 2. Read existing contestPageTabId if any, or pass null
    const { contestPageTabId: existingTabId } = await chrome.storage.local.get('contestPageTabId');

    // 3. Open or reuse contest homepage tab
    const tabId = await openOrReuseTab(existingTabId ?? null, contestUrl);

    // 4. Store returned tabId under contestPageTabId
    await chrome.storage.local.set({ contestPageTabId: tabId });
    console.log(`[background] Contest homepage tab ready with ID: ${tabId}`);

    sendResponse({ ok: true, tabId });
  } catch (err) {
    console.error('[background] Error handling CONTEST_URL_SAVED:', err);
    sendResponse({ ok: false, error: err.message });
  }
}

/**
 * Handles DISCOVERY_COMPLETE message from contestPageScript.js.
 * Persists the discovered questions, posts config to the backend,
 * opens 4 background problem tabs (Q1–Q4), and persists their tab IDs.
 *
 * @param {object} message
 * @param {Array<{questionNumber: string, problemName: string, problemUrl: string}>} message.questions
 * @param {chrome.runtime.MessageSender} sender
 * @param {function} sendResponse
 * @returns {Promise<void>}
 * @sideeffects Updates chrome.storage.local keys "discoveredQuestions", "discoveryStatus",
 *              and "tabIds"; sends HTTP POST to backend; opens 4 Chrome tabs.
 * @note Runs in the extension service worker context.
 */
async function handleDiscoveryComplete(message, sender, sendResponse) {
  const questions = message.questions ?? [];
  console.log(`[background] Handling DISCOVERY_COMPLETE with ${questions.length} questions.`);

  try {
    // 1. Persist questions to chrome.storage.local
    await chrome.storage.local.set({
      discoveredQuestions: questions,
      discoveryStatus: 'COMPLETE'
    });

    // 2. Fetch contestUrl from storage to postConfig payload
    const { contestUrl } = await chrome.storage.local.get('contestUrl');
    const configPayload = {
      contestUrl: contestUrl || '',
      questions: questions
    };

    console.log('[background] Posting contest config to backend:', configPayload);
    const configResult = await postConfig(configPayload);
    if (!configResult) {
      console.warn('[background] Backend postConfig returned null (backend unreachable or rejected).');
    }

    // 3. For each of the 4 discovered questions, open a background tab
    const tabIds = { Q1: null, Q2: null, Q3: null, Q4: null };
    for (const q of questions) {
      if (q.questionNumber && q.problemUrl) {
        const tabId = await openOrReuseTab(null, q.problemUrl);
        tabIds[q.questionNumber] = tabId;
      }
    }

    // 4. Persist the 4 returned tab IDs
    await setPersistedTabIds(tabIds);

    // 5. Log clearly
    console.log(
      `[background] 4 problem tabs opened: Q1=${tabIds.Q1}, Q2=${tabIds.Q2}, Q3=${tabIds.Q3}, Q4=${tabIds.Q4}`
    );

    sendResponse({ ok: true, tabIds });

    // 6. Immediate first cycle + alarm at the stored interval (default 5 min).
    // Discovery already replied so a cycle failure must not re-sendResponse.
    try {
      await registerMonitoringAlarm();
      await runScrapeCycle();
    } catch (cycleErr) {
      console.error('[background] Immediate scrape cycle failed:', cycleErr);
    }
  } catch (err) {
    console.error('[background] Error handling DISCOVERY_COMPLETE:', err);
    sendResponse({ ok: false, error: err.message });
  }
}

/**
 * Handles CLICK_AND_CAPTURE_START message from contestPageScript.js.
 * Listens for navigation on contestPageTabId via a one-time chrome.tabs.onUpdated
 * listener, captures the target problem URL, navigates back to contestUrl,
 * and replies with CLICK_AND_CAPTURE_RESULT.
 *
 * @param {object} message
 * @param {number} [message.entryIndex]
 * @param {chrome.runtime.MessageSender} sender
 * @param {function} sendResponse
 * @returns {Promise<void>}
 * @sideeffects Listens for tab updates, navigates the contest tab back to contestUrl.
 * @note Runs in the extension service worker context.
 */
async function handleClickAndCaptureStart(message, sender, sendResponse) {
  const { contestPageTabId: storedTabId, contestUrl } = await chrome.storage.local.get([
    'contestPageTabId',
    'contestUrl'
  ]);

  const targetTabId = storedTabId ?? sender?.tab?.id;
  const entryIndex = message?.entryIndex;

  console.log(
    `[background] Handling CLICK_AND_CAPTURE_START for entry ${entryIndex} on tab ${targetTabId}`
  );

  if (!targetTabId) {
    console.error('[background] CLICK_AND_CAPTURE_START failed: no valid contestPageTabId found.');
    sendResponse({
      type: 'CLICK_AND_CAPTURE_RESULT',
      entryIndex,
      problemUrl: null,
      error: 'NO_TARGET_TAB'
    });
    return;
  }

  // Set up one-time event-driven listener for tab navigation completion
  const onUpdatedListener = async (updatedTabId, changeInfo, tab) => {
    if (updatedTabId === targetTabId && changeInfo.status === 'complete') {
      chrome.tabs.onUpdated.removeListener(onUpdatedListener);

      let capturedUrl = tab?.url;
      if (!capturedUrl) {
        try {
          const tabInfo = await chrome.tabs.get(targetTabId);
          capturedUrl = tabInfo.url;
        } catch (err) {
          console.error('[background] Failed to get tab info during click-and-capture:', err);
        }
      }

      console.log(`[background] Click-and-capture captured URL: ${capturedUrl} on tab ${targetTabId}`);

      // Navigate back to contestUrl (NEVER history.back())
      if (contestUrl) {
        console.log(`[background] Navigating tab ${targetTabId} back to contest URL: ${contestUrl}`);
        try {
          await chrome.tabs.update(targetTabId, { url: contestUrl });
        } catch (err) {
          console.error('[background] Failed to navigate tab back to contest URL:', err);
        }
      }

      sendResponse({
        type: 'CLICK_AND_CAPTURE_RESULT',
        entryIndex,
        problemUrl: capturedUrl
      });
    }
  };

  chrome.tabs.onUpdated.addListener(onUpdatedListener);
}

/**
 * Handles DISCOVERY_FAILED message from contestPageScript.js.
 * Stores failure status and reason in chrome.storage.local.
 *
 * @param {object} message
 * @param {string} [message.reason]
 * @param {chrome.runtime.MessageSender} sender
 * @param {function} sendResponse
 * @returns {Promise<void>}
 * @sideeffects Updates chrome.storage.local keys "discoveryStatus" and "discoveryReason".
 * @note Runs in the extension service worker context.
 */
async function handleDiscoveryFailed(message, sender, sendResponse) {
  const reason = message?.reason || 'UNKNOWN_ERROR';
  console.warn(`[background] Discovery failed: ${reason}`);

  await chrome.storage.local.set({
    discoveryStatus: 'FAILED',
    discoveryReason: reason
  });

  sendResponse({ ok: true });
}

/**
 * Handles MONITORING_INTERVAL_SAVED from options.js.
 * Persists the clamped interval and re-registers scrapeCycle when monitoring
 * is already active. Does not start a scrape cycle (ADR-020).
 *
 * @param {object} message
 * @param {number} message.periodMinutes
 * @param {chrome.runtime.MessageSender} sender
 * @param {function} sendResponse
 * @returns {Promise<void>}
 */
async function handleMonitoringIntervalSaved(message, sender, sendResponse) {
  try {
    const period = await updateMonitoringInterval(message.periodMinutes);
    sendResponse({ ok: true, periodMinutes: period });
  } catch (err) {
    console.error('[background] MONITORING_INTERVAL_SAVED failed:', err);
    sendResponse({ ok: false, error: err?.message ?? 'UNKNOWN_ERROR' });
  }
}

/**
 * Phase 11 call site: stop scrapeCycle when lifecycle becomes ENDED.
 *
 * ContestLifecycleService (Phase 11, ADR-006) will detect ENDED (3 unchanged
 * cycles) and surface it on health/status. Until then nothing produces ENDED —
 * do not invent detection here. Phase 11 should send { type: 'CONTEST_ENDED' }
 * or import handleContestEnded() from alarmScheduler.js.
 *
 * @param {object} message
 * @param {chrome.runtime.MessageSender} sender
 * @param {function} sendResponse
 * @returns {Promise<void>}
 */
async function handleContestEndedMessage(message, sender, sendResponse) {
  await handleContestEnded(message?.source ?? 'CONTEST_ENDED');
  sendResponse({ ok: true });
}

// ─── onMessage ───────────────────────────────────────────────────────────────

/**
 * Central message dispatcher. Routes incoming messages from content scripts,
 * the options page, the popup, and the side panel to the correct handler.
 *
 * Returning `true` from this listener keeps the message channel open for
 * async responses (required when any handler is async).
 *
 * Supported message types:
 *   CONTEST_URL_SAVED         — Phase 3: trigger tab discovery & open contest tab
 *   DISCOVERY_COMPLETE        — Phase 3: persist discovered questions, config backend, open Q1–Q4 tabs
 *   CLICK_AND_CAPTURE_START   — Phase 3: capture navigation URL for click-and-capture fallback
 *   DISCOVERY_FAILED          — Phase 3: record discovery failure status
 *   MONITORING_INTERVAL_SAVED — Phase 7: persist interval and re-register scrapeCycle
 *   CONTEST_ENDED             — Phase 11 hook: clear scrapeCycle (handleContestEnded)
 *   SCRAPE_RESULT             — Phase 6: postIngest + resolve pending scrape
 *   OPEN_SIDE_PANEL           — Phase 10: open the side panel
 *
 * @param {object} message         The message object sent by the caller.
 * @param {string} message.type    Identifies the message kind.
 * @param {chrome.runtime.MessageSender} sender
 * @param {function} sendResponse  Call with the reply payload.
 * @returns {boolean} true — keeps the channel open for async handlers.
 * @note Runs in the extension service worker context.
 */
function onMessage(message, sender, sendResponse) {
  console.log(`[background] onMessage → type="${message?.type}" from`, sender?.origin ?? sender?.id);

  switch (message?.type) {
    case 'CONTEST_URL_SAVED':
      handleContestUrlSaved(message, sender, sendResponse);
      break;

    case 'DISCOVERY_COMPLETE':
      handleDiscoveryComplete(message, sender, sendResponse);
      break;

    case 'CLICK_AND_CAPTURE_START':
      handleClickAndCaptureStart(message, sender, sendResponse);
      break;

    case 'DISCOVERY_FAILED':
      handleDiscoveryFailed(message, sender, sendResponse);
      break;

    case 'MONITORING_INTERVAL_SAVED':
      handleMonitoringIntervalSaved(message, sender, sendResponse);
      break;

    case 'CONTEST_ENDED':
      handleContestEndedMessage(message, sender, sendResponse);
      break;

    case 'SCRAPE_RESULT':
      handleScrapeResult(message, sender, sendResponse);
      break;

    case 'OPEN_SIDE_PANEL':
      // Phase 6: open the side panel for the sender tab.
      console.log('[background] OPEN_SIDE_PANEL received — handler wired in Phase 6.');
      sendResponse({ ok: true });
      break;

    default:
      console.warn(`[background] Unrecognised message type: "${message?.type}"`);
      sendResponse({ ok: false, error: 'Unknown message type' });
      return false;
  }

  // Return true to keep the message channel open for async sendResponse calls.
  return true;
}

chrome.runtime.onMessage.addListener(onMessage);

// ─── onAlarm ─────────────────────────────────────────────────────────────────

/**
 * Handles a SCRAPE_RESULT from problemPageScript.js.
 * Maps the sender tab (or URL fallback) to Q1–Q4, POSTs ingest, then
 * resolves any pending scrape wait so runScrapeCycle can advance.
 * Only this handler posts real scrapes; the 20s waiter posts NAVIGATION_TIMEOUT.
 *
 * @param {object} message
 * @param {chrome.runtime.MessageSender} sender
 * @param {function} sendResponse
 * @returns {Promise<void>}
 * @note Runs in the extension service worker context.
 */
async function handleScrapeResult(message, sender, sendResponse) {
  try {
    const questionNumber = await deriveQuestionNumber(sender, message);
    if (!questionNumber) {
      console.warn(
        `[background] SCRAPE_RESULT from unmapped tab id=${sender?.tab?.id} url=${message?.url}`
      );
      sendResponse({ ok: false, error: 'UNKNOWN_TAB' });
      return;
    }

    const payload = {
      rawUsersAccepted: message.rawUsersAccepted ?? null,
      scrapingStatus: message.scrapingStatus,
      selectorStrategyUsed: message.selectorStrategyUsed ?? null,
      errorMessage: message.errorMessage ?? null
    };

    let ingestResult = null;
    try {
      ingestResult = await postIngest(questionNumber, payload);
    } catch (err) {
      console.error(`[background] postIngest failed for ${questionNumber}:`, err);
    }

    const tabIds = await getPersistedTabIds();
    const tabId = sender?.tab?.id ?? tabIds[questionNumber];
    if (tabId != null) {
      resolvePendingScrape(tabId);
    }

    sendResponse({ ok: true, ingestResult });
  } catch (err) {
    console.error('[background] handleScrapeResult failed:', err);
    sendResponse({ ok: false, error: err?.message ?? 'UNKNOWN_ERROR' });
  }
}

/**
 * Maps a scrape sender to Q1–Q4: inverted tabIds first, then discoveredQuestions URL.
 *
 * @param {chrome.runtime.MessageSender} sender
 * @param {object} message
 * @returns {Promise<string|null>}
 */
async function deriveQuestionNumber(sender, message) {
  const tabIds = await getPersistedTabIds();
  const senderTabId = sender?.tab?.id;
  if (senderTabId != null) {
    for (const slot of ['Q1', 'Q2', 'Q3', 'Q4']) {
      if (tabIds[slot] === senderTabId) {
        return slot;
      }
    }
  }

  const { discoveredQuestions } = await chrome.storage.local.get('discoveredQuestions');
  const observedUrl = message?.url;
  if (observedUrl && Array.isArray(discoveredQuestions)) {
    const match = discoveredQuestions.find((q) => urlsLooselyMatch(q.problemUrl, observedUrl));
    if (match?.questionNumber) {
      return normalizeQuestionSlot(match.questionNumber);
    }
  }

  return null;
}

/**
 * Compares configured problemUrl to the live tab URL (ignore query + trailing slash).
 *
 * @param {string} configured
 * @param {string} observed
 * @returns {boolean}
 */
function urlsLooselyMatch(configured, observed) {
  if (!configured || !observed) {
    return false;
  }
  const normalize = (u) => String(u).split('?')[0].replace(/\/$/, '');
  const a = normalize(configured);
  const b = normalize(observed);
  return a === b || b.startsWith(a) || a.startsWith(b);
}

/**
 * Handles chrome.alarms events.
 *
 * scrapeCycle: skip if monitoring was stopped (ENDED) or a cycle is already
 * in progress (storage-backed guard), otherwise run Q1→Q4. First cycle is
 * also started immediately after discovery.
 *
 * @param {chrome.alarms.Alarm} alarm  The alarm that fired.
 * @returns {Promise<void>}
 * @note Runs in the extension service worker context.
 */
async function onAlarm(alarm) {
  console.log(`[background] onAlarm → name="${alarm.name}"`);

  if (alarm.name === ALARM_NAME) {
    if (await isMonitoringStopped()) {
      console.log('[background] scrapeCycle fired after ENDED — clearing leftover alarm.');
      await handleContestEnded('stale-alarm');
      return;
    }

    const inProgress = await getCycleInProgress();
    if (inProgress) {
      console.log('[background] Scrape cycle already in progress — skipping alarm.');
      return;
    }

    await runScrapeCycle();
  }
}

chrome.alarms.onAlarm.addListener(onAlarm);

// ─── Startup health check ────────────────────────────────────────────────────

/**
 * Runs once each time the service worker starts (including restarts).
 * Pings the backend, clears an orphaned cycleInProgress left by a mid-cycle
 * SW death, and restores scrapeCycle if monitoring is still active.
 * Does not start a scrape cycle on restart (that would double-fire).
 *
 * @returns {Promise<void>}
 * @note Runs in the extension service worker context.
 */
async function runStartupChecks() {
  console.log('[background] Service worker started — running startup checks.');
  await recoverOrphanedCycleGuard();
  await ensureMonitoringAlarm();
  await checkHealth();
}

runStartupChecks();

