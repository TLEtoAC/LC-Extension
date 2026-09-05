/*
 * File: ContestHealthController.java
 * Author: REST API Agent
 * Phase: Phase 10 — Status/Health API + Side Panel UI
 * Purpose: GET /api/contest/health — process liveness plus contest lifecycle,
 *          per-question scrape status, and lastIngestReceivedAt (Section 22.4).
 */

package com.leetcode.monitor.controller;

import com.leetcode.monitor.model.ContestStats;
import com.leetcode.monitor.model.QuestionStats;
import com.leetcode.monitor.service.ContestStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller exposing health check endpoints for the LeetCode Contest Monitor backend.
 */
@RestController
@RequestMapping("/api/contest")
public class ContestHealthController {

    private static final Logger logger = LoggerFactory.getLogger(ContestHealthController.class);
    private static final String BACKEND_VERSION = "1.0.0";

    private final ContestStateService contestStateService;

    /**
     * @param contestStateService lock-free snapshot source
     */
    public ContestHealthController(ContestStateService contestStateService) {
        this.contestStateService = contestStateService;
    }

    /**
     * Health plus monitoring diagnostics. Calling this endpoint from the
     * extension proves the backend is reachable; {@code lastIngestReceivedAt}
     * distinguishes a stalled extension from a stalled backend.
     *
     * @return 200 OK health payload
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        logger.info("Received GET /api/contest/health request");

        ContestStats snapshot = contestStateService.getCurrentStats();

        Map<String, Object> lastIngestReceivedAt = new LinkedHashMap<>();
        Map<String, Object> questionStatuses = new LinkedHashMap<>();
        for (String slot : new String[]{"Q1", "Q2", "Q3", "Q4"}) {
            lastIngestReceivedAt.put(slot, null);
            questionStatuses.put(slot, null);
        }

        String lifecycleState = "UNINITIALISED";
        String discoveryStatus = "UNINITIALISED";
        if (snapshot != null) {
            lifecycleState = snapshot.getLifecycleState();
            discoveryStatus = "CONFIGURED";
            for (QuestionStats qs : snapshot.getQuestions()) {
                if (qs.getQuestionNumber() == null) {
                    continue;
                }
                String slot = qs.getQuestionNumber().trim().toUpperCase();
                lastIngestReceivedAt.put(slot, qs.getTimestamp() != null ? qs.getTimestamp().toString() : null);
                questionStatuses.put(slot, qs.getScrapingStatus() != null ? qs.getScrapingStatus().name() : null);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "OK");
        response.put("backendVersion", BACKEND_VERSION);
        response.put("timestamp", Instant.now().toString());
        response.put("lifecycleState", lifecycleState);
        response.put("discoveryStatus", discoveryStatus);
        response.put("lastIngestReceivedAt", lastIngestReceivedAt);
        response.put("questionStatuses", questionStatuses);

        return ResponseEntity.ok(response);
    }
}
