/*
 * File: ScrapingStatus.java
 * Author: REST API Agent
 * Phase: Phase 3 — Backend: ContestConfigController + Model Classes
 * Purpose: Enumerates scraping outcome statuses for question data ingestion.
 */

package com.leetcode.monitor.model;

/**
 * Represents the status or outcome of a scraping attempt on LeetCode contest problem pages.
 */
public enum ScrapingStatus {

    /**
     * Successfully retrieved and parsed question submission statistics.
     */
    SUCCESS,

    /**
     * Encountered a login wall or authentication prompt preventing data access.
     */
    LOGIN_WALL,

    /**
     * Required DOM selector was not found on the page.
     */
    SELECTOR_NOT_FOUND,

    /**
     * DOM element was present but parsing the statistics text failed.
     */
    PARSE_ERROR,

    /**
     * Page navigation timed out before reaching target content.
     */
    NAVIGATION_TIMEOUT,

    /**
     * Target page returned an HTTP error or was unavailable.
     */
    PAGE_UNAVAILABLE,

    /**
     * Browser or automation engine encountered an internal error.
     */
    BROWSER_ERROR,

    /**
     * Required browser tab or session was missing or closed.
     */
    TAB_MISSING,

    /**
     * An unexpected or unclassified error occurred.
     */
    UNKNOWN_ERROR
}
