/*
 * File: ContestHealthController.java
 * Author: REST API Agent
 * Phase: Phase 1 — Backend Skeleton
 * Purpose: Provides a health check endpoint for the Chrome Extension to verify backend availability.
 */

package com.leetcode.monitor.controller;

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

    /**
     * Handles health check requests from clients (e.g., Chrome Extension).
     *
     * @return a ResponseEntity containing health status ("OK"), backendVersion ("1.0.0"), and current ISO-8601 timestamp
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        logger.info("Received GET /api/contest/health request");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "OK");
        response.put("backendVersion", BACKEND_VERSION);
        response.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(response);
    }
}
