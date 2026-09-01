/**
 * contestPageScript.js — Contest Homepage Problem Discovery Content Script
 *
 * Purpose : Executes within the context of a LeetCode contest homepage tab to
 *           discover the 4 contest problem entries, extract their URLs via
 *           href-first or click-and-capture fallback, extract their titles,
 *           and assign Q1–Q4 in DOM order before notifying the service worker.
 *
 * Author  : Content Script/DOM Agent
 * Phase   : 3 — Discovery
 *
 * Context : Runs exclusively in the Chrome Content Script context (isolated DOM world).
 *           Matches URLs: https://leetcode.com/contest/*
 *           Does NOT run in service worker context (no chrome.alarms/background APIs).
 *
 * Section Rules Compliance:
 *   - Query Problem List for exactly 4 entries — never hardcode URLs, names, or slugs.
 *   - href-first extraction resolved against window.location.origin.
 *   - Click-and-capture fallback via background messaging if no href is present.
 *   - Uses MutationObserver to wait for DOM rendering (no polling setTimeout loops).
 *   - Dispatches DISCOVERY_COMPLETE or DISCOVERY_FAILED to background.js.
 *   - Discovery only — does NOT parse acceptance stats.
 *
 * DOM Assumptions (documented for DECISIONS.md):
 *   1. The contest homepage renders a Problem List container containing exactly 4
 *      problem row/item elements (e.g. <tr> in table tbody, <li> in question list,
 *      or role="listitem" / role="row").
 *   2. Each entry contains an anchor <a> pointing to the problem URL, or represents
 *      a clickable row/card navigating to the problem page.
 *   3. Entries are rendered in sequential problem order corresponding to Q1, Q2, Q3, Q4.
 *   4. The problem title is the primary text content of the link/entry element.
 *   5. LeetCode is a React SPA where the problem list may render asynchronously after
 *      the initial HTML document_idle event.
 */

