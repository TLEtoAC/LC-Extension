/*
 * File: RankingServiceTest.java
 * Author: Testing/QA Agent
 * Phase: Phase 8 — State + Ranking
 * Purpose: Ranking order, ties, and null-percentage (unranked-last) cases.
 */

package com.leetcode.monitor.service;

import com.leetcode.monitor.model.QuestionStats;
import com.leetcode.monitor.model.ScrapingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RankingService")
class RankingServiceTest {

    private final RankingService rankingService = new RankingService();

    @Test
    @DisplayName("Ranks by usersAcceptedPercentage descending")
    void ranksDescendingByPercentage() {
        List<String> ranking = rankingService.computeRanking(List.of(
                stats("Q1", "50"),
                stats("Q2", "75"),
                stats("Q3", "10"),
                stats("Q4", "92.9356913183")
        ));

        assertEquals(List.of("Q4", "Q2", "Q1", "Q3"), ranking);
    }

    @Test
    @DisplayName("Ties break by question number ascending")
    void tiesBreakByQuestionNumber() {
        List<String> ranking = rankingService.computeRanking(List.of(
                stats("Q1", "50"),
                stats("Q2", "75"),
                stats("Q3", "50"),
                stats("Q4", "75")
        ));

        assertEquals(List.of("Q2", "Q4", "Q1", "Q3"), ranking);
    }

    @Test
    @DisplayName("Null percentages (PARSE_ERROR / not scraped) sort last and do not crash")
    void nullPercentagesRankLast() {
        List<String> ranking = rankingService.computeRanking(List.of(
                stats("Q1", "40"),
                parseError("Q2"),
                stats("Q3", "80"),
                uninitialised("Q4")
        ));

        assertEquals(List.of("Q3", "Q1", "Q2", "Q4"), ranking);
    }

    @Test
    @DisplayName("All-null percentages yield question-number order, never throw")
    void allNullPercentagesAreUnrankedLast() {
        List<String> ranking = rankingService.computeRanking(List.of(
                uninitialised("Q3"),
                parseError("Q1"),
                uninitialised("Q4"),
                parseError("Q2")
        ));

        assertEquals(List.of("Q1", "Q2", "Q3", "Q4"), ranking);
    }

    @Test
    @DisplayName("Empty or null input returns an empty ranking")
    void emptyInputReturnsEmptyList() {
        assertTrue(rankingService.computeRanking(List.of()).isEmpty());
        assertTrue(rankingService.computeRanking(null).isEmpty());
    }

    private static QuestionStats stats(String questionNumber, String percentage) {
        return new QuestionStats(
                questionNumber,
                questionNumber + "-name",
                "https://leetcode.com/problems/" + questionNumber,
                new BigDecimal("1"),
                new BigDecimal("2"),
                new BigDecimal(percentage),
                Instant.now(),
                ScrapingStatus.SUCCESS,
                null,
                "text-anchored"
        );
    }

    private static QuestionStats parseError(String questionNumber) {
        return new QuestionStats(
                questionNumber,
                questionNumber + "-name",
                "https://leetcode.com/problems/" + questionNumber,
                null,
                null,
                null,
                Instant.now(),
                ScrapingStatus.PARSE_ERROR,
                "malformed",
                null
        );
    }

    private static QuestionStats uninitialised(String questionNumber) {
        QuestionStats stats = new QuestionStats();
        stats.setQuestionNumber(questionNumber);
        stats.setProblemName(questionNumber + "-name");
        return stats;
    }
}
