/**
 * sidepanel.js — Dashboard (Phase 10)
 *
 * Purpose : Poll GET /api/contest/status every 5s and render questions,
 *           ranking, and recent overtakes. Surface backend-unreachable
 *           (ADR-009) without crashing. Empty/uninitialized is a clear
 *           empty state, not a blank error.
 *
 * Context : Extension side-panel page. Imports backendClient (same fetch
 *           contract as the service worker).
 */

import { getStatus } from '../background/backendClient.js';

const POLL_MS = 5000;

const backendBanner = document.getElementById('backendBanner');
const emptyBanner = document.getElementById('emptyBanner');
const endedBanner = document.getElementById('endedBanner');
const contestUrlLabel = document.getElementById('contestUrlLabel');
const lifecycleLabel = document.getElementById('lifecycleLabel');
const updatedLabel = document.getElementById('updatedLabel');
const questionsRoot = document.getElementById('questionsRoot');
const rankingRoot = document.getElementById('rankingRoot');
const changesRoot = document.getElementById('changesRoot');

let pollTimer = null;

/**
 * Shows or hides a banner element.
 *
 * @param {HTMLElement} el
 * @param {boolean} visible
 */
function setBanner(el, visible) {
  el.classList.toggle('visible', visible);
}

/**
 * Formats a percentage BigDecimal/number for display. Never uses the value
 * for ranking math — display only.
 *
 * @param {string|number|null|undefined} value
 * @returns {string}
 */
function formatPercentage(value) {
  if (value == null || value === '') {
    return '—';
  }
  const n = Number(value);
  if (!Number.isFinite(n)) {
    return String(value);
  }
  return `${n.toFixed(2)}%`;
}

/**
 * @param {object|null} snapshot
 * @param {boolean} unreachable
 */
function render(snapshot, unreachable) {
  setBanner(backendBanner, unreachable);

  const initialized = Boolean(snapshot && snapshot.initialized);
  setBanner(emptyBanner, !unreachable && !initialized);
  setBanner(endedBanner, initialized && snapshot.lifecycleState === 'ENDED');

  if (!initialized) {
    contestUrlLabel.textContent = 'Waiting for contest configuration…';
    lifecycleLabel.textContent = 'lifecycle: —';
    updatedLabel.textContent = 'updated: —';
    questionsRoot.className = 'empty';
    questionsRoot.textContent = 'No question data yet.';
    rankingRoot.className = 'rank-list empty';
    rankingRoot.innerHTML = '<li>Ranking appears after the first successful ingest.</li>';
    changesRoot.className = 'change-list empty';
    changesRoot.innerHTML = '<li>No overtakes yet.</li>';
    return;
  }

  contestUrlLabel.textContent = snapshot.contestUrl || 'Contest configured';
  lifecycleLabel.textContent = `lifecycle: ${snapshot.lifecycleState ?? '—'}`;
  updatedLabel.textContent = `updated: ${snapshot.lastUpdated ?? '—'}`;

  const questions = Array.isArray(snapshot.questions) ? snapshot.questions : [];
  if (questions.length === 0) {
    questionsRoot.className = 'empty';
    questionsRoot.textContent = 'No question data yet.';
  } else {
    questionsRoot.className = '';
    questionsRoot.innerHTML = questions.map((q) => {
      const status = q.scrapingStatus ?? 'not scraped';
      const err = q.errorMessage ? ` — ${escapeHtml(q.errorMessage)}` : '';
      return `<article class="question">
        <span class="slot">${escapeHtml(q.questionNumber ?? '?')}</span>
        <span class="name">${escapeHtml(q.problemName ?? '')}</span>
        <span class="pct">${escapeHtml(formatPercentage(q.usersAcceptedPercentage))}</span>
        <span class="status">${escapeHtml(status)}${err}</span>
      </article>`;
    }).join('');
  }

  const ranking = Array.isArray(snapshot.ranking) ? snapshot.ranking : [];
  if (ranking.length === 0) {
    rankingRoot.className = 'rank-list empty';
    rankingRoot.innerHTML = '<li>Ranking appears after the first successful ingest.</li>';
  } else {
    rankingRoot.className = 'rank-list';
    rankingRoot.innerHTML = ranking.map((slot, i) =>
      `<li>${i + 1}. ${escapeHtml(slot)}</li>`
    ).join('');
  }

  const changes = Array.isArray(snapshot.recentChanges) ? snapshot.recentChanges : [];
  if (changes.length === 0) {
    changesRoot.className = 'change-list empty';
    changesRoot.innerHTML = '<li>No overtakes yet.</li>';
  } else {
    changesRoot.className = 'change-list';
    changesRoot.innerHTML = changes.map((c) =>
      `<li>${escapeHtml(c.description ?? 'overtake')} <span class="status">${escapeHtml(c.timestamp ?? '')}</span></li>`
    ).join('');
  }
}

/**
 * @param {string} text
 * @returns {string}
 */
function escapeHtml(text) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

/**
 * One poll tick. getStatus returns null on unreachable / HTTP error.
 *
 * @returns {Promise<void>}
 */
async function refresh() {
  try {
    const snapshot = await getStatus();
    if (snapshot == null) {
      render(null, true);
      return;
    }
    render(snapshot, false);
    if (snapshot.lifecycleState === 'ENDED') {
      try {
        await chrome.runtime.sendMessage({ type: 'CONTEST_ENDED', source: 'sidepanel-status' });
      } catch (err) {
        console.warn('[sidepanel] CONTEST_ENDED notify failed:', err?.message);
      }
    }
  } catch (err) {
    console.warn('[sidepanel] refresh failed:', err);
    render(null, true);
  }
}

/**
 * Entry: immediate refresh + 5s poll. Also watches backendUnreachable storage.
 */
async function init() {
  await refresh();
  pollTimer = setInterval(refresh, POLL_MS);

  if (chrome?.storage?.onChanged) {
    chrome.storage.onChanged.addListener((changes, area) => {
      if (area === 'local' && changes.backendUnreachable) {
        refresh();
      }
    });
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}

export { formatPercentage, render, POLL_MS };
