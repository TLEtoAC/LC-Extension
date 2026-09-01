/*
 * File: Question.java
 * Author: REST API Agent
 * Phase: Phase 3 — Backend: ContestConfigController + Model Classes
 * Purpose: Immutable domain model representing a contest problem definition.
 */

package com.leetcode.monitor.model;

import java.util.Objects;

/**
 * Immutable record representing a contest question configuration.
 *
 * @param questionNumber Question slot identifier (e.g., "Q1", "Q2", "Q3", "Q4").
 * @param problemName    Human-readable problem name or slug.
 * @param problemUrl     Full URL to the contest problem page on LeetCode.
 */
public record Question(
        String questionNumber,
        String problemName,
        String problemUrl
) {

    /**
     * Compact constructor validating question attributes.
     *
     * @param questionNumber question slot identifier ("Q1" through "Q4")
     * @param problemName    human-readable problem name
     * @param problemUrl     full URL to the problem page
     */
    public Question {
        Objects.requireNonNull(questionNumber, "questionNumber cannot be null");
        Objects.requireNonNull(problemName, "problemName cannot be null");
        Objects.requireNonNull(problemUrl, "problemUrl cannot be null");
    }
}
