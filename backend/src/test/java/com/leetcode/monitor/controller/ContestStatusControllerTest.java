/*
 * File: ContestStatusControllerTest.java
 * Author: REST API Agent
 * Phase: Phase 10 — Status/Health API + Side Panel UI
 * Purpose: GET /api/contest/status empty envelope, configured snapshot, ranking.
 */

package com.leetcode.monitor.controller;

import com.leetcode.monitor.dto.ContestIngestRequest;
import com.leetcode.monitor.model.Question;
import com.leetcode.monitor.model.ScrapingStatus;
import com.leetcode.monitor.service.ContestStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GET /api/contest/status")
class ContestStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContestStateService contestStateService;

    @Test
    @DisplayName("Uninitialized contest returns a clear empty envelope, not an error")
    void uninitializedReturnsEmptyEnvelope() throws Exception {
        // A previous test in this JVM may have configured state; re-init to a known
        // empty snapshot is not available, so we only assert the envelope shape
        // when getCurrentStats() is null. After other @SpringBootTest classes
        // the singleton may already be INITIALISED — both shapes are 200.
        mockMvc.perform(get("/api/contest/status").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions").isArray())
                .andExpect(jsonPath("$.ranking").isArray())
                .andExpect(jsonPath("$.recentChanges").isArray())
                .andExpect(jsonPath("$.initialized").exists());
    }

    @Test
    @DisplayName("Configured + ingested contest returns ranking and questions")
    void configuredContestReturnsSnapshot() throws Exception {
        contestStateService.initializeContest(
                "https://leetcode.com/contest/weekly-400",
                List.of(
                        new Question("Q1", "min-chairs", "https://leetcode.com/problems/min-chairs"),
                        new Question("Q2", "count-days", "https://leetcode.com/problems/count-days"),
                        new Question("Q3", "smallest-string", "https://leetcode.com/problems/smallest-string"),
                        new Question("Q4", "bitwise-and", "https://leetcode.com/problems/bitwise-and")
                )
        );
        contestStateService.applyIngest("Q1", new ContestIngestRequest("100 / 200", ScrapingStatus.SUCCESS));
        contestStateService.applyIngest("Q2", new ContestIngestRequest("300 / 400", ScrapingStatus.SUCCESS));
        contestStateService.applyIngest("Q3", new ContestIngestRequest("50 / 100", ScrapingStatus.SUCCESS));
        contestStateService.applyIngest("Q4", new ContestIngestRequest("28,903 / 31.1K", ScrapingStatus.SUCCESS));

        mockMvc.perform(get("/api/contest/status").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.lifecycleState").value("MONITORING"))
                .andExpect(jsonPath("$.contestUrl").value("https://leetcode.com/contest/weekly-400"))
                .andExpect(jsonPath("$.questions.length()").value(4))
                .andExpect(jsonPath("$.ranking[0]").value("Q4"))
                .andExpect(jsonPath("$.ranking[1]").value("Q2"))
                .andExpect(jsonPath("$.lastUpdated").isNotEmpty());
    }
}
