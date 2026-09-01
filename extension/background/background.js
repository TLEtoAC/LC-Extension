/**
 * background.js — Service Worker Entry Point
 *
 * Purpose : Registers all top-level Chrome runtime listeners and wires
 *           incoming messages / alarms to the appropriate handler modules.
 *           Acts as the single orchestration hub; actual logic lives in
 *           tabLifecycleManager.js, alarmScheduler.js, and backendClient.js.
 *
 * Author  : Extension/Background Agent
 * Phase   : 2 (scaffold) — alarm and message handlers stubbed; will be
 *           fleshed out in Phases 3, 5, 7, and 11.
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

import { recoverMissingTabs, getCycleInProgress } from './tabLifecycleManager.js';
import { checkHealth } from './backendClient.js';

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
      tabIds: { Q1: null, Q2: null, Q3: null, Q4: null },
      cycleInProgress: false,
      backendUnreachable: false,
    });
  } else if (details.reason === 'update') {
    console.log(`[background] Extension updated to v${chrome.runtime.getManifest().version}.`);
  }
}

chrome.runtime.onInstalled.addListener(onInstalled);

// ─── onMessage ───────────────────────────────────────────────────────────────

/**
 * Central message dispatcher. Routes incoming messages from content scripts,
 * the options page, the popup, and the side panel to the correct handler.
 *
 * Returning `true` from this listener keeps the message channel open for
 * async responses (required when any handler is async).
 *
 * Supported message types (stubs — completed in later phases):
 *   CONTEST_URL_SAVED  — Phase 3: trigger tab discovery
 *   SCRAPE_RESULT      — Phase 5: receive scraped data from content script
 *   OPEN_SIDE_PANEL    — Phase 6: open the side panel
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
      // Phase 3: handleContestUrlSaved(message, sendResponse);
      console.log('[background] CONTEST_URL_SAVED received — handler wired in Phase 3.');
      sendResponse({ ok: true });
      break;

    case 'SCRAPE_RESULT':
      // Phase 5: handleScrapeResult(message, sendResponse);
      console.log('[background] SCRAPE_RESULT received — handler wired in Phase 5.');
      sendResponse({ ok: true });
      break;

    case 'OPEN_SIDE_PANEL':
      // Phase 6: open the side panel for the sender tab.
      console.log('[background] OPEN_SIDE_PANEL received — handler wired in Phase 6.');
      sendResponse({ ok: true });
      break;

    default:
      console.warn(`[background] Unrecognised message type: "${message?.type}"`);
      sendResponse({ ok: false, error: 'Unknown message type' });
  }

  // Return true to keep the message channel open for async sendResponse calls.
  return true;
}

chrome.runtime.onMessage.addListener(onMessage);

// ─── onAlarm ─────────────────────────────────────────────────────────────────

/**
 * Handles chrome.alarms events.
 *
 * Phase 7 will replace the stub body with the full scrape-cycle orchestration.
 * The `cycleInProgress` guard (persisted to chrome.storage.local — NOT
 * in-memory) prevents overlapping cycles if an alarm fires while a previous
 * scrape is still running.
 *
 * @param {chrome.alarms.Alarm} alarm  The alarm that fired.
 * @returns {Promise<void>}
 * @note Runs in the extension service worker context.
 */
async function onAlarm(alarm) {
  console.log(`[background] onAlarm → name="${alarm.name}"`);

  if (alarm.name === 'scrapeCycle') {
    // cycleInProgress is read from chrome.storage.local (NOT in-memory) so
    // this guard survives service worker restarts between alarm fires.
    const inProgress = await getCycleInProgress();
    if (inProgress) {
      console.log('[background] Scrape cycle already in progress — skipping alarm.');
      return;
    }

    // Phase 7: await runScrapeCycle();
    console.log('[background] scrapeCycle alarm fired — full handler wired in Phase 7.');
  }
}

chrome.alarms.onAlarm.addListener(onAlarm);

// ─── Startup health check ────────────────────────────────────────────────────

/**
 * Runs once each time the service worker starts (including restarts).
 * Pings the backend so the UI can show a "backend unreachable" warning early.
 *
 * @returns {Promise<void>}
 * @note Runs in the extension service worker context.
 */
async function runStartupChecks() {
  console.log('[background] Service worker started — running startup checks.');
  await checkHealth();
}

runStartupChecks();
