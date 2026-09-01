/*
 * File: ContestConfigControllerTest.java
 * Author: REST API Agent
 * Phase: Phase 3 — Backend: ContestConfigController + Model Classes
 * Purpose: Integration and unit tests for ContestConfigController and contest configuration API.
 */

package com.leetcode.monitor.controller;

import com.leetcode.monitor.model.ContestStats;
import com.leetcode.monitor.service.ContestStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test class verifying POST /api/contest/config validation rules and state initialization.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ContestConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContestStateService contestStateService;

    private static final String VALID_CONFIG_JSON = """
            {
              "contestUrl": "https://leetcode.com/contest/weekly-contest-400",
              "questions": [
                {
                  "questionNumber": "Q1",
                  "problemName": "minimum-chairs",
                  "problemUrl": "https://leetcode.com/contest/weekly-contest-400/problems/minimum-number-of-chairs-in-a-waiting-room/"
                },
                {
                  "questionNumber": "Q2",
                  "problemName": "count-days-without-meetings",
                  "problemUrl": "https://leetcode.com/contest/weekly-contest-400/problems/count-days-without-meetings/"
                },
                {
                  "questionNumber": "Q3",
                  "problemName": "lexicographically-smallest-string",
                  "problemUrl": "https://leetcode.com/contest/weekly-contest-400/problems/lexicographically-smallest-string-after-a-swap/"
                },
                {
                  "questionNumber": "Q4",
                  "problemName": "find-subarray-with-bitwise-and",
                  "problemUrl": "https://leetcode.com/contest/weekly-contest-400/problems/find-subarray-with-bitwise-and-closest-to-k/"
                }
              ]
            }
            """;

    /**
     * Tests successful configuration with valid payload and verifies initialized contest state.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testValidContestConfigReturnsOkAndInitializesState() throws Exception {
        mockMvc.perform(post("/api/contest/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CONFIG_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIGURED"))
                .andExpect(jsonPath("$.questionCount").value(4));

        ContestStats stats = contestStateService.getCurrentStats();
        assertNotNull(stats);
        assertEquals("https://leetcode.com/contest/weekly-contest-400", stats.getContestUrl());
        assertEquals("INITIALISED", stats.getLifecycleState());
        assertEquals(4, stats.getQuestions().size());
        assertEquals("Q1", stats.getQuestions().get(0).getQuestionNumber());
        assertEquals("Q2", stats.getQuestions().get(1).getQuestionNumber());
        assertEquals("Q3", stats.getQuestions().get(2).getQuestionNumber());
        assertEquals("Q4", stats.getQuestions().get(3).getQuestionNumber());

        // Check pre-ingest null values
        assertNull(stats.getQuestions().get(0).getAcceptedUsers());
        assertNull(stats.getQuestions().get(0).getTotalUsers());
        assertNull(stats.getQuestions().get(0).getUsersAcceptedPercentage());
        assertNull(stats.getQuestions().get(0).getScrapingStatus());
    }

    /**
     * Tests validation error when request body is empty.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testEmptyRequestBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/contest/config")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"));
    }

    /**
     * Tests validation error when contestUrl is missing.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testMissingContestUrlReturnsBadRequest() throws Exception {
        String json = """
                {
                  "questions": [
                    {"questionNumber": "Q1", "problemName": "p1", "problemUrl": "u1"},
                    {"questionNumber": "Q2", "problemName": "p2", "problemUrl": "u2"},
                    {"questionNumber": "Q3", "problemName": "p3", "problemUrl": "u3"},
                    {"questionNumber": "Q4", "problemName": "p4", "problemUrl": "u4"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/contest/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("contestUrl must not be blank"));
    }

    /**
     * Tests validation error when questions count is not exactly 4.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testInvalidQuestionsCountReturnsBadRequest() throws Exception {
        String json = """
                {
                  "contestUrl": "https://leetcode.com/contest/weekly-300",
                  "questions": [
                    {"questionNumber": "Q1", "problemName": "p1", "problemUrl": "u1"},
                    {"questionNumber": "Q2", "problemName": "p2", "problemUrl": "u2"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/contest/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("questions list must contain exactly 4 entries"));
    }

    /**
     * Tests validation error when duplicate question numbers exist.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testDuplicateQuestionNumbersReturnsBadRequest() throws Exception {
        String json = """
                {
                  "contestUrl": "https://leetcode.com/contest/weekly-300",
                  "questions": [
                    {"questionNumber": "Q1", "problemName": "p1", "problemUrl": "u1"},
                    {"questionNumber": "Q1", "problemName": "p2", "problemUrl": "u2"},
                    {"questionNumber": "Q3", "problemName": "p3", "problemUrl": "u3"},
                    {"questionNumber": "Q4", "problemName": "p4", "problemUrl": "u4"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/contest/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Duplicate questionNumber found: Q1"));
    }

    /**
     * Tests validation error when question number is invalid.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testInvalidQuestionNumberReturnsBadRequest() throws Exception {
        String json = """
                {
                  "contestUrl": "https://leetcode.com/contest/weekly-300",
                  "questions": [
                    {"questionNumber": "Q1", "problemName": "p1", "problemUrl": "u1"},
                    {"questionNumber": "Q2", "problemName": "p2", "problemUrl": "u2"},
                    {"questionNumber": "Q3", "problemName": "p3", "problemUrl": "u3"},
                    {"questionNumber": "Q5", "problemName": "p5", "problemUrl": "u5"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/contest/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("questionNumber must be one of Q1, Q2, Q3, Q4"));
    }

    /**
     * Tests validation error when problemUrl is blank.
     *
     * @throws Exception if mockMvc request fails
     */
    @Test
    public void testBlankProblemUrlReturnsBadRequest() throws Exception {
        String json = """
                {
                  "contestUrl": "https://leetcode.com/contest/weekly-300",
                  "questions": [
                    {"questionNumber": "Q1", "problemName": "p1", "problemUrl": " "},
                    {"questionNumber": "Q2", "problemName": "p2", "problemUrl": "u2"},
                    {"questionNumber": "Q3", "problemName": "p3", "problemUrl": "u3"},
                    {"questionNumber": "Q4", "problemName": "p4", "problemUrl": "u4"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/contest/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("problemUrl must not be blank for Q1"));
    }
}
