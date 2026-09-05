/*
 * File: ComparisonServiceTest.java
 * Author: Testing/QA Agent
 * Phase: Phase 9 — Overtaking Detection
 * Purpose: Overtake, no-duplicate, tie, and PARSE_ERROR isolation.
 */

package com.leetcode.monitor.service;

import com.leetcode.monitor.model.QuestionStats;
import com.leetcode.monitor.model.RankingChange;
import com.leetcode.monitor.model.ScrapingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ComparisonService")
class ComparisonServiceTest {

    private final ComparisonService comparisonService = new ComparisonService();

    @Test
    @DisplayName("Q4 overtook Q3 when its percentage crosses above Q3")
    void detectsOvertake() {
        List<QuestionStats> previous = List.of(
                pct("Q1", "90"),
                pct("Q2", "80"),
                pct("Q3", "40"),
                pct("Q4", "30")
        );
        ComparisonService.ComparisonOutcome baseline = comparisonService.detectOvertakes(
                previous, previous, Map.of(), List.of()
        );
        assertTrue(baseline.recentChanges().isEmpty(), "First observation is not a transition");

        List<QuestionStats> current = List.of(
                pct("Q1", "90"),
                pct("Q2", "80"),
                pct("Q3", "40"),
                pct("Q4", "45")
        );
        ComparisonService.ComparisonOutcome outcome = comparisonService.detectOvertakes(
                previous, current, baseline.pairwiseRelationships(), baseline.recentChanges()
        );

        assertEquals(1, outcome.recentChanges().size());
        RankingChange event = outcome.recentChanges().get(0);
        assertEquals("Q4 overtook Q3", event.description());
        assertEquals("Q4", event.questionNumberOvertaker());
        assertEquals("Q3", event.questionNumberOvertaken());
        // Canonical key Q3vsQ4 stores Q3 compared to Q4; Q3 < Q4 after the overtake.
        assertEquals(ComparisonService.REL_LT, outcome.pairwiseRelationships().get("Q3vsQ4"));
    }

    @Test
    @DisplayName("Unchanged pair does not emit a second overtake")
    void noDuplicateOnUnchangedPair() {
        Map<String, String> alreadyLt = Map.of("Q3vsQ4", ComparisonService.REL_LT);
        List<RankingChange> existing = List.of(
                new RankingChange("Q4 overtook Q3", "Q4", "Q3", Instant.parse("2026-01-01T00:00:00Z"))
        );
        List<QuestionStats> current = List.of(
                pct("Q3", "40"),
                pct("Q4", "45")
        );

        ComparisonService.ComparisonOutcome again = comparisonService.detectOvertakes(
                current, current, alreadyLt, existing
        );

        assertEquals(1, again.recentChanges().size());
        assertEquals("Q4 overtook Q3", again.recentChanges().get(0).description());
    }

    @Test
    @DisplayName("Tie → later lead is an overtake; staying tied is not")
    void tieThenOvertake() {
        List<QuestionStats> tied = List.of(pct("Q1", "50"), pct("Q2", "50"));
        ComparisonService.ComparisonOutcome afterTie = comparisonService.detectOvertakes(
                tied, tied, Map.of(), List.of()
        );
        assertTrue(afterTie.recentChanges().isEmpty());
        assertEquals(ComparisonService.REL_EQ, afterTie.pairwiseRelationships().get("Q1vsQ2"));

        ComparisonService.ComparisonOutcome stillTied = comparisonService.detectOvertakes(
                tied, tied, afterTie.pairwiseRelationships(), afterTie.recentChanges()
        );
        assertTrue(stillTied.recentChanges().isEmpty());

        List<QuestionStats> q2Leads = List.of(pct("Q1", "50"), pct("Q2", "51"));
        ComparisonService.ComparisonOutcome afterLead = comparisonService.detectOvertakes(
                tied, q2Leads, stillTied.pairwiseRelationships(), stillTied.recentChanges()
        );
        assertEquals(1, afterLead.recentChanges().size());
        assertEquals("Q2 overtook Q1", afterLead.recentChanges().get(0).description());
    }

    @Test
    @DisplayName("One PARSE_ERROR does not wipe or invent overtakes for other pairs")
    void parseErrorDoesNotWipeOthers() {
        List<QuestionStats> previous = List.of(
                pct("Q1", "10"),
                pct("Q2", "20"),
                pct("Q3", "30"),
                pct("Q4", "40")
        );
        ComparisonService.ComparisonOutcome baseline = comparisonService.detectOvertakes(
                previous, previous, Map.of(), List.of()
        );

        List<QuestionStats> q1BrokenQ2OvertakesQ3 = List.of(
                parseError("Q1"),
                pct("Q2", "35"),
                pct("Q3", "30"),
                pct("Q4", "40")
        );
        ComparisonService.ComparisonOutcome outcome = comparisonService.detectOvertakes(
                previous, q1BrokenQ2OvertakesQ3, baseline.pairwiseRelationships(), List.of()
        );

        assertEquals(1, outcome.recentChanges().size());
        assertEquals("Q2 overtook Q3", outcome.recentChanges().get(0).description());
        assertEquals(ComparisonService.REL_LT, outcome.pairwiseRelationships().get("Q1vsQ2"));
        assertEquals(ComparisonService.REL_GT, outcome.pairwiseRelationships().get("Q2vsQ3"));
    }

    @Test
    @DisplayName("Lead → tie is not an overtake")
    void becomingTiedIsNotAnOvertake() {
        List<QuestionStats> q1Leads = List.of(pct("Q1", "60"), pct("Q2", "50"));
        ComparisonService.ComparisonOutcome baseline = comparisonService.detectOvertakes(
                q1Leads, q1Leads, Map.of(), List.of()
        );
        List<QuestionStats> tied = List.of(pct("Q1", "50"), pct("Q2", "50"));
        ComparisonService.ComparisonOutcome outcome = comparisonService.detectOvertakes(
                q1Leads, tied, baseline.pairwiseRelationships(), List.of()
        );
        assertTrue(outcome.recentChanges().isEmpty());
        assertEquals(ComparisonService.REL_EQ, outcome.pairwiseRelationships().get("Q1vsQ2"));
    }

    private static QuestionStats pct(String questionNumber, String percentage) {
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
}
