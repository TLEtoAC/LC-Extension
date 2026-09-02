/**
 * problemPageScript.js — Problem Page DOM Scraping Content Script
 *
 * Purpose : Executes within the context of a LeetCode contest problem tab
 *           (https://leetcode.com/contest/<contest-slug>/problems/<problem-slug>/)
 *           to scrape the raw "Users Accepted" statistics string, perform a
 *           double-read stability check, detect login walls, and transmit the
 *           unparsed raw payload to the extension service worker.
 *
 * Author  : Content Script/DOM Agent
 * Phase   : 4 — Problem Page DOM Scraping
 *
 * Context : Runs exclusively in the Chrome Content Script context (isolated DOM world).
 *           Matches URLs: https://leetcode.com/contest/*/problems/*
 *           Manifest V3 compliant — pure browser JavaScript (no Node.js APIs).
 *
 * Section Rules Compliance:
 *   - MutationObserver waits for "Users Accepted" element (never polls with setTimeout loops).
 *   - Double-read stability check: two readings 300ms apart must match before acceptance.
 *   - Login-wall detection: reports scrapingStatus: "LOGIN_WALL" immediately on form/modal detection.
 *   - Selector fallback chain (in priority order):
 *       1. Text-anchored: find element containing text "Users Accepted", read sibling/child value.
 *       2. Attribute-based: [data-*] or class attributes relating to submission/acceptance stats.
 *       3. Aria-label: [aria-label] containing "users accepted" or "accepted users".
 *   - Raw string transmitted as-is (e.g. "28,903 / 31.1K") — NEVER parsed in JS (Java backend parses).
 *   - On timeout (15s), reports scrapingStatus: "SELECTOR_NOT_FOUND".
 *   - Strict logging with prefix "[problemPageScript]".
 *
 * DOM Assumptions (documented for DECISIONS.md):
 *   1. LeetCode renders problem stats asynchronously via React hydration / GraphQL fetch.
 *   2. "Users Accepted" statistics render as a composite string (e.g. "28,903 / 31.1K"),
 *      adjacent sibling nodes, or structured stat labels.
 *   3. A 300ms settling interval is sufficient to avoid capturing intermediate DOM rendering artifacts.
 *   4. Login walls render standard LeetCode login forms, password inputs, or auth overlay modals.
 */

