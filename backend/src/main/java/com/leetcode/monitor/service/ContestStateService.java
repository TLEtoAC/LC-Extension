/*
 * File: ContestStateService.java
 * Author: Backend/Core Agent
 * Phase: Phase 11 — Robustness + Lifecycle
 * Purpose: Central thread-safe state management. applyIngest wraps parse → calculate →
 *          recompute ranking → compute overtakes → lifecycle → snapshot publish
 *          in a single synchronized critical section.
 *
 * Concurrency & Architecture Notes:
 * This class owns the ContestStateService critical section per Section 7A-2 / ADR-003.
 * All state mutations are synchronized. QuestionStats are deep-copied before mutation so
 * published snapshots cannot be torn by a later ingest (ADR-014).
 * Snapshots are published atomically via AtomicReference<ContestStats> for lock-free reads.
 * No I/O is performed inside the lock.
 */

package com.leetcode.monitor.service;

import com.leetcode.monitor.dto.ContestIngestRequest;
import com.leetcode.monitor.model.ContestStats;
import com.leetcode.monitor.model.Question;
import com.leetcode.monitor.model.QuestionStats;
import com.leetcode.monitor.model.RankingChange;
import com.leetcode.monitor.model.ScrapingStatus;
import com.leetcode.monitor.parser.AcceptanceStatsParser;
import com.leetcode.monitor.parser.ParsedAcceptance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    private final AcceptanceStatsParser acceptanceStatsParser;
    private final AcceptanceCalculationService acceptanceCalculationService;
    private final RankingService rankingService;
    private final ComparisonService comparisonService;
    private final ContestLifecycleService contestLifecycleService;

    /**
     * Constructs the service with parser, calculator, ranking, overtake, and lifecycle.
     *
     * @param acceptanceStatsParser         parser for raw acceptance strings
     * @param acceptanceCalculationService  BigDecimal percentage calculator
     * @param rankingService                ranks questions by acceptance percentage
     * @param comparisonService             pairwise overtake detector
     * @param contestLifecycleService       ADR-006 ENDED detection
     */
    public ContestStateService(
            AcceptanceStatsParser acceptanceStatsParser,
            AcceptanceCalculationService acceptanceCalculationService,
            RankingService rankingService,
            ComparisonService comparisonService,
            ContestLifecycleService contestLifecycleService) {
        this.acceptanceStatsParser = acceptanceStatsParser;
        this.acceptanceCalculationService = acceptanceCalculationService;
        this.rankingService = rankingService;
        this.comparisonService = comparisonService;
        this.contestLifecycleService = contestLifecycleService;
    }

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

        contestLifecycleService.reset();
        currentStats.set(newStats);
        logger.info("Contest state successfully initialized with lifecycleState=INITIALISED");
    }

    /**
     * Updates question statistics with inbound scraped data and publishes an updated contest snapshot.
     *
     * <p>Critical section: method-level {@code synchronized} wraps the full compound transition
     * (deep-copy → apply ingest → parse/calculate → ranking → overtakes → lifecycle → publish).
     * AtomicReference alone is not sufficient (ADR-003).</p>
     *
     * @param questionNumber the question slot identifier (e.g., "Q1", "Q2", "Q3", "Q4")
     * @param request        the ingest payload containing scraping status and scraped statistics
     * @return the updated {@link QuestionStats} for the target question (a copy, not the previous snapshot's object)
     * @throws IllegalStateException    if no contest has been initialized
     * @throws IllegalArgumentException if questionNumber does not match any configured question
     * @throws NullPointerException     if questionNumber or request is null
     */
    public synchronized QuestionStats applyIngest(String questionNumber, ContestIngestRequest request) {
        Objects.requireNonNull(questionNumber, "questionNumber cannot be null");
        Objects.requireNonNull(request, "request cannot be null");

        ContestStats previous = currentStats.get();
        if (previous == null) {
            logger.error("Cannot apply ingest for {}: contest has not been initialized", questionNumber);
            throw new IllegalStateException("Contest has not been initialized");
        }

        logger.info("Applying ingest for question: {} with scrapingStatus: {}", questionNumber, request.getScrapingStatus());

        List<QuestionStats> updatedQuestions = new ArrayList<>();
        QuestionStats targetQuestionStats = null;

        for (QuestionStats qs : previous.getQuestions()) {
            QuestionStats copy = copyQuestionStats(qs);
            if (copy.getQuestionNumber().equalsIgnoreCase(questionNumber.trim())) {
                applyIngestToCopy(copy, request);
                targetQuestionStats = copy;
            }
            updatedQuestions.add(copy);
        }

        if (targetQuestionStats == null) {
            logger.error("Question {} not found in active contest configuration", questionNumber);
            throw new IllegalArgumentException("Question " + questionNumber + " not found in contest configuration");
        }

        String nextLifecycleState = contestLifecycleService.nextLifecycleState(
                previous.getLifecycleState(),
                updatedQuestions,
                questionNumber
        );

        // Ranking + overtakes are part of the same synchronized publish (Section 7A-2).
        List<String> ranking = rankingService.computeRanking(updatedQuestions);
        ComparisonService.ComparisonOutcome comparison = comparisonService.detectOvertakes(
                previous.getQuestions(),
                updatedQuestions,
                previous.getPairwiseRelationships(),
                previous.getRecentChanges()
        );
        List<RankingChange> recentChanges = comparison.recentChanges();
        Map<String, String> pairwiseRelationships = comparison.pairwiseRelationships();

        ContestStats newSnapshot = ContestStats.builder()
                .contestUrl(previous.getContestUrl())
                .questions(updatedQuestions)
                .ranking(ranking)
                .recentChanges(recentChanges)
                .lastUpdated(Instant.now())
                .lifecycleState(nextLifecycleState)
                .pairwiseRelationships(pairwiseRelationships)
                .build();

        currentStats.set(newSnapshot);
        logger.info("Successfully applied ingest for {}. Snapshot updated with lifecycleState={}", questionNumber, nextLifecycleState);

        return targetQuestionStats;
    }

    /**
     * Applies scrape metadata and, on SUCCESS, parse + percentage calculation to a deep-copied QuestionStats.
     * Parse/zero-total failures become PARSE_ERROR with metrics cleared. Non-SUCCESS statuses from the
     * extension (including NAVIGATION_TIMEOUT) preserve that status and clear metric fields.
     *
     * @param target  mutable copy of the target question
     * @param request inbound ingest payload
     */
    private void applyIngestToCopy(QuestionStats target, ContestIngestRequest request) {
        target.setTimestamp(Instant.now());
        target.setScrapingStatus(request.getScrapingStatus());
        target.setErrorMessage(request.getErrorMessage());
        target.setSelectorStrategyUsed(request.getSelectorStrategyUsed());

        if (request.getScrapingStatus() == ScrapingStatus.SUCCESS) {
            try {
                ParsedAcceptance parsed = acceptanceStatsParser.parse(request.getRawUsersAccepted());
                target.setAcceptedUsers(parsed.acceptedUsers());
                target.setTotalUsers(parsed.totalUsers());
                target.setUsersAcceptedPercentage(
                        acceptanceCalculationService.calculatePercentage(parsed.acceptedUsers(), parsed.totalUsers())
                );
            } catch (IllegalArgumentException ex) {
                // ParseException extends IllegalArgumentException; zero totalUsers also throws IAE
                logger.warn("Parse/calculate failed for {}: {}", target.getQuestionNumber(), ex.getMessage());
                target.setScrapingStatus(ScrapingStatus.PARSE_ERROR);
                target.setErrorMessage(ex.getMessage());
                target.setAcceptedUsers(null);
                target.setTotalUsers(null);
                target.setUsersAcceptedPercentage(null);
            }
        } else {
            target.setAcceptedUsers(null);
            target.setTotalUsers(null);
            target.setUsersAcceptedPercentage(null);
        }
    }

    /**
     * Deep-copies a {@link QuestionStats} so published snapshots are not mutated in place.
     *
     * @param source the snapshot-owned instance
     * @return a new object with the same field values
     */
    private QuestionStats copyQuestionStats(QuestionStats source) {
        return new QuestionStats(
                source.getQuestionNumber(),
                source.getProblemName(),
                source.getProblemUrl(),
                source.getAcceptedUsers(),
                source.getTotalUsers(),
                source.getUsersAcceptedPercentage(),
                source.getTimestamp(),
                source.getScrapingStatus(),
                source.getErrorMessage(),
                source.getSelectorStrategyUsed()
        );
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
