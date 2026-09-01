/*
 * File: QuestionStats.java
 * Author: REST API Agent
 * Phase: Phase 3 — Backend: ContestConfigController + Model Classes
 * Purpose: Mutable domain model representing live ingestion and submission statistics for an individual contest question.
 */

package com.leetcode.monitor.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Mutable domain model representing the current statistics and scraping metadata for a contest question.
 */
public class QuestionStats {

    /**
     * Question slot identifier (e.g., "Q1", "Q2", "Q3", "Q4").
     */
    private String questionNumber;

    /**
     * Human-readable problem title or slug.
     */
    private String problemName;

    /**
     * Full URL to the contest problem page.
     */
    private String problemUrl;

    /**
     * Total number of distinct users who successfully solved the problem (null until first ingest).
     */
    private BigDecimal acceptedUsers;

    /**
     * Total number of users who made submissions to the problem (null until first ingest).
     */
    private BigDecimal totalUsers;

    /**
     * Percentage of submitting users who solved the problem (acceptedUsers / totalUsers * 100) (null until first ingest).
     */
    private BigDecimal usersAcceptedPercentage;

    /**
     * Timestamp of the most recent scraping attempt (null until first ingest).
     */
    private Instant timestamp;

    /**
     * Scraping outcome status of the most recent ingest attempt (null if never ingested).
     */
    private ScrapingStatus scrapingStatus;

    /**
     * Error message details if scraping or parsing failed (null if no error).
     */
    private String errorMessage;

    /**
     * Identifier of the DOM selector strategy utilized during data extraction (null until first ingest).
     */
    private String selectorStrategyUsed;

    /**
     * Default no-argument constructor.
     */
    public QuestionStats() {
    }

    /**
     * Full-arguments constructor for QuestionStats.
     *
     * @param questionNumber          question slot identifier (e.g., "Q1")
     * @param problemName             human-readable problem name
     * @param problemUrl              full URL to the problem page
     * @param acceptedUsers           accepted user count
     * @param totalUsers              total submitting user count
     * @param usersAcceptedPercentage acceptance percentage
     * @param timestamp               timestamp of the scraping attempt
     * @param scrapingStatus          scraping outcome status
     * @param errorMessage            error message, if any
     * @param selectorStrategyUsed    selector strategy employed
     */
    public QuestionStats(
            String questionNumber,
            String problemName,
            String problemUrl,
            BigDecimal acceptedUsers,
            BigDecimal totalUsers,
            BigDecimal usersAcceptedPercentage,
            Instant timestamp,
            ScrapingStatus scrapingStatus,
            String errorMessage,
            String selectorStrategyUsed) {
        this.questionNumber = questionNumber;
        this.problemName = problemName;
        this.problemUrl = problemUrl;
        this.acceptedUsers = acceptedUsers;
        this.totalUsers = totalUsers;
        this.usersAcceptedPercentage = usersAcceptedPercentage;
        this.timestamp = timestamp;
        this.scrapingStatus = scrapingStatus;
        this.errorMessage = errorMessage;
        this.selectorStrategyUsed = selectorStrategyUsed;
    }

    /**
     * Static factory method that constructs an uninitialized QuestionStats instance for a configured question.
     * All ingestion-related metrics are initialized to {@code null} representing the pre-ingest state.
     *
     * @param question the configured question metadata
     * @return a new QuestionStats instance with question identity set and null metric values
     */
    public static QuestionStats uninitialised(Question question) {
        Objects.requireNonNull(question, "question cannot be null");
        QuestionStats stats = new QuestionStats();
        stats.setQuestionNumber(question.questionNumber());
        stats.setProblemName(question.problemName());
        stats.setProblemUrl(question.problemUrl());
        stats.setAcceptedUsers(null);
        stats.setTotalUsers(null);
        stats.setUsersAcceptedPercentage(null);
        stats.setTimestamp(null);
        stats.setScrapingStatus(null);
        stats.setErrorMessage(null);
        stats.setSelectorStrategyUsed(null);
        return stats;
    }