(() => {
  'use strict';

  // ─── Configuration Constants ───────────────────────────────────────────────

  /** Maximum time (ms) to wait for "Users Accepted" DOM element before failing. */
  const SCRAPING_TIMEOUT_MS = 15000;

  /** Interval (ms) between the two consecutive readings for the stability check. */
  const STABILITY_CHECK_DELAY_MS = 300;

  // ─── Module State ──────────────────────────────────────────────────────────

  /** @type {boolean} Indicates whether a scrape result has already been dispatched. */
  let hasDispatched = false;

  /** @type {boolean} Indicates whether a double-read stability verification is in flight. */
  let isVerifying = false;

  /** @type {MutationObserver|null} MutationObserver instance for DOM monitoring. */
  let domObserver = null;

  /** @type {number|null} Timeout ID for the overall 15s scraping deadline. */
  let scrapingTimeoutId = null;

  /** @type {object|null} Cached copy of the last dispatched scrape result payload. */
  let lastDispatchedResult = null;

  // ─── URL Validation ────────────────────────────────────────────────────────

  /**
   * Validates whether the current page URL is a contest problem subpage.
   *
   * @returns {boolean} True if running on a valid contest problem page.
   * @note Runs in content script DOM context.
   */
  function isTargetProblemPage() {
    const pathname = window.location.pathname;
    return pathname.includes('/contest/') && pathname.includes('/problems/');
  }

  // ─── Detection Helpers: Login-Wall & Page Unavailable ───────────────────────

  /**
   * Detects if the page presents a login wall, login modal, or authentication redirect.
   *
   * @returns {boolean} True if a login wall is active on the page.
   * @note Runs in content script DOM context.
   */
  function detectLoginWall() {
    // 1. URL redirect checks
    const path = window.location.pathname.toLowerCase();
    const search = window.location.search.toLowerCase();
    if (path.includes('/accounts/login') || path.includes('/login') || search.includes('next=')) {
      return true;
    }

    // 2. Visible login forms or authentication inputs
    const loginForm = document.querySelector(
      'form[action*="login" i], form[id*="login" i], [data-cypress="LoginForm"], [data-cy="login-form"], .login-pane'
    );
    if (loginForm) {
      return true;
    }

    const passwordInput = document.querySelector('input[type="password"]');
    if (passwordInput && (passwordInput.offsetWidth > 0 || passwordInput.offsetHeight > 0)) {
      return true;
    }

    // 3. Known login modal or auth container overlays
    const loginModalSelectors = [
      '.login-modal',
      '.auth-modal',
      '[class*="login-container" i]',
      '[class*="sign-in-prompt" i]'
    ];
    for (const sel of loginModalSelectors) {
      const el = document.querySelector(sel);
      if (el && (el.offsetWidth > 0 || el.offsetHeight > 0)) {
        return true;
      }
    }

    // 4. Modal / dialog text indicators
    const headings = document.querySelectorAll('h1, h2, h3, h4, .modal-title, [role="dialog"]');
    for (const heading of headings) {
      const text = (heading.innerText || heading.textContent || '').trim().toLowerCase();
      if (
        text.includes('sign in to leetcode') ||
        text.includes('log in to leetcode') ||
        text.includes('sign in to participate') ||
        text.includes('you need to log in') ||
        text.includes('please log in')
      ) {
        return true;
      }
    }

    return false;
  }

  /**
   * Detects if the problem page is unavailable (404 Not Found, 403 Forbidden, or not yet active).
   *
   * @returns {boolean} True if page unavailability is confirmed.
   * @note Runs in content script DOM context.
   */
  function detectPageUnavailable() {
    const title = (document.title || '').toLowerCase();
    if (title.includes('404') || title.includes('page not found') || title.includes('access denied')) {
      return true;
    }

    const errorContainer = document.querySelector(
      '[data-cy="error-page"], .error-page, .not-found, .error-404, [class*="error-container" i]'
    );
    if (errorContainer && (errorContainer.offsetWidth > 0 || errorContainer.offsetHeight > 0)) {
      return true;
    }

    const headings = document.querySelectorAll('h1, h2, h3');
    for (const h of headings) {
      const text = (h.innerText || h.textContent || '').trim().toLowerCase();
      if (
        text === '404' ||
        text === 'page not found' ||
        text === 'question not found' ||
        text === 'access denied' ||
        text.includes('contest has not started')
      ) {
        return true;
      }
    }

    return false;
  }

  // ─── Raw Text Extraction & Normalization ───────────────────────────────────

  /**
   * Cleans and sanitizes extracted text to isolate the raw numeric statistics string.
   * Strips extraneous labels and whitespace while strictly preserving all raw numeric
   * values, commas, decimals, K/M/B suffixes, and slash delimiters.
   *
   * Does NOT perform numeric parsing or float math.
   *
   * @param {string} text - The raw extracted text from the DOM.
   * @returns {string|null} Sanitized raw string (e.g. "28,903 / 31.1K", "150 / 300", "28,903") or null.
   * @note Runs in content script DOM context.
   */
  function sanitizeRawStat(text) {
    if (!text || typeof text !== 'string') {
      return null;
    }

    const str = text.trim();
    if (!str) {
      return null;
    }

    // Pattern 1: Separate multi-label format within a single container
    // E.g. "Users Accepted: 28,903 Total Submissions: 31.1K"
    const multiLabelMatch = str.match(
      /(?:users?\s*accepted|accepted\s*users?)[\s:\-–—]*([\d\.\,]+[KkMmBb]?)[\s\S]*?(?:total\s*)?submissions?[\s:\-–—]*([\d\.\,]+[KkMmBb]?)/i
    );
    if (multiLabelMatch && multiLabelMatch[1] && multiLabelMatch[2]) {
      return `${multiLabelMatch[1].trim()} / ${multiLabelMatch[2].trim()}`;
    }

    // Pattern 2: Slash-separated standard format
    // E.g. "Users Accepted: 28,903 / 31.1K", "28,903 / 31.1K", "1.2M / 4.5M"
    const slashMatch = str.match(/([\d\.\,]+[KkMmBb]?)\s*\/\s*([\d\.\,]+[KkMmBb]?)/);
    if (slashMatch && slashMatch[1] && slashMatch[2]) {
      return `${slashMatch[1].trim()} / ${slashMatch[2].trim()}`;
    }

    // Pattern 3: Single acceptance count (when total submissions is omitted in view)
    // E.g. "Users Accepted: 28,903" -> "28,903"
    let cleaned = str
      .replace(/^(?:users?\s*accepted|accepted\s*users?|accepted|acceptance\s*rate|submissions?)[\s:\-–—]*/i, '')
      .replace(/[\s:\-–—]*(?:users?\s*accepted|accepted\s*users?|submissions?)$/i, '')
      .replace(/\s+/g, ' ')
      .trim();

    // Check if remaining string is a single valid numeric stat (e.g. "28,903", "1.2M")
    if (/^[\d\.\,]+[KkMmBb]?$/.test(cleaned) && /\d/.test(cleaned)) {
      return cleaned;
    }

    // Pattern 4: Embedded single numeric token after a label
    const embeddedMatch = cleaned.match(/\b[\d\.\,]+[KkMmBb]?\b/);
    if (embeddedMatch && /\d/.test(embeddedMatch[0])) {
      return embeddedMatch[0].trim();
    }

    return null;
  }

  // ─── Selector Fallback Chain ───────────────────────────────────────────────

  /**
   * Strategy 1 (Priority 1): Text-Anchored Search
   * Finds elements containing the text "Users Accepted" (case-insensitive) and reads
   * the associated numeric statistics from the element, its siblings, or parent.
   *
   * @returns {{ rawUsersAccepted: string, strategy: string } | null}
   * @note Runs in content script DOM context.
   */
  function findByTextAnchored() {
    const labelPatterns = [
      /\busers?\s*accepted\b/i,
      /\baccepted\s*users?\b/i,
      /\baccepted\s*\/\s*submissions?\b/i,
      /\baccepted\b/i
    ];

    const elements = document.querySelectorAll(
      'span, div, p, td, th, li, b, strong, section, h1, h2, h3, h4, h5, h6'
    );

    for (const pattern of labelPatterns) {
      for (const el of elements) {
        if (el === document.body || el === document.documentElement) {
          continue;
        }
        // Skip large outer wrapper containers to target specific label leaf nodes
        if (el.children.length > 6) {
          continue;
        }

        const directText = (el.innerText || el.textContent || '').trim();
        if (!pattern.test(directText)) {
          continue;
        }

        // Case A: Label and value reside in the same DOM element
        const selfStat = sanitizeRawStat(directText);
        if (selfStat) {
          return { rawUsersAccepted: selfStat, strategy: 'text-anchored' };
        }

        // Case B: Value resides in an adjacent sibling DOM element
        let nextSib = el.nextElementSibling;
        while (nextSib) {
          const sibText = (nextSib.innerText || nextSib.textContent || '').trim();
          const sibStat = sanitizeRawStat(sibText);
          if (sibStat) {
            return { rawUsersAccepted: sibStat, strategy: 'text-anchored' };
          }
          nextSib = nextSib.nextElementSibling;
        }

        // Case C: Value resides in parent element's composite text
        if (el.parentElement && el.parentElement !== document.body) {
          const parentText = (el.parentElement.innerText || el.parentElement.textContent || '').trim();
          if (parentText.length < 250) {
            const parentStat = sanitizeRawStat(parentText);
            if (parentStat) {
              return { rawUsersAccepted: parentStat, strategy: 'text-anchored' };
            }
          }
        }
      }
    }

    return null;
  }

  /**
   * Strategy 2 (Priority 2): Attribute-Based Search
   * Finds elements with data attributes or classes related to submission and acceptance stats.
   *
   * @returns {{ rawUsersAccepted: string, strategy: string } | null}
   * @note Runs in content script DOM context.
   */
  function findByAttributeBased() {
    const attributeSelectors = [
      '[data-track-load*="accepted" i]',
      '[data-track-load*="submission" i]',
      '[data-cy*="accepted" i]',
      '[data-cy*="submission" i]',
      '[data-cy*="stat" i]',
      '[data-testid*="accepted" i]',
      '[data-testid*="submission" i]',
      '[data-key*="accepted" i]',
      '[class*="users-accepted" i]',
      '[class*="accepted-stat" i]',
      '[class*="submission-stat" i]',
      '[class*="contest-question-stat" i]',
      '[class*="question-info" i]'
    ];

    for (const selector of attributeSelectors) {
      const elements = document.querySelectorAll(selector);
      for (const el of elements) {
        const text = (el.innerText || el.textContent || '').trim();
        const stat = sanitizeRawStat(text);
        if (stat) {
          return { rawUsersAccepted: stat, strategy: 'attribute-based' };
        }

        if (el.nextElementSibling) {
          const sibStat = sanitizeRawStat(el.nextElementSibling.innerText || el.nextElementSibling.textContent);
          if (sibStat) {
            return { rawUsersAccepted: sibStat, strategy: 'attribute-based' };
          }
        }
      }
    }

    return null;
  }

  /**
   * Strategy 3 (Priority 3): Aria-Label Search
   * Finds elements with aria-label or aria-description containing acceptance terms.
   *
   * @returns {{ rawUsersAccepted: string, strategy: string } | null}
   * @note Runs in content script DOM context.
   */
  function findByAriaLabel() {
    const ariaSelectors = [
      '[aria-label*="users accepted" i]',
      '[aria-label*="accepted users" i]',
      '[aria-label*="accepted" i]',
      '[aria-label*="acceptance" i]',
      '[aria-description*="users accepted" i]',
      '[aria-description*="accepted" i]'
    ];

    for (const selector of ariaSelectors) {
      const elements = document.querySelectorAll(selector);
      for (const el of elements) {
        // 1. Inspect aria-label attribute value
        const ariaLabel = el.getAttribute('aria-label');
        if (ariaLabel) {
          const stat = sanitizeRawStat(ariaLabel);
          if (stat) {
            return { rawUsersAccepted: stat, strategy: 'aria-label' };
          }
        }

        // 2. Inspect aria-description attribute value
        const ariaDesc = el.getAttribute('aria-description');
        if (ariaDesc) {
          const stat = sanitizeRawStat(ariaDesc);
          if (stat) {
            return { rawUsersAccepted: stat, strategy: 'aria-label' };
          }
        }

        // 3. Inspect innerText of the aria element
        const text = (el.innerText || el.textContent || '').trim();
        const textStat = sanitizeRawStat(text);
        if (textStat) {
          return { rawUsersAccepted: textStat, strategy: 'aria-label' };
        }
      }
    }

    return null;
  }

  /**
   * Executes the 3-tier selector fallback chain in strict priority order.
   *
   * @returns {{ rawUsersAccepted: string, strategy: string } | null}
   * @note Runs in content script DOM context.
   */
  function evaluateSelectorChain() {
    // 1. Text-anchored
    const textResult = findByTextAnchored();
    if (textResult) {
      return textResult;
    }

    // 2. Attribute-based
    const attrResult = findByAttributeBased();
    if (attrResult) {
      return attrResult;
    }

    // 3. Aria-label
    const ariaResult = findByAriaLabel();
    if (ariaResult) {
      return ariaResult;
    }

    return null;
  }

  // ─── Dispatch & Stability Check Orchestration ──────────────────────────────

  /**
   * Dispatches the final scrape result message to the Chrome extension background service worker.
   * Disconnects the DOM observer and ensures results are dispatched only once per lifecycle.
   *
   * @param {object} payload
   * @param {string|null} payload.rawUsersAccepted - Raw unparsed statistics string.
   * @param {string} payload.scrapingStatus - Outcome status enum value.
   * @param {string} payload.selectorStrategyUsed - Strategy name used ('text-anchored', 'attribute-based', 'aria-label', 'none').
   * @returns {void}
   * @note Runs in content script DOM context.
   */
  function dispatchScrapeResult(payload) {
    if (hasDispatched) {
      return;
    }
    hasDispatched = true;

    stopScraping();

    const message = {
      type: 'SCRAPE_RESULT',
      rawUsersAccepted: payload.rawUsersAccepted ?? null,
      scrapingStatus: payload.scrapingStatus,
      selectorStrategyUsed: payload.selectorStrategyUsed ?? 'none',
      url: window.location.href
    };

    lastDispatchedResult = message;

    console.log(
      `[problemPageScript] Dispatched scrape result [${message.scrapingStatus}] strategy="${message.selectorStrategyUsed}":`,
      message.rawUsersAccepted
    );

    chrome.runtime.sendMessage(message, (response) => {
      if (chrome.runtime.lastError) {
        console.warn(
          '[problemPageScript] Error transmitting SCRAPE_RESULT to background:',
          chrome.runtime.lastError.message
        );
      } else {
        console.log('[problemPageScript] Background acknowledged SCRAPE_RESULT:', response);
      }
    });
  }

  /**
   * Attempts a scraping cycle. Evaluates login walls, page unavailability, and the selector chain.
   * Executes a double-read stability check (300ms delay) before confirming the value.
   *
   * @returns {Promise<void>}
   * @note Runs in content script DOM context.
   */
  async function attemptScrape() {
    if (hasDispatched || isVerifying) {
      return;
    }

    // Check 1: Login-wall detection
    if (detectLoginWall()) {
      console.warn('[problemPageScript] Login wall detected on problem page — reporting LOGIN_WALL.');
      dispatchScrapeResult({
        rawUsersAccepted: null,
        scrapingStatus: 'LOGIN_WALL',
        selectorStrategyUsed: 'none'
      });
      return;
    }

    // Check 2: Page unavailability detection
    if (detectPageUnavailable()) {
      console.warn('[problemPageScript] Problem page unavailable / 404 — reporting PAGE_UNAVAILABLE.');
      dispatchScrapeResult({
        rawUsersAccepted: null,
        scrapingStatus: 'PAGE_UNAVAILABLE',
        selectorStrategyUsed: 'none'
      });
      return;
    }

    // Check 3: Evaluate selector fallback chain (Reading 1)
    const reading1 = evaluateSelectorChain();
    if (!reading1) {
      return; // DOM not yet ready; continue observing
    }

    isVerifying = true;

    try {
      // Double-Read Stability Check: wait 300ms for React hydration / DOM settling
      await new Promise((resolve) => setTimeout(resolve, STABILITY_CHECK_DELAY_MS));

      if (hasDispatched) {
        return;
      }

      // Re-evaluate selector chain (Reading 2)
      const reading2 = evaluateSelectorChain();

      if (reading2 && reading1.rawUsersAccepted === reading2.rawUsersAccepted) {
        console.log(
          `[problemPageScript] Double-read stability check PASSED: "${reading2.rawUsersAccepted}" using strategy "${reading2.strategy}".`
        );
        dispatchScrapeResult({
          rawUsersAccepted: reading2.rawUsersAccepted,
          scrapingStatus: 'SUCCESS',
          selectorStrategyUsed: reading2.strategy
        });
      } else {
        console.log(
          `[problemPageScript] Double-read mismatch ("${reading1.rawUsersAccepted}" vs "${reading2?.rawUsersAccepted}"). Re-observing...`
        );
        isVerifying = false;
      }
    } catch (err) {
      console.error('[problemPageScript] Unexpected error during double-read stability check:', err);
      isVerifying = false;
    }
  }

  // ─── Lifecycle & Observer Management ───────────────────────────────────────

  /**
   * Stops the MutationObserver and clears the 15-second scraping timeout.
   *
   * @returns {void}
   * @note Runs in content script DOM context.
   */
  function stopScraping() {
    if (domObserver) {
      domObserver.disconnect();
      domObserver = null;
    }
    if (scrapingTimeoutId !== null) {
      clearTimeout(scrapingTimeoutId);
      scrapingTimeoutId = null;
    }
  }

  /**
   * Initializes DOM observation and starts the scraping workflow.
   *
   * @returns {void}
   * @note Runs in content script DOM context.
   */
  function startScraping() {
    if (!isTargetProblemPage()) {
      console.log(`[problemPageScript] Skipping — not a contest problem page (${window.location.pathname})`);
      return;
    }

    console.log(`[problemPageScript] Initialized on contest problem page: ${window.location.href}`);

    // Immediate initial probe
    attemptScrape();
    if (hasDispatched) {
      return;
    }

    // Set up MutationObserver to react to asynchronous React SPA rendering
    domObserver = new MutationObserver(() => {
      if (!hasDispatched && !isVerifying) {
        attemptScrape();
      }
    });

    const targetNode = document.body || document.documentElement;
    domObserver.observe(targetNode, {
      childList: true,
      subtree: true,
      characterData: true
    });

    // 15-second overall timeout fallback
    scrapingTimeoutId = setTimeout(() => {
      if (hasDispatched) {
        return;
      }

      console.error(`[problemPageScript] Scraping timed out after ${SCRAPING_TIMEOUT_MS}ms.`);

      // Final checks before declaring SELECTOR_NOT_FOUND
      if (detectLoginWall()) {
        dispatchScrapeResult({
          rawUsersAccepted: null,
          scrapingStatus: 'LOGIN_WALL',
          selectorStrategyUsed: 'none'
        });
      } else if (detectPageUnavailable()) {
        dispatchScrapeResult({
          rawUsersAccepted: null,
          scrapingStatus: 'PAGE_UNAVAILABLE',
          selectorStrategyUsed: 'none'
        });
      } else {
        dispatchScrapeResult({
          rawUsersAccepted: null,
          scrapingStatus: 'SELECTOR_NOT_FOUND',
          selectorStrategyUsed: 'none'
        });
      }
    }, SCRAPING_TIMEOUT_MS);
  }

  // ─── Runtime Message Listener ──────────────────────────────────────────────

  /**
   * Listens for direct scrape requests from background or dev tools.
   */
  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === 'REQUEST_SCRAPE') {
      console.log('[problemPageScript] Received REQUEST_SCRAPE message.');
      if (lastDispatchedResult) {
        sendResponse(lastDispatchedResult);
      } else {
        attemptScrape();
        sendResponse({ status: 'SCRAPE_IN_PROGRESS' });
      }
    }
  });

  // ─── Entry Point Execution ─────────────────────────────────────────────────

  startScraping();
})();
