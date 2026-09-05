/*
 * File: ContestStateServiceRankingTest.java
 * Author: Testing/QA Agent
 * Phase: Phase 8 — State + Ranking
 * Purpose: Ranking is computed inside synchronized applyIngest and published
 *          on the same ContestStats snapshot (Section 7A-2).
 */

package com.leetcode.monitor.service;

import com.leetcode.monitor.dto.ContestIngestRequest;
import com.leetcode.monitor.model.ContestStats;
import com.leetcode.monitor.model.Question;
import com.leetcode.monitor.model.ScrapingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@DisplayName("ContestStateService ranking publish")
class ContestStateServiceRankingTest {

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
    @DisplayName("initializeContest leaves ranking empty")
    void initializeLeavesRankingEmpty() {
        assertTrue(contestStateService.getCurrentStats().getRanking().isEmpty());
    }

    @Test
    @DisplayName("applyIngest publishes ranking on the same snapshot")
    void applyIngestPublishesRankingOnSnapshot() {
        contestStateService.applyIngest("Q1", success("100 / 200"));
        contestStateService.applyIngest("Q2", success("300 / 400"));
        contestStateService.applyIngest("Q3", success("50 / 100"));
        contestStateService.applyIngest("Q4", success("28,903 / 31.1K"));

        ContestStats snapshot = contestStateService.getCurrentStats();
        // Q4 92.93, Q2 75, Q1 50, Q3 50 → tie Q1 before Q3
        assertEquals(List.of("Q4", "Q2", "Q1", "Q3"), snapshot.getRanking());
        assertEquals("MONITORING", snapshot.getLifecycleState());
    }

    @Test
    @DisplayName("PARSE_ERROR slot is ranked last; other slots still rank")
    void parseErrorDoesNotCrashRanking() {
        contestStateService.applyIngest("Q1", success("100 / 200"));
        contestStateService.applyIngest("Q2", new ContestIngestRequest("not-a-ratio", ScrapingStatus.SUCCESS));
        contestStateService.applyIngest("Q3", success("10 / 100"));
        contestStateService.applyIngest("Q4", success("90 / 100"));

        assertEquals(
                List.of("Q4", "Q1", "Q3", "Q2"),
                contestStateService.getCurrentStats().getRanking()
        );
    }

    private static ContestIngestRequest success(String raw) {
        return new ContestIngestRequest(raw, ScrapingStatus.SUCCESS);
    }
}
