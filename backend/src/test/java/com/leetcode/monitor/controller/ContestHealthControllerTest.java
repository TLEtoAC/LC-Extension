/*
 * File: ContestHealthControllerTest.java
 * Author: REST API Agent
 * Phase: Phase 1 — Backend Skeleton
 * Purpose: Unit and integration tests for ContestHealthController.
 */

package com.leetcode.monitor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
