/**
 * options.js — Options Page Controller
 *
 * Purpose : Handles user interaction on options.html. Validates and persists
 *           the contest URL to chrome.storage.local, then notifies the
 *           background service worker so it can kick off tab discovery
 *           (Phase 3).
 *
 * Author  : Extension/Background Agent
 * Phase   : 2 (scaffold + full implementation)
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

// ─── DOM references (resolved after DOMContentLoaded) ────────────────────────

let inputEl;
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

// ─── Core handlers ────────────────────────────────────────────────────────────

/**
 * Loads the previously saved contest URL from storage and populates the input.
 *
 * Called once on page load. If no URL is stored the input is left blank.
 *
 * @returns {Promise<void>}
 */
async function loadSavedUrl() {
  try {
    const result = await chrome.storage.local.get(STORAGE_KEY_CONTEST_URL);
    if (result[STORAGE_KEY_CONTEST_URL]) {
      inputEl.value = result[STORAGE_KEY_CONTEST_URL];
    }
  } catch (err) {
    console.error('[options] Failed to load saved URL:', err);
  }
}

/**
 * Handles the Save button click event.
 *
 * Steps:
 *   1. Validate the URL.
 *   2. Persist to chrome.storage.local.
 *   3. Notify the background service worker via chrome.runtime.sendMessage
 *      (type: CONTEST_URL_SAVED). Phase 3 wires the handler.
 *   4. Display success or error feedback.
 *
 * @param {MouseEvent} _event  Click event (unused but required by addEventListener).
 * @returns {Promise<void>}
 * @sideeffects Writes to chrome.storage.local key "contestUrl"; sends a
 *              runtime message to the background service worker.
 */
async function handleSave(_event) {
  showStatus('');

  const { valid, error, url } = validateContestUrl(inputEl.value);

  if (!valid) {
    showStatus(error, 'error');
    return;
  }

  try {
    await chrome.storage.local.set({ [STORAGE_KEY_CONTEST_URL]: url });
    console.log('[options] Contest URL saved:', url);
  } catch (err) {
    console.error('[options] storage.local.set failed:', err);
    showStatus('Failed to save — storage error.', 'error');
    return;
  }

  // Notify the background service worker. The handler is a stub in Phase 2
  // and will be completed in Phase 3 (tab discovery).
  try {
    const response = await chrome.runtime.sendMessage({
      type: 'CONTEST_URL_SAVED',
      contestUrl: url,
    });
    console.log('[options] Background acknowledged CONTEST_URL_SAVED:', response);
  } catch (err) {
    // The service worker may have been suspended. This is non-fatal — the URL
    // is already persisted; the SW will pick it up on next wake.
    console.warn('[options] sendMessage to background failed (SW may be asleep):', err.message);
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
  inputEl    = document.getElementById('contestUrlInput');
  saveBtnEl  = document.getElementById('saveBtn');
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
