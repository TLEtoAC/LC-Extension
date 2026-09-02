/*
 * File: ContestIngestRequest.java
 * Author: REST API Agent
 * Phase: Phase 4 — Backend: ContestIngestController & Ingest DTOs
 * Purpose: Inbound Data Transfer Object (DTO) representing scraped problem statistics and outcome status.
 */

package com.leetcode.monitor.dto;

import com.leetcode.monitor.model.ScrapingStatus;

/**
 * Inbound payload representing scraped submission data and status for an individual contest question.
 */
public class ContestIngestRequest {

    /**
     * Raw "Users Accepted" string extracted from the LeetCode problem page (e.g., "28,903 / 31.1K").
     * Optional if scrapingStatus is not SUCCESS.
     */
    private String rawUsersAccepted;

    /**
     * Outcome status of the scraping attempt (required).
     */
    private ScrapingStatus scrapingStatus;

    /**
     * DOM selector strategy utilized during extraction (optional).
     */
    private String selectorStrategyUsed;

    /**
     * Error message details if scraping or parsing failed (optional).
     */
    private String errorMessage;

    /**
     * Default no-argument constructor.
     */
    public ContestIngestRequest() {
    }

    /**
     * Convenience constructor with rawUsersAccepted and scrapingStatus.
     *
     * @param rawUsersAccepted raw text extracted from problem page
     * @param scrapingStatus   outcome status of the scraping attempt
     */
    public ContestIngestRequest(String rawUsersAccepted, ScrapingStatus scrapingStatus) {
        this.rawUsersAccepted = rawUsersAccepted;
        this.scrapingStatus = scrapingStatus;
    }

    /**
     * All-arguments constructor for ContestIngestRequest.
     *
     * @param rawUsersAccepted     raw text extracted from problem page
     * @param scrapingStatus       outcome status of the scraping attempt
     * @param selectorStrategyUsed identifier of DOM selector strategy used
     * @param errorMessage         error description if scraping failed
     */
    public ContestIngestRequest(
            String rawUsersAccepted,
            ScrapingStatus scrapingStatus,
            String selectorStrategyUsed,
            String errorMessage) {
        this.rawUsersAccepted = rawUsersAccepted;
        this.scrapingStatus = scrapingStatus;
        this.selectorStrategyUsed = selectorStrategyUsed;
        this.errorMessage = errorMessage;
    }

    /**
     * Gets the raw users accepted string.
     *
     * @return the raw string, or {@code null} if scraping was unsuccessful
     */
    public String getRawUsersAccepted() {
        return rawUsersAccepted;
    }

    /**
     * Sets the raw users accepted string.
     *
     * @param rawUsersAccepted the raw scraped string
     */
    public void setRawUsersAccepted(String rawUsersAccepted) {
        this.rawUsersAccepted = rawUsersAccepted;
    }

    /**
     * Gets the scraping outcome status.
     *
     * @return the scraping status
     */
    public ScrapingStatus getScrapingStatus() {
        return scrapingStatus;
    }

    /**
     * Sets the scraping outcome status.
     *
     * @param scrapingStatus the scraping status
     */
    public void setScrapingStatus(ScrapingStatus scrapingStatus) {
        this.scrapingStatus = scrapingStatus;
    }

    /**
     * Gets the DOM selector strategy used during scraping.
     *
     * @return the selector strategy identifier, or {@code null}
     */
    public String getSelectorStrategyUsed() {
        return selectorStrategyUsed;
    }

    /**
     * Sets the DOM selector strategy used during scraping.
     *
     * @param selectorStrategyUsed the selector strategy identifier
     */
    public void setSelectorStrategyUsed(String selectorStrategyUsed) {
        this.selectorStrategyUsed = selectorStrategyUsed;
    }

    /**
     * Gets the scraping error message.
     *
     * @return the error message, or {@code null} if no error occurred
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Sets the scraping error message.
     *
     * @param errorMessage the error message
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
