/*
 * File: ContestHealthControllerTest.java
 * Author: REST API Agent
 * Phase: Phase 10 — Status/Health API + Side Panel UI
 * Purpose: GET /api/contest/health process + contest diagnostics.
 */

package com.leetcode.monitor.controller;

import com.leetcode.monitor.dto.ContestIngestRequest;
import com.leetcode.monitor.model.Question;
import com.leetcode.monitor.model.ScrapingStatus;
import com.leetcode.monitor.service.ContestStateService;
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

/**
 * Integration test verifying the health check endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ContestHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContestStateService contestStateService;

    /**
     * Verifies that GET /api/contest/health returns 200 OK with expected JSON keys and values.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testGetHealthReturnsOk() throws Exception {
        mockMvc.perform(get("/api/contest/health")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.backendVersion").value("1.0.0"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.lifecycleState").exists())
                .andExpect(jsonPath("$.discoveryStatus").exists())
                .andExpect(jsonPath("$.lastIngestReceivedAt.Q1").exists())
                .andExpect(jsonPath("$.questionStatuses").exists());
    }

    @Test
    public void testGetHealthAfterIngestIncludesLastIngestTimestamp() throws Exception {
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

        mockMvc.perform(get("/api/contest/health")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("MONITORING"))
                .andExpect(jsonPath("$.discoveryStatus").value("CONFIGURED"))
                .andExpect(jsonPath("$.lastIngestReceivedAt.Q1").isNotEmpty())
                .andExpect(jsonPath("$.questionStatuses.Q1").value("SUCCESS"));
    }
}
