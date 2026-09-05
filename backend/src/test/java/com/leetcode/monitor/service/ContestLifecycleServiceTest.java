/*
 * File: ContestLifecycleServiceTest.java
 * Author: Testing/QA Agent
 * Phase: Phase 11 — Robustness + Lifecycle
 * Purpose: 3 unchanged cycles → ENDED; change resets; PARSE_ERROR does not end.
 */

package com.leetcode.monitor.service;

import com.leetcode.monitor.dto.ContestIngestRequest;
import com.leetcode.monitor.model.Question;
import com.leetcode.monitor.model.QuestionStats;
import com.leetcode.monitor.model.ScrapingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@DisplayName("ContestLifecycleService")
class ContestLifecycleServiceUnitTest {

    private final ContestLifecycleService lifecycle = new ContestLifecycleService();

    @Test
    @DisplayName("Three identical complete rounds mark ENDED")
    void threeUnchangedCyclesEnd() {
        lifecycle.reset();
        List<QuestionStats> same = List.of(pct("Q1", "10"), pct("Q2", "20"), pct("Q3", "30"), pct("Q4", "40"));
        assertEquals("MONITORING", applyRound(lifecycle, "INITIALISED", same));
        assertEquals("MONITORING", applyRound(lifecycle, "MONITORING", same));
        assertEquals("ENDED", applyRound(lifecycle, "MONITORING", same));
        assertEquals("ENDED", applyRound(lifecycle, "ENDED", same));
    }

    @Test
    @DisplayName("A changed percentage resets the unchanged streak")
    void changeResetsStreak() {
        lifecycle.reset();
        List<QuestionStats> first = List.of(pct("Q1", "10"), pct("Q2", "20"), pct("Q3", "30"), pct("Q4", "40"));
        List<QuestionStats> second = List.of(pct("Q1", "11"), pct("Q2", "20"), pct("Q3", "30"), pct("Q4", "40"));
        assertEquals("MONITORING", applyRound(lifecycle, "INITIALISED", first));
        assertEquals("MONITORING", applyRound(lifecycle, "MONITORING", second));
        assertEquals("MONITORING", applyRound(lifecycle, "MONITORING", second));
        assertEquals("ENDED", applyRound(lifecycle, "MONITORING", second));
    }

    @Test
    @DisplayName("A null percentage (PARSE_ERROR) does not count toward ENDED")
    void parseErrorDoesNotEnd() {
        lifecycle.reset();
        List<QuestionStats> broken = List.of(
                pct("Q1", "10"),
                parseError("Q2"),
                pct("Q3", "30"),
                pct("Q4", "40")
        );
        assertEquals("MONITORING", applyRound(lifecycle, "INITIALISED", broken));
        assertEquals("MONITORING", applyRound(lifecycle, "MONITORING", broken));
        assertEquals("MONITORING", applyRound(lifecycle, "MONITORING", broken));
        assertEquals("MONITORING", applyRound(lifecycle, "MONITORING", broken));
    }

    private static String applyRound(ContestLifecycleService service, String start, List<QuestionStats> questions) {
        String state = start;
        for (String slot : List.of("Q1", "Q2", "Q3", "Q4")) {
            state = service.nextLifecycleState(state, questions, slot);
        }
        return state;
    }

    private static QuestionStats pct(String slot, String percentage) {
        QuestionStats stats = new QuestionStats();
        stats.setQuestionNumber(slot);
        stats.setUsersAcceptedPercentage(new BigDecimal(percentage));
        stats.setScrapingStatus(ScrapingStatus.SUCCESS);
        return stats;
    }

    private static QuestionStats parseError(String slot) {
        QuestionStats stats = new QuestionStats();
        stats.setQuestionNumber(slot);
        stats.setScrapingStatus(ScrapingStatus.PARSE_ERROR);
        return stats;
    }
}

@SpringBootTest
@DisplayName("ContestStateService lifecycle publish")
class ContestStateServiceLifecycleTest {

    @Autowired
    private ContestStateService contestStateService;

    @BeforeEach
    void initializeContest() {
        contestStateService.initializeContest(
                "https://leetcode.com/contest/weekly-400",
                List.of(
                        new Question("Q1", "min-chairs", "https://leetcode.com/problems/min-chairs"),
                        new Question("Q2", "count-days", "https://leetcode.com/problems/count-days"),
                        new Question("Q3", "smallest-string", "https://leetcode.com/problems/smallest-string"),
                        new Question("Q4", "bitwise-and", "https://leetcode.com/problems/bitwise-and")
                )
        );
    }

    @Test
    @DisplayName("Three unchanged Q1–Q4 ingest rounds publish ENDED")
    void threeUnchangedIngestRoundsPublishEnded() {
        for (int round = 1; round <= 3; round++) {
            ingestAllSame();
        }
        assertEquals("ENDED", contestStateService.getCurrentStats().getLifecycleState());
    }

    @Test
    @DisplayName("A mid-streak change keeps MONITORING")
    void changeKeepsMonitoring() {
        ingestAllSame();
        contestStateService.applyIngest("Q1", success("90 / 100"));
        contestStateService.applyIngest("Q2", success("80 / 100"));
        contestStateService.applyIngest("Q3", success("40 / 100"));
        contestStateService.applyIngest("Q4", success("30 / 100"));
        assertNotEquals("ENDED", contestStateService.getCurrentStats().getLifecycleState());
        assertEquals("MONITORING", contestStateService.getCurrentStats().getLifecycleState());
    }

    private void ingestAllSame() {
        contestStateService.applyIngest("Q1", success("90 / 100"));
        contestStateService.applyIngest("Q2", success("80 / 100"));
        contestStateService.applyIngest("Q3", success("40 / 100"));
        contestStateService.applyIngest("Q4", success("30 / 100"));
    }

    private static ContestIngestRequest success(String raw) {
        return new ContestIngestRequest(raw, ScrapingStatus.SUCCESS);
    }
}
