/**
 * options.js — Options Page Controller
 *
 * Purpose : Handles user interaction on options.html. Validates and persists
 *           the contest URL and scrape interval to chrome.storage.local, then
 *           notifies the background service worker (discovery and/or alarm
 *           re-registration).
 *
 * Author  : Extension/Background Agent
 * Phase   : 7 (interval config)
 *
 * Context : Runs in the extension Options Page renderer context (NOT the
 *           service worker). DOM APIs are available. chrome.* APIs available
 *           through the extension bridge.
 */

'use strict';

// ─── Constants ────────────────────────────────────────────────────────────────

/** Required URL prefix — any contest URL must begin with this string. */
const CONTEST_URL_PREFIX = 'https://leetcode.com/contest/';

/** chrome.storage.local key used to persist the contest URL. */
const STORAGE_KEY_CONTEST_URL = 'contestUrl';

/** chrome.storage.local key used to persist the scrape interval (minutes). */
const STORAGE_KEY_INTERVAL = 'monitoringIntervalMinutes';

/** Matches alarmScheduler.js — Chrome alarms floor at 1 minute (ADR-020). */
const MIN_PERIOD_MINUTES = 1;
const MAX_PERIOD_MINUTES = 60;
const DEFAULT_PERIOD_MINUTES = 5;

// ─── DOM references (resolved after DOMContentLoaded) ────────────────────────

let inputEl;
let intervalEl;
let saveBtnEl;
let statusMsgEl;

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Displays a status message below the Save button.
 *
 * Clears any previous message class and applies the appropriate CSS class
 * ('success' or 'error') so the user receives colour-coded feedback.
 *
 * @param {string}  text    Message to display. Pass '' to clear.
 * @param {'success'|'error'} [type='success']  Visual style.
 * @returns {void}
 */
function showStatus(text, type = 'success') {
  statusMsgEl.textContent = text;
  statusMsgEl.className   = text ? type : '';
}

/**
 * Validates the contest URL entered by the user.
 *
 * Rules:
 *   1. Must not be empty.
 *   2. Must start with `https://leetcode.com/contest/`.
 *   3. Must be a structurally valid URL (parseable by the URL constructor).
 *
 * @param {string} rawUrl  The raw string from the input element.
 * @returns {{ valid: boolean, error?: string, url?: string }}
 *   On success: `{ valid: true, url: normalised URL string }`
 *   On failure: `{ valid: false, error: human-readable message }`
 */
function validateContestUrl(rawUrl) {
  const trimmed = (rawUrl ?? '').trim();

  if (!trimmed) {
    return { valid: false, error: 'Please enter a contest URL.' };
  }

  if (!trimmed.startsWith(CONTEST_URL_PREFIX)) {
    return {
      valid: false,
      error: `URL must start with "${CONTEST_URL_PREFIX}".`,
    };
  }

  try {
    const parsed = new URL(trimmed);
    return { valid: true, url: parsed.href };
  } catch {
    return { valid: false, error: 'That does not look like a valid URL.' };
  }
}

/**
 * Validates the scrape interval from the options number input.
 *
 * @param {string|number} raw
 * @returns {{ valid: boolean, error?: string, periodMinutes?: number }}
 */
function validateInterval(raw) {
  const n = Number(raw);
  if (!Number.isFinite(n) || !Number.isInteger(n)) {
    return { valid: false, error: 'Interval must be a whole number of minutes.' };
  }
  if (n < MIN_PERIOD_MINUTES || n > MAX_PERIOD_MINUTES) {
    return {
      valid: false,
      error: `Interval must be between ${MIN_PERIOD_MINUTES} and ${MAX_PERIOD_MINUTES} minutes.`,
    };
  }
  return { valid: true, periodMinutes: n };
}

// ─── Core handlers ────────────────────────────────────────────────────────────

/**
 * Loads the previously saved contest URL and scrape interval from storage.
 *
 * @returns {Promise<void>}
 */
async function loadSavedUrl() {
  try {
    const result = await chrome.storage.local.get([
      STORAGE_KEY_CONTEST_URL,
      STORAGE_KEY_INTERVAL,
    ]);
    if (result[STORAGE_KEY_CONTEST_URL]) {
      inputEl.value = result[STORAGE_KEY_CONTEST_URL];
    }
    const storedInterval = result[STORAGE_KEY_INTERVAL];
    intervalEl.value = Number.isFinite(Number(storedInterval))
      ? String(storedInterval)
      : String(DEFAULT_PERIOD_MINUTES);
  } catch (err) {
    console.error('[options] Failed to load saved settings:', err);
  }
}