    /**
     * Gets the question slot identifier.
     *
     * @return the question number (e.g., "Q1")
     */
    public String getQuestionNumber() {
        return questionNumber;
    }

    /**
     * Sets the question slot identifier.
     *
     * @param questionNumber the question number (e.g., "Q1")
     */
    public void setQuestionNumber(String questionNumber) {
        this.questionNumber = questionNumber;
    }

    /**
     * Gets the human-readable problem name.
     *
     * @return the problem name
     */
    public String getProblemName() {
        return problemName;
    }

    /**
     * Sets the human-readable problem name.
     *
     * @param problemName the problem name
     */
    public void setProblemName(String problemName) {
        this.problemName = problemName;
    }

    /**
     * Gets the full URL to the problem page.
     *
     * @return the problem URL
     */
    public String getProblemUrl() {
        return problemUrl;
    }

    /**
     * Sets the full URL to the problem page.
     *
     * @param problemUrl the problem URL
     */
    public void setProblemUrl(String problemUrl) {
        this.problemUrl = problemUrl;
    }

    /**
     * Gets the accepted users count.
     *
     * @return the accepted users count, or {@code null} if not yet ingested
     */
    public BigDecimal getAcceptedUsers() {
        return acceptedUsers;
    }

    /**
     * Sets the accepted users count.
     *
     * @param acceptedUsers the accepted users count
     */
    public void setAcceptedUsers(BigDecimal acceptedUsers) {
        this.acceptedUsers = acceptedUsers;
    }

    /**
     * Gets the total submitting users count.
     *
     * @return the total submitting users count, or {@code null} if not yet ingested
     */
    public BigDecimal getTotalUsers() {
        return totalUsers;
    }

    /**
     * Sets the total submitting users count.
     *
     * @param totalUsers the total submitting users count
     */
    public void setTotalUsers(BigDecimal totalUsers) {
        this.totalUsers = totalUsers;
    }

    /**
     * Gets the percentage of submitting users who solved the problem.
     *
     * @return the acceptance percentage, or {@code null} if not yet ingested
     */
    public BigDecimal getUsersAcceptedPercentage() {
        return usersAcceptedPercentage;
    }

    /**
     * Sets the percentage of submitting users who solved the problem.
     *
     * @param usersAcceptedPercentage the acceptance percentage
     */
    public void setUsersAcceptedPercentage(BigDecimal usersAcceptedPercentage) {
        this.usersAcceptedPercentage = usersAcceptedPercentage;
    }

    /**
     * Gets the timestamp of the scraping attempt.
     *
     * @return the scraping timestamp, or {@code null} if not yet ingested
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Sets the timestamp of the scraping attempt.
     *
     * @param timestamp the scraping timestamp
     */
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * Gets the outcome status of the scraping attempt.
     *
     * @return the scraping status, or {@code null} if never ingested
     */
    public ScrapingStatus getScrapingStatus() {
        return scrapingStatus;
    }

    /**
     * Sets the outcome status of the scraping attempt.
     *
     * @param scrapingStatus the scraping status
     */
    public void setScrapingStatus(ScrapingStatus scrapingStatus) {
        this.scrapingStatus = scrapingStatus;
    }

    /**
     * Gets the scraping error message details.
     *
     * @return the error message, or {@code null} if no error occurred
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Sets the scraping error message details.
     *
     * @param errorMessage the error message
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Gets the DOM selector strategy utilized during extraction.
     *
     * @return the selector strategy name, or {@code null} if not yet ingested
     */
    public String getSelectorStrategyUsed() {
        return selectorStrategyUsed;
    }

    /**
     * Sets the DOM selector strategy utilized during extraction.
     *
     * @param selectorStrategyUsed the selector strategy name
     */
    public void setSelectorStrategyUsed(String selectorStrategyUsed) {
        this.selectorStrategyUsed = selectorStrategyUsed;
    }
}