(() => {
  'use strict';

  // ─── Configuration Constants ───────────────────────────────────────────────

  /** Maximum time (ms) to wait for Problem List DOM rendering before failing. */
  const DISCOVERY_TIMEOUT_MS = 15000;

  /** Maximum time (ms) to wait for click-and-capture round-trip before timing out. */
  const CLICK_CAPTURE_TIMEOUT_MS = 10000;

  // ─── Module State ──────────────────────────────────────────────────────────

  /** @type {boolean} Indicates whether discovery has successfully completed. */
  let isDiscovered = false;

  /** @type {boolean} Indicates whether discovery extraction is currently active. */
  let isDiscovering = false;

  /** @type {MutationObserver|null} MutationObserver instance for DOM tracking. */
  let domObserver = null;

  /** @type {number|null} Timeout ID for overall discovery timeout. */
  let discoveryTimeoutId = null;

  // ─── URL Validation ────────────────────────────────────────────────────────

  /**
   * Checks whether the current page is a specific contest homepage (e.g. /contest/weekly-contest-380/)
   * and NOT a problem page (/contest/.../problems/...) or ranking index.
   *
   * @returns {boolean} True if the current page is a valid contest homepage.
   */
  function isTargetContestPage() {
    const pathname = window.location.pathname;

    // Guard: exclude problem subpages (handled by problemPageScript.js)
    if (pathname.includes('/problems/')) {
      return false;
    }

    // Guard: exclude ranking subpages
    if (pathname.includes('/ranking/')) {
      return false;
    }

    // Match /contest/<contest-slug> or /contest/<contest-slug>/
    const match = pathname.match(/^\/contest\/([^/]+)\/?$/);
    if (!match) {
      return false;
    }

    const slug = match[1].toLowerCase();
    // Exclude root contest portal or general index pages
    if (!slug || slug === 'ranking' || slug === 'globalranking') {
      return false;
    }

    return true;
  }

  // ─── DOM Discovery Strategies ──────────────────────────────────────────────

  /**
   * Evaluates multiple DOM selector strategies to find exactly 4 problem entry elements.
   * Does NOT hardcode problem names, slugs, or URL patterns.
   *
   * @returns {{ strategy: string, elements: HTMLElement[] } | null} Found candidate entries or null.
   */
  function findCandidateProblemElements() {
    // Strategy 1: Dedicated contest question list items
    const listSelectors = [
      'ul.contest-question-list > li',
      'ul.question-list > li',
      'ol.contest-question-list > li',
      'ol.question-list > li',
      'ul.list-group > li.list-group-item',
      'ul.list-group > li'
    ];

    for (const selector of listSelectors) {
      const items = Array.from(document.querySelectorAll(selector)).filter((el) => {
        const text = el.textContent ? el.textContent.trim() : '';
        return text.length > 0;
      });
      if (items.length === 4) {
        return { strategy: `list-items (${selector})`, elements: items };
      }
    }

    // Strategy 2: Table rows inside contest problem table
    const tableSelectors = [
      'table.table tbody > tr',
      'table.table-hover tbody > tr',
      'table[class*="contest"] tbody > tr',
      'table[class*="question"] tbody > tr',
      'table tbody > tr'
    ];

    for (const selector of tableSelectors) {
      const rows = Array.from(document.querySelectorAll(selector)).filter((tr) => {
        // Exclude header rows containing <th> or empty rows
        const hasTh = tr.querySelector('th') !== null;
        const hasTd = tr.querySelectorAll('td').length > 0;
        return !hasTh && hasTd;
      });
      if (rows.length === 4) {
        return { strategy: `table-tbody-rows (${selector})`, elements: rows };
      }
    }

    // Strategy 3: Text-anchored container discovery (e.g. following "Problem List" heading)
    const headings = Array.from(
      document.querySelectorAll('h1, h2, h3, h4, h5, h6, th, div, span, p')
    ).filter((el) => {
      const text = el.textContent ? el.textContent.trim() : '';
      return /^(?:problem list|contest problems|problems|questions)$/i.test(text);
    });

    for (const heading of headings) {
      const container = heading.closest('section, div, .panel, .card') || heading.parentElement;
      if (container) {
        // Look for 4 table rows or 4 list items or 4 links within this container
        const rows = Array.from(container.querySelectorAll('tbody > tr')).filter(
          (tr) => !tr.querySelector('th') && tr.querySelectorAll('td').length > 0
        );
        if (rows.length === 4) {
          return { strategy: 'text-anchored-heading-table-rows', elements: rows };
        }

        const items = Array.from(container.querySelectorAll('li')).filter(
          (li) => (li.textContent || '').trim().length > 0
        );
        if (items.length === 4) {
          return { strategy: 'text-anchored-heading-list-items', elements: items };
        }
      }
    }

    // Strategy 4: Role-based rows or list items
    const roleRowSelectors = [
      '[role="rowgroup"] > [role="row"]',
      '[role="table"] [role="row"]',
      '[role="grid"] [role="row"]'
    ];

    for (const selector of roleRowSelectors) {
      const rows = Array.from(document.querySelectorAll(selector)).filter((el) => {
        const isHeader = el.getAttribute('role') === 'columnheader' || el.querySelector('[role="columnheader"]');
        return !isHeader && (el.textContent || '').trim().length > 0;
      });
      if (rows.length === 4) {
        return { strategy: `role-rows (${selector})`, elements: rows };
      }
    }

    const roleListItems = Array.from(document.querySelectorAll('[role="list"] > [role="listitem"]')).filter(
      (el) => (el.textContent || '').trim().length > 0
    );
    if (roleListItems.length === 4) {
      return { strategy: 'role-listitem', elements: roleListItems };
    }

    // Strategy 5: Container with exactly 4 anchor links
    const contestContainer = document.querySelector('.contest-question-list, .question-list, #contest-problems');
    if (contestContainer) {
      const anchors = Array.from(contestContainer.querySelectorAll('a')).filter((a) => {
        const text = (a.textContent || '').trim();
        return text.length > 0 && a.getAttribute('href') !== '#';
      });
      if (anchors.length === 4) {
        return { strategy: 'contest-container-anchors', elements: anchors };
      }
    }

    return null;
  }

  // ─── Entry Extraction Helpers ──────────────────────────────────────────────

  /**
   * Extracts the href from a candidate problem element using href-first strategy.
   * Resolves relative URLs against window.location.origin.
   *
   * @param {HTMLElement} element - The DOM element representing a problem entry.
   * @returns {string|null} The resolved absolute URL, or null if no valid href is present.
   */
  function extractHref(element) {
    let link = null;

    if (element.tagName && element.tagName.toLowerCase() === 'a' && element.hasAttribute('href')) {
      link = element;
    } else {
      link = element.querySelector('a[href]') || element.closest('a[href]');
    }

    if (!link) {
      return null;
    }

    const rawHref = link.getAttribute('href');
    if (!rawHref || rawHref === '#' || rawHref.startsWith('javascript:')) {
      return null;
    }

    try {
      return new URL(rawHref, window.location.origin).href;
    } catch (e) {
      console.warn('[contestPageScript] Failed to resolve URL from href:', rawHref, e);
      return null;
    }
  }

  /**
   * Extracts and cleans the human-readable problem title from the entry DOM element.
   *
   * @param {HTMLElement} element - The DOM element representing a problem entry.
   * @returns {string} Cleaned problem title.
   */
  function extractProblemName(element) {
    const link = element.tagName && element.tagName.toLowerCase() === 'a' ? element : element.querySelector('a');
    const target = link || element;

    // Use innerText if available to preserve layout/line breaks, fallback to textContent
    const rawText = (target.innerText || target.textContent || '').trim();
    if (!rawText) {
      return 'Untitled Problem';
    }

    // If multi-line, take the first non-empty line (titles are typically on the first line)
    const firstLine = rawText.split('\n').map((s) => s.trim()).filter(Boolean)[0] || rawText;

    // Strip leading question index/prefix (e.g., "1.", "Q1.", "1 -", "1: ")
    const cleaned = firstLine.replace(/^\s*(?:Q\d+|[0-9]+)[\.\:\s\-]+\s*/i, '').trim();
    return cleaned || firstLine;
  }

  // ─── Click-and-Capture Fallback ────────────────────────────────────────────

  /**
   * Performs the click-and-capture fallback for an entry when href-first extraction fails.
   * Communicates with background.js to capture the resulting URL.
   *
   * @param {HTMLElement} element - The DOM element to trigger or coordinate.
   * @param {number} entryIndex - 0-based index of the entry (0..3).
   * @returns {Promise<string>} Resolves with the captured problem URL.
   */
  function performClickAndCapture(element, entryIndex) {
    return new Promise((resolve, reject) => {
      let settled = false;

      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        cleanup();
        console.error(`[contestPageScript] Click-and-capture timed out (> 10s) for entry index ${entryIndex}`);
        reject(new Error(`CLICK_CAPTURE_TIMEOUT: Entry ${entryIndex}`));
      }, CLICK_CAPTURE_TIMEOUT_MS);

      /**
       * Message listener for CLICK_AND_CAPTURE_RESULT from background.
       *
       * @param {object} message
       */
      function onCaptureMessage(message) {
        if (settled) return;
        if (
          message?.type === 'CLICK_AND_CAPTURE_RESULT' &&
          (message.entryIndex === entryIndex || message.entryIndex === undefined)
        ) {
          if (message.problemUrl) {
            settled = true;
            cleanup();
            resolve(message.problemUrl);
          }
        }
      }

      function cleanup() {
        clearTimeout(timer);
        chrome.runtime.onMessage.removeListener(onCaptureMessage);
      }

      chrome.runtime.onMessage.addListener(onCaptureMessage);

      console.log(`[contestPageScript] Sending CLICK_AND_CAPTURE_START for entry index ${entryIndex}`);
      chrome.runtime.sendMessage(
        {
          type: 'CLICK_AND_CAPTURE_START',
          entryIndex: entryIndex
        },
        (response) => {
          if (chrome.runtime.lastError) {
            console.warn(
              `[contestPageScript] runtime.lastError on CLICK_AND_CAPTURE_START (entry ${entryIndex}):`,
              chrome.runtime.lastError.message
            );
          }
          if (response?.type === 'CLICK_AND_CAPTURE_RESULT' && response.problemUrl) {
            if (!settled) {
              settled = true;
              cleanup();
              resolve(response.problemUrl);
            }
          }
        }
      );
    });
  }

  // ─── Discovery Processing ──────────────────────────────────────────────────

  /**
   * Processes the 4 discovered candidate entries, extracts problem titles and URLs,
   * assigns Q1..Q4, and sends DISCOVERY_COMPLETE or DISCOVERY_FAILED to background.
   *
   * @param {HTMLElement[]} elements - Array of exactly 4 DOM elements.
   * @param {string} strategyName - The selector strategy that located the elements.
   * @returns {Promise<boolean>} True if discovery succeeded and was dispatched.
   */
  async function processCandidateEntries(elements, strategyName) {
    const questionLabels = ['Q1', 'Q2', 'Q3', 'Q4'];
    const questions = [];

    console.log(`[contestPageScript] Processing 4 candidate entries using strategy "${strategyName}"`);

    for (let i = 0; i < 4; i++) {
      const entry = elements[i];
      const questionNumber = questionLabels[i];
      const problemName = extractProblemName(entry);

      let problemUrl = extractHref(entry);

      if (problemUrl) {
        console.log(
          `[contestPageScript] href-first succeeded for entry ${i + 1} (${questionNumber}) [${strategyName}]: ${problemUrl}`
        );
      } else {
        console.log(
          `[contestPageScript] href-first not found for entry ${i + 1} (${questionNumber}) — initiating click-and-capture fallback`
        );
        try {
          problemUrl = await performClickAndCapture(entry, i);
          console.log(
            `[contestPageScript] click-and-capture fallback used for entry ${i + 1} (${questionNumber}): ${problemUrl}`
          );
        } catch (err) {
          console.error(
            `[contestPageScript] Discovery failed during click-and-capture for entry ${i + 1} (${questionNumber}):`,
            err
          );
          chrome.runtime.sendMessage({
            type: 'DISCOVERY_FAILED',
            reason: 'CLICK_CAPTURE_TIMEOUT',
            entryIndex: i
          });
          return false;
        }
      }

      questions.push({
        questionNumber,
        problemName,
        problemUrl
      });
    }

    console.log('[contestPageScript] All 4 contest problems successfully discovered:', questions);

    chrome.runtime.sendMessage({
      type: 'DISCOVERY_COMPLETE',
      questions: questions
    });

    return true;
  }

  /**
   * Attempts a discovery cycle. Queries DOM for 4 entries and executes processing.
   *
   * @returns {Promise<void>}
   */
  async function attemptDiscovery() {
    if (isDiscovered || isDiscovering) {
      return;
    }

    const candidateResult = findCandidateProblemElements();
    if (!candidateResult || candidateResult.elements.length !== 4) {
      return;
    }

    isDiscovering = true;

    try {
      const success = await processCandidateEntries(candidateResult.elements, candidateResult.strategy);
      if (success) {
        isDiscovered = true;
        stopDiscoveryObserver();
      } else {
        isDiscovering = false;
      }
    } catch (err) {
      console.error('[contestPageScript] Unexpected error during attemptDiscovery:', err);
      isDiscovering = false;
    }
  }

  // ─── Lifecycle & Observer Management ───────────────────────────────────────

  /**
   * Stops the MutationObserver and cancels the overall discovery timeout.
   *
   * @returns {void}
   */
  function stopDiscoveryObserver() {
    if (domObserver) {
      domObserver.disconnect();
      domObserver = null;
    }
    if (discoveryTimeoutId !== null) {
      clearTimeout(discoveryTimeoutId);
      discoveryTimeoutId = null;
    }
  }

  /**
   * Initializes the discovery flow using MutationObserver to wait for DOM elements.
   *
   * @returns {void}
   */
  function startDiscovery() {
    if (!isTargetContestPage()) {
      console.log(`[contestPageScript] Skipping discovery — not a specific contest homepage (${window.location.pathname})`);
      return;
    }

    console.log(`[contestPageScript] Initialized on contest homepage: ${window.location.href}`);

    // Immediate probe
    attemptDiscovery();
    if (isDiscovered) {
      return;
    }

    // Setup MutationObserver to wait for React rendering
    domObserver = new MutationObserver(() => {
      if (!isDiscovered && !isDiscovering) {
        attemptDiscovery();
      }
    });

    const targetNode = document.body || document.documentElement;
    domObserver.observe(targetNode, {
      childList: true,
      subtree: true
    });

    // Overall timeout fallback
    discoveryTimeoutId = setTimeout(() => {
      if (isDiscovered || isDiscovering) {
        return;
      }

      stopDiscoveryObserver();

      const candidateResult = findCandidateProblemElements();
      const foundCount = candidateResult ? candidateResult.elements.length : 0;

      console.error(`[contestPageScript] Discovery timed out after ${DISCOVERY_TIMEOUT_MS}ms. Found ${foundCount} entries (expected 4).`);

      chrome.runtime.sendMessage({
        type: 'DISCOVERY_FAILED',
        reason: 'INSUFFICIENT_ENTRIES',
        found: foundCount
      });
    }, DISCOVERY_TIMEOUT_MS);
  }

  // ─── Entry Point Execution ─────────────────────────────────────────────────

  startDiscovery();
})();
