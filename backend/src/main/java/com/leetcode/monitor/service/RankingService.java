/*
 * File: RankingService.java
 * Author: Backend/Core Agent
 * Phase: Phase 8 — State + Ranking
 * Purpose: Rank Q1–Q4 by usersAcceptedPercentage. Called only from the
 *          ContestStateService critical section (Section 7A-2) — no I/O.
 *
 * Ranking contract (ADR-022)
 * ──────────────────────────
 *   • Descending usersAcceptedPercentage (highest acceptance first).
 *   • Ties broken by question number ascending (Q1 before Q2) — stable, deterministic.
 *   • Null percentage (PARSE_ERROR, NAVIGATION_TIMEOUT, not yet scraped) sorts last,
 *     among themselves by question number. Never throws.
 *   • BigDecimal.compareTo only — never double.
 */

package com.leetcode.monitor.service;

import com.leetcode.monitor.model.QuestionStats;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Computes the immutable ranking list stored on each {@code ContestStats} snapshot.
 *
 * <p>This class is stateless. Callers must invoke it from the synchronized
 * {@code applyIngest} critical section so the ranking is published atomically
 * with the rest of the compound transition.</p>
 */
@Service
public class RankingService {

    /**
     * Builds the ranking list for the four question slots.
     *
     * @param questions current (already-copied) question stats, typically Q1–Q4
     * @return ordered question numbers; empty list if {@code questions} is null or empty
     */
    public List<String> computeRanking(List<QuestionStats> questions) {
        if (questions == null || questions.isEmpty()) {
            return List.of();
        }

        List<QuestionStats> withPercentage = new ArrayList<>();
        List<QuestionStats> withoutPercentage = new ArrayList<>();
        for (QuestionStats stats : questions) {
            if (stats == null) {
                continue;
            }
            if (stats.getUsersAcceptedPercentage() != null) {
                withPercentage.add(stats);
            } else {
                withoutPercentage.add(stats);
            }
        }

        withPercentage.sort(rankedComparator());
        withoutPercentage.sort(Comparator.comparing(
                QuestionStats::getQuestionNumber,
                Comparator.nullsLast(String::compareToIgnoreCase)
        ));

        List<String> ranking = new ArrayList<>(questions.size());
        for (QuestionStats stats : withPercentage) {
            ranking.add(stats.getQuestionNumber());
        }
        for (QuestionStats stats : withoutPercentage) {
            ranking.add(stats.getQuestionNumber());
        }
        return ranking;
    }

    /**
     * Highest percentage first; equal percentages break by question number.
     * Uses {@link BigDecimal#compareTo(BigDecimal)} — never {@code double}.
     */
    private static Comparator<QuestionStats> rankedComparator() {
        Comparator<BigDecimal> percentageDescending = (left, right) -> right.compareTo(left);
        return Comparator
                .comparing(QuestionStats::getUsersAcceptedPercentage, percentageDescending)
                .thenComparing(
                        QuestionStats::getQuestionNumber,
                        Comparator.nullsLast(String::compareToIgnoreCase)
                );
    }
}
