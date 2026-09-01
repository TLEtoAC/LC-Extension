/*
 * File: ContestStateService.java
 * Author: REST API Agent
 * Phase: Phase 3 — Backend: ContestConfigController + Model Classes
 * Purpose: Central thread-safe state management service for active contest tracking and snapshot publishing.
 *
 * Concurrency & Architecture Notes:
 * This class owns the ContestStateService critical section per Section 7A-2.
 * All state mutations to contest metrics and lifecycle transitions are synchronized through this service.
 * Snapshots are published atomically via AtomicReference<ContestStats> to allow non-blocking reads.
 */

package com.leetcode.monitor.service;

import com.leetcode.monitor.model.ContestStats;
import com.leetcode.monitor.model.Question;
import com.leetcode.monitor.model.QuestionStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Service managing contest state, question statistics, ranking computations, and snapshots.
 * This class owns the ContestStateService critical section per Section 7A-2.
 */
@Service
public class ContestStateService {

    private static final Logger logger = LoggerFactory.getLogger(ContestStateService.class);

    private final AtomicReference<ContestStats> currentStats = new AtomicReference<>();

    /**
     * Initializes the contest state with the configured contest URL and questions.
     * Creates uninitialized statistics records for each question and sets the lifecycle state to "INITIALISED".
     *
     * Critical section: This method touches the critical section and is synchronized to guarantee that
     * contest initialization is atomic and mutually exclusive with ingest updates.
     *
     * @param contestUrl the URL of the LeetCode contest
     * @param questions  the list of 4 configured contest questions (Q1 through Q4)
     * @throws NullPointerException if contestUrl or questions is null
     */
    public synchronized void initializeContest(String contestUrl, List<Question> questions) {
        Objects.requireNonNull(contestUrl, "contestUrl cannot be null");
        Objects.requireNonNull(questions, "questions cannot be null");

        logger.info("Initializing contest state for URL: {} with {} questions", contestUrl, questions.size());

        // Ensure questions are ordered Q1 -> Q4
        List<QuestionStats> uninitialisedQuestionStats = questions.stream()
                .sorted(Comparator.comparing(Question::questionNumber))
                .map(QuestionStats::uninitialised)
                .collect(Collectors.toList());

        ContestStats newStats = ContestStats.builder()
                .contestUrl(contestUrl)
                .questions(uninitialisedQuestionStats)
                .ranking(Collections.emptyList())
                .recentChanges(Collections.emptyList())
                .lastUpdated(Instant.now())
                .lifecycleState("INITIALISED")
                .pairwiseRelationships(Collections.emptyMap())
                .build();

        currentStats.set(newStats);
        logger.info("Contest state successfully initialized with lifecycleState=INITIALISED");
    }

    /**
     * Retrieves the current immutable snapshot of contest statistics.
     *
     * @return the current {@link ContestStats} snapshot, or {@code null} if no contest has been initialized
     */
    public ContestStats getCurrentStats() {
        return currentStats.get();
    }
}
