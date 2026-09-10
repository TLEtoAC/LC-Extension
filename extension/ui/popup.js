/**
 * popup.js — Lightweight ranking fallback (Phase 10)
 *
 * Purpose : One-shot status fetch for the toolbar popup. Surfaces
 *           backend-unreachable and uninitialized empty state. Full
 *           dashboard lives in the side panel.
 */

import { getStatus } from '../background/backendClient.js';
import { openSidePanelFromClick } from './openSidePanel.js';

const backendBanner = document.getElementById('backendBanner');
const emptyBanner = document.getElementById('emptyBanner');
const summaryLabel = document.getElementById('summaryLabel');
const rankingRoot = document.getElementById('rankingRoot');

/**
 * @param {HTMLElement} el
 * @param {boolean} visible
 */
function setBanner(el, visible) {
  el.classList.toggle('visible', visible);
}

/**
 * @returns {Promise<void>}
 */
async function refresh() {
  try {
    const snapshot = await getStatus();
    if (snapshot == null) {
      setBanner(backendBanner, true);
      setBanner(emptyBanner, false);
      summaryLabel.textContent = 'Backend not running';
      rankingRoot.innerHTML = '<li>Unavailable</li>';
      return;
    }

    setBanner(backendBanner, false);
    const initialized = Boolean(snapshot.initialized);
    setBanner(emptyBanner, !initialized);

    if (!initialized) {
      summaryLabel.textContent = 'No contest configured';
      rankingRoot.innerHTML = '<li>Save a contest URL in Options</li>';
      return;
    }

    summaryLabel.textContent = `${snapshot.lifecycleState ?? 'MONITORING'} · ${snapshot.lastUpdated ?? ''}`;
    const ranking = Array.isArray(snapshot.ranking) ? snapshot.ranking : [];
    rankingRoot.className = ranking.length ? 'rank-list' : 'rank-list empty';
    rankingRoot.innerHTML = ranking.length
      ? ranking.map((slot, i) => `<li>${i + 1}. ${slot}</li>`).join('')
      : '<li>Waiting for first ingest</li>';

    if (snapshot.lifecycleState === 'ENDED') {
      try {
        await chrome.runtime.sendMessage({ type: 'CONTEST_ENDED', source: 'popup-status' });
      } catch (err) {
        console.warn('[popup] CONTEST_ENDED notify failed:', err?.message);
      }
    }
  } catch (err) {
    console.warn('[popup] refresh failed:', err);
    setBanner(backendBanner, true);
    summaryLabel.textContent = 'Backend not running';
  }
}

document.getElementById('openSidePanel')?.addEventListener('click', (event) => {
  event.preventDefault();
  openSidePanelFromClick().catch((err) => {
    console.warn('[popup] sidePanel.open failed:', err?.message);
  });
});

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', refresh);
} else {
  refresh();
}
