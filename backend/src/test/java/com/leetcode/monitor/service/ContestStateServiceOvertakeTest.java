/*
 * File: ContestStateServiceOvertakeTest.java
 * Author: Testing/QA Agent
 * Phase: Phase 9 — Overtaking Detection
 * Purpose: Overtakes are computed inside synchronized applyIngest and published
 *          on the same snapshot as ranking (Section 7A-2).
 */

package com.leetcode.monitor.service;

import com.leetcode.monitor.dto.ContestIngestRequest;
import com.leetcode.monitor.model.ContestStats;
import com.leetcode.monitor.model.Question;
import com.leetcode.monitor.model.RankingChange;
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
@DisplayName("ContestStateService overtake publish")
class ContestStateServiceOvertakeTest {

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
    @DisplayName("applyIngest records an overtake on the published snapshot")
    void applyIngestPublishesOvertake() {
        contestStateService.applyIngest("Q1", success("90 / 100"));
        contestStateService.applyIngest("Q2", success("80 / 100"));
        contestStateService.applyIngest("Q3", success("40 / 100"));
        contestStateService.applyIngest("Q4", success("30 / 100"));
        assertTrue(contestStateService.getCurrentStats().getRecentChanges().isEmpty());

        contestStateService.applyIngest("Q4", success("45 / 100"));

        ContestStats snapshot = contestStateService.getCurrentStats();
        assertEquals(1, snapshot.getRecentChanges().size());
        RankingChange event = snapshot.getRecentChanges().get(0);
        assertEquals("Q4 overtook Q3", event.description());
        assertEquals(List.of("Q1", "Q2", "Q4", "Q3"), snapshot.getRanking());
        assertEquals(ComparisonService.REL_LT, snapshot.getPairwiseRelationships().get("Q3vsQ4"));
    }

    @Test
    @DisplayName("Re-ingesting the same percentages does not duplicate the overtake")
    void noDuplicateAfterUnchangedIngest() {
        contestStateService.applyIngest("Q3", success("40 / 100"));
        contestStateService.applyIngest("Q4", success("30 / 100"));
        contestStateService.applyIngest("Q4", success("45 / 100"));
        contestStateService.applyIngest("Q4", success("45 / 100"));

        assertEquals(1, contestStateService.getCurrentStats().getRecentChanges().size());
    }

    private static ContestIngestRequest success(String raw) {
        return new ContestIngestRequest(raw, ScrapingStatus.SUCCESS);
    }
}
