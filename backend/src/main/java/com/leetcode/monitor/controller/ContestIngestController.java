/*
 * File: ContestIngestController.java
 * Author: REST API Agent
 * Phase: Phase 4 — Backend: ContestIngestController & Ingest DTOs
 * Purpose: REST controller receiving scraped problem submission data from the Chrome Extension.
 */

package com.leetcode.monitor.controller;

import com.leetcode.monitor.dto.ContestIngestRequest;
import com.leetcode.monitor.model.QuestionStats;
import com.leetcode.monitor.model.ScrapingStatus;
import com.leetcode.monitor.service.ContestStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * REST controller responsible for receiving and processing scraped question statistics.
 */
@RestController
@RequestMapping("/api/contest")
public class ContestIngestController {

    private static final Logger logger = LoggerFactory.getLogger(ContestIngestController.class);
    private static final Set<String> VALID_QUESTION_NUMBERS = Set.of("Q1", "Q2", "Q3", "Q4");

    private final ContestStateService contestStateService;

    /**
     * Constructs a new ContestIngestController with the required ContestStateService.
     *
     * @param contestStateService the service managing contest state and ingestion processing
     */
    public ContestIngestController(ContestStateService contestStateService) {
        this.contestStateService = contestStateService;
    }

    /**
     * Ingests scraped submission metrics for a specific question slot.
     * Validates that the question slot is one of Q1, Q2, Q3, or Q4, and that the payload contains a valid scraping status.
     *
     * @param questionNumber the question slot identifier (e.g. "Q1", "Q2", "Q3", "Q4")
     * @param request        the inbound ingestion payload
     * @return 200 OK with the updated {@link QuestionStats} on success, or 400 Bad Request on invalid input/state
     */
    @PostMapping("/ingest/{questionNumber}")
    public ResponseEntity<?> ingestQuestion(
            @PathVariable String questionNumber,
            @RequestBody(required = false) ContestIngestRequest request) {

        logger.info("Received POST /api/contest/ingest/{} request", questionNumber);

        if (questionNumber == null || !VALID_QUESTION_NUMBERS.contains(questionNumber.trim().toUpperCase())) {
            logger.error("Ingest failed: invalid questionNumber '{}'", questionNumber);
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "questionNumber must be one of Q1, Q2, Q3, Q4");
        }

        String normalizedQuestionNumber = questionNumber.trim().toUpperCase();

        if (request == null) {
            logger.error("Ingest failed: request body is missing for question {}", normalizedQuestionNumber);
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "Request body is required");
        }

        if (request.getScrapingStatus() == null) {
            logger.error("Ingest failed: scrapingStatus is missing for question {}", normalizedQuestionNumber);
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "scrapingStatus is required");
        }

        if (request.getScrapingStatus() == ScrapingStatus.SUCCESS) {
            if (request.getRawUsersAccepted() == null || request.getRawUsersAccepted().trim().isEmpty()) {
                logger.error("Ingest failed: rawUsersAccepted is blank for SUCCESS status on question {}", normalizedQuestionNumber);
                return buildErrorResponse(HttpStatus.BAD_REQUEST, "rawUsersAccepted must not be blank when scrapingStatus is SUCCESS");
            }
        }

        if (contestStateService.getCurrentStats() == null) {
            logger.error("Ingest failed: contest has not been configured prior to ingest");
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "Contest is not configured. Please initialize contest configuration first.");
        }

        try {
            QuestionStats updatedStats = contestStateService.applyIngest(normalizedQuestionNumber, request);
            logger.info("Successfully ingested stats for question {}", normalizedQuestionNumber);
            return ResponseEntity.ok(updatedStats);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.error("Ingest failed for question {}: {}", normalizedQuestionNumber, ex.getMessage());
            return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("status", "ERROR");
        errorBody.put("message", message);
        return ResponseEntity.status(status).body(errorBody);
    }
}
