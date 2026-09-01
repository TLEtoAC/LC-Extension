/*
 * File: ContestConfigRequest.java
 * Author: REST API Agent
 * Phase: Phase 3 — Backend: ContestConfigController + Model Classes
 * Purpose: Data Transfer Object (DTO) for contest configuration requests sent by the Chrome Extension.
 */

package com.leetcode.monitor.dto;

import java.util.List;

/**
 * Inbound payload representing the contest setup configuration from the Chrome Extension.
 */
public class ContestConfigRequest {

    /**
     * URL of the LeetCode contest page.
     */
    private String contestUrl;

    /**
     * List of 4 contest questions (Q1 through Q4).
     */
    private List<QuestionRequest> questions;

    /**
     * Default no-arg constructor.
     */
    public ContestConfigRequest() {
    }

    /**
     * All-args constructor.
     *
     * @param contestUrl URL of the contest
     * @param questions  list of question configurations
     */
    public ContestConfigRequest(String contestUrl, List<QuestionRequest> questions) {
        this.contestUrl = contestUrl;
        this.questions = questions;
    }

    /**
     * Gets the contest URL.
     *
     * @return the contest URL
     */
    public String getContestUrl() {
        return contestUrl;
    }

    /**
     * Sets the contest URL.
     *
     * @param contestUrl the contest URL
     */
    public void setContestUrl(String contestUrl) {
        this.contestUrl = contestUrl;
    }

    /**
     * Gets the list of question configurations.
     *
     * @return the list of question requests
     */
    public List<QuestionRequest> getQuestions() {
        return questions;
    }

    /**
     * Sets the list of question configurations.
     *
     * @param questions the list of question requests
     */
    public void setQuestions(List<QuestionRequest> questions) {
        this.questions = questions;
    }

    /**
     * Nested DTO representing individual question metadata within the configuration payload.
     */
    public static class QuestionRequest {

        /**
         * Question identifier (e.g., "Q1", "Q2", "Q3", "Q4").
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
         * Default no-arg constructor.
         */
        public QuestionRequest() {
        }

        /**
         * All-args constructor.
         *
         * @param questionNumber question slot identifier
         * @param problemName    problem title
         * @param problemUrl     problem page URL
         */
        public QuestionRequest(String questionNumber, String problemName, String problemUrl) {
            this.questionNumber = questionNumber;
            this.problemName = problemName;
            this.problemUrl = problemUrl;
        }

        /**
         * Gets the question slot identifier.
         *
         * @return the question number
         */
        public String getQuestionNumber() {
            return questionNumber;
        }

        /**
         * Sets the question slot identifier.
         *
         * @param questionNumber the question number
         */
        public void setQuestionNumber(String questionNumber) {
            this.questionNumber = questionNumber;
        }

        /**
         * Gets the problem title or name.
         *
         * @return the problem name
         */
        public String getProblemName() {
            return problemName;
        }

        /**
         * Sets the problem title or name.
         *
         * @param problemName the problem name
         */
        public void setProblemName(String problemName) {
            this.problemName = problemName;
        }

        /**
         * Gets the problem page URL.
         *
         * @return the problem URL
         */
        public String getProblemUrl() {
            return problemUrl;
        }

        /**
         * Sets the problem page URL.
         *
         * @param problemUrl the problem URL
         */
        public void setProblemUrl(String problemUrl) {
            this.problemUrl = problemUrl;
        }
    }
}