/**
 * Handles the Save button click event.
 *
 * Steps:
 *   1. Validate URL and interval.
 *   2. Persist both to chrome.storage.local.
 *   3. Notify the SW: CONTEST_URL_SAVED only when the URL actually changed
 *      (so a interval-only save does not re-run discovery).
 *   4. Always notify MONITORING_INTERVAL_SAVED so scrapeCycle is re-registered
 *      when monitoring is already active.
 *
 * @param {MouseEvent} _event  Click event (unused but required by addEventListener).
 * @returns {Promise<void>}
 * @sideeffects Writes contestUrl + monitoringIntervalMinutes; sends runtime messages.
 */
async function handleSave(_event) {
  showStatus('');

  const { valid, error, url } = validateContestUrl(inputEl.value);
  if (!valid) {
    showStatus(error, 'error');
    return;
  }

  const intervalResult = validateInterval(intervalEl.value);
  if (!intervalResult.valid) {
    showStatus(intervalResult.error, 'error');
    return;
  }
  const periodMinutes = intervalResult.periodMinutes;

  let previousUrl = null;
  try {
    const prev = await chrome.storage.local.get(STORAGE_KEY_CONTEST_URL);
    previousUrl = prev[STORAGE_KEY_CONTEST_URL] ?? null;
  } catch (err) {
    console.warn('[options] Could not read previous URL:', err);
  }

  try {
    await chrome.storage.local.set({
      [STORAGE_KEY_CONTEST_URL]: url,
      [STORAGE_KEY_INTERVAL]: periodMinutes,
    });
    console.log('[options] Settings saved:', { url, periodMinutes });
  } catch (err) {
    console.error('[options] storage.local.set failed:', err);
    showStatus('Failed to save — storage error.', 'error');
    return;
  }

  const urlChanged = url !== previousUrl;
  if (urlChanged) {
    try {
      const response = await chrome.runtime.sendMessage({
        type: 'CONTEST_URL_SAVED',
        contestUrl: url,
      });
      console.log('[options] Background acknowledged CONTEST_URL_SAVED:', response);
    } catch (err) {
      console.warn('[options] sendMessage CONTEST_URL_SAVED failed (SW may be asleep):', err.message);
    }
  }

  try {
    const response = await chrome.runtime.sendMessage({
      type: 'MONITORING_INTERVAL_SAVED',
      periodMinutes,
    });
    console.log('[options] Background acknowledged MONITORING_INTERVAL_SAVED:', response);
  } catch (err) {
    console.warn('[options] sendMessage MONITORING_INTERVAL_SAVED failed (SW may be asleep):', err.message);
  }

  showStatus('✓ Saved successfully!', 'success');
}

/**
 * Handles the "Open Side Panel" button click.
 *
 * Sends a message to the background asking it to open the side panel for
 * the current tab. Falls back to a console warning if the API is unavailable.
 *
 * @param {MouseEvent} event
 * @returns {Promise<void>}
 */
async function handleOpenSidePanel(event) {
  event.preventDefault();
  try {
    await chrome.runtime.sendMessage({ type: 'OPEN_SIDE_PANEL' });
  } catch (err) {
    console.warn('[options] Could not open side panel:', err.message);
  }
}

// ─── Initialisation ───────────────────────────────────────────────────────────

/**
 * Entry point — called once the DOM is fully parsed.
 *
 * Binds DOM references, attaches event listeners, and loads any previously
 * saved contest URL from storage.
 *
 * @returns {void}
 */
function init() {
  inputEl     = document.getElementById('contestUrlInput');
  intervalEl  = document.getElementById('intervalInput');
  saveBtnEl   = document.getElementById('saveBtn');
  statusMsgEl = document.getElementById('statusMsg');

  saveBtnEl.addEventListener('click', handleSave);

  const openSidePanelEl = document.getElementById('openSidePanel');
  if (openSidePanelEl) {
    openSidePanelEl.addEventListener('click', handleOpenSidePanel);
  }

  // Allow pressing Enter in the input to trigger save.
  inputEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') saveBtnEl.click();
  });

  loadSavedUrl();
}

document.addEventListener('DOMContentLoaded', init);
