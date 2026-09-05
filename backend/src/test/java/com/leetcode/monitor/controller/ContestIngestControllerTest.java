/*
 * File: ContestIngestControllerTest.java
 * Author: REST API Agent
 * Phase: Phase 4 — Backend: ContestIngestController & Ingest DTOs
 * Purpose: Integration and validation tests for ContestIngestController and ingest endpoints.
 */

package com.leetcode.monitor.controller;

import com.leetcode.monitor.dto.ContestIngestRequest;
import com.leetcode.monitor.model.ContestStats;
import com.leetcode.monitor.model.Question;
import com.leetcode.monitor.model.QuestionStats;
import com.leetcode.monitor.model.ScrapingStatus;
import com.leetcode.monitor.parser.AcceptanceStatsParser;
import com.leetcode.monitor.service.AcceptanceCalculationService;
import com.leetcode.monitor.service.ContestStateService;
import com.leetcode.monitor.service.ComparisonService;
import com.leetcode.monitor.service.RankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test class verifying POST /api/contest/ingest/{questionNumber} endpoint validation and state handling.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ContestIngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContestStateService contestStateService;

    @BeforeEach
    public void setupContest() {
        List<Question> questions = List.of(
                new Question("Q1", "min-chairs", "https://leetcode.com/problems/min-chairs"),
                new Question("Q2", "count-days", "https://leetcode.com/problems/count-days"),
                new Question("Q3", "smallest-string", "https://leetcode.com/problems/smallest-string"),
                new Question("Q4", "bitwise-and", "https://leetcode.com/problems/bitwise-and")
        );
        contestStateService.initializeContest("https://leetcode.com/contest/weekly-400", questions);
    }

    /**
     * Tests successful ingestion of Q1 statistics with SUCCESS status.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testSuccessfulIngestQ1() throws Exception {
        String json = """
                {
                  "rawUsersAccepted": "28,903 / 31.1K",
                  "scrapingStatus": "SUCCESS",
                  "selectorStrategyUsed": "text-anchored"
                }
                """;

        mockMvc.perform(post("/api/contest/ingest/Q1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionNumber").value("Q1"))
                .andExpect(jsonPath("$.problemName").value("min-chairs"))
                .andExpect(jsonPath("$.scrapingStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.selectorStrategyUsed").value("text-anchored"))
                .andExpect(jsonPath("$.acceptedUsers").value(28903))
                .andExpect(jsonPath("$.totalUsers").value(31100))
                .andExpect(jsonPath("$.usersAcceptedPercentage").value(92.9356913183))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        ContestStats stats = contestStateService.getCurrentStats();
        assertNotNull(stats);
        assertEquals("MONITORING", stats.getLifecycleState());
        QuestionStats q1 = stats.getQuestions().get(0);
        assertEquals(ScrapingStatus.SUCCESS, q1.getScrapingStatus());
        assertEquals(0, new BigDecimal("28903").compareTo(q1.getAcceptedUsers()));
        assertEquals(0, new BigDecimal("31100").compareTo(q1.getTotalUsers()));
        assertEquals(0, new BigDecimal("92.9356913183").compareTo(q1.getUsersAcceptedPercentage()));
    }

    /**
     * Zero totalUsers is PARSE_ERROR — never store 0%.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testSuccessIngestWithZeroTotalUsersMarksParseError() throws Exception {
        String json = """
                {
                  "rawUsersAccepted": "10 / 0",
                  "scrapingStatus": "SUCCESS"
                }
                """;

        mockMvc.perform(post("/api/contest/ingest/Q1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionNumber").value("Q1"))
                .andExpect(jsonPath("$.scrapingStatus").value("PARSE_ERROR"));

        QuestionStats q1 = contestStateService.getCurrentStats().getQuestions().get(0);
        assertEquals(ScrapingStatus.PARSE_ERROR, q1.getScrapingStatus());
        assertNull(q1.getAcceptedUsers());
        assertNull(q1.getTotalUsers());
        assertNull(q1.getUsersAcceptedPercentage());
        assertNotNull(q1.getErrorMessage());
    }

    /**
     * Tests successful ingestion for Q2 with SELECTOR_NOT_FOUND error status.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testIngestSelectorNotFoundStatus() throws Exception {
        String json = """
                {
                  "scrapingStatus": "SELECTOR_NOT_FOUND",
                  "errorMessage": "Could not find users accepted element",
                  "selectorStrategyUsed": "fallback-chain-exhausted"
                }
                """;

        mockMvc.perform(post("/api/contest/ingest/Q2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionNumber").value("Q2"))
                .andExpect(jsonPath("$.scrapingStatus").value("SELECTOR_NOT_FOUND"))
                .andExpect(jsonPath("$.errorMessage").value("Could not find users accepted element"));

        ContestStats stats = contestStateService.getCurrentStats();
        assertEquals(ScrapingStatus.SELECTOR_NOT_FOUND, stats.getQuestions().get(1).getScrapingStatus());
        assertEquals("Could not find users accepted element", stats.getQuestions().get(1).getErrorMessage());
    }

    /**
     * Tests successful ingestion for Q3 with LOGIN_WALL status.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testIngestLoginWallStatus() throws Exception {
        String json = """
                {
                  "scrapingStatus": "LOGIN_WALL",
                  "errorMessage": "Sign-in prompt intercepted DOM"
                }
                """;

        mockMvc.perform(post("/api/contest/ingest/Q3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionNumber").value("Q3"))
                .andExpect(jsonPath("$.scrapingStatus").value("LOGIN_WALL"))
                .andExpect(jsonPath("$.errorMessage").value("Sign-in prompt intercepted DOM"));

        ContestStats stats = contestStateService.getCurrentStats();
        assertEquals(ScrapingStatus.LOGIN_WALL, stats.getQuestions().get(2).getScrapingStatus());
    }

    /**
     * Tests invalid question number in URL path.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testInvalidQuestionNumberReturnsBadRequest() throws Exception {
        String json = """
                {
                  "rawUsersAccepted": "100 / 200",
                  "scrapingStatus": "SUCCESS"
                }
                """;

        mockMvc.perform(post("/api/contest/ingest/Q5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message").value("questionNumber must be one of Q1, Q2, Q3, Q4"));

        mockMvc.perform(post("/api/contest/ingest/foo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"));
    }

    /**
     * Tests missing request body.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testEmptyRequestBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/contest/ingest/Q1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    /**
     * Tests missing scrapingStatus in payload.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testMissingScrapingStatusReturnsBadRequest() throws Exception {
        String json = """
                {
                  "rawUsersAccepted": "100 / 200"
                }
                """;

        mockMvc.perform(post("/api/contest/ingest/Q1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("scrapingStatus is required"));
    }

    /**
     * Tests SUCCESS scrapingStatus with missing rawUsersAccepted string.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testSuccessStatusWithBlankRawUsersAcceptedReturnsBadRequest() throws Exception {
        String json = """
                {
                  "scrapingStatus": "SUCCESS",
                  "rawUsersAccepted": "   "
                }
                """;

        mockMvc.perform(post("/api/contest/ingest/Q1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("rawUsersAccepted must not be blank when scrapingStatus is SUCCESS"));
    }

    /**
     * Tests ingest when contest is not configured.
     */
    @Test
    public void testIngestWhenContestNotConfiguredReturnsBadRequest() {
        ContestStateService uninitializedService = new ContestStateService(
                new AcceptanceStatsParser(),
                new AcceptanceCalculationService(),
                new RankingService(),
                new ComparisonService()
        );
        ContestIngestController controller = new ContestIngestController(uninitializedService);
        ContestIngestRequest request = new ContestIngestRequest("10 / 20", ScrapingStatus.SUCCESS);
        ResponseEntity<?> response = controller.ingestQuestion("Q1", request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
