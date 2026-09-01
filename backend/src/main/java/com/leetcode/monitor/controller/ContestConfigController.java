/*
 * File: ContestConfigController.java
 * Author: REST API Agent
 * Phase: Phase 3 — Backend: ContestConfigController + Model Classes
 * Purpose: REST controller handling contest initialization requests from the Chrome Extension.
 */

package com.leetcode.monitor.controller;

import com.leetcode.monitor.dto.ContestConfigRequest;
import com.leetcode.monitor.model.Question;
import com.leetcode.monitor.service.ContestStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST controller responsible for configuring contest metadata and initializing contest tracking state.
 */
@RestController
@RequestMapping("/api/contest")
public class ContestConfigController {

    private static final Logger logger = LoggerFactory.getLogger(ContestConfigController.class);
    private static final Set<String> VALID_QUESTION_NUMBERS = Set.of("Q1", "Q2", "Q3", "Q4");

    private final ContestStateService contestStateService;

    /**
     * Constructs a new ContestConfigController with the required ContestStateService.
     *
     * @param contestStateService the service managing contest state and snapshot lifecycle
     */
    public ContestConfigController(ContestStateService contestStateService) {
        this.contestStateService = contestStateService;
    }

    /**
     * Configures the contest metadata and initializes question tracking.
     * Validates that the request includes a non-blank contest URL and exactly 4 valid question entries (Q1 through Q4).
     *
     * @param request the contest configuration request payload
     * @return 200 OK with configuration summary on success, or 400 Bad Request on invalid payload
     */
    @PostMapping("/config")
    public ResponseEntity<?> configureContest(@RequestBody(required = false) ContestConfigRequest request) {
        logger.info("Received POST /api/contest/config request");

        if (request == null) {
            logger.error("Configuration failed: request body is missing");
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "Request body is required");
        }

        if (request.getContestUrl() == null || request.getContestUrl().trim().isEmpty()) {
            logger.error("Configuration failed: contestUrl is missing or blank");
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "contestUrl must not be blank");
        }

        List<ContestConfigRequest.QuestionRequest> questionRequests = request.getQuestions();
        if (questionRequests == null || questionRequests.size() != 4) {
            logger.error("Configuration failed: questions count is not 4");
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "questions list must contain exactly 4 entries");
        }

        Set<String> seenQuestionNumbers = new HashSet<>();
        List<Question> domainQuestions = new ArrayList<>();

        for (ContestConfigRequest.QuestionRequest q : questionRequests) {
            if (q == null) {
                logger.error("Configuration failed: null question entry encountered");
                return buildErrorResponse(HttpStatus.BAD_REQUEST, "Question entry must not be null");
            }

            String qNum = q.getQuestionNumber() != null ? q.getQuestionNumber().trim() : null;
            String name = q.getProblemName() != null ? q.getProblemName().trim() : null;
            String url = q.getProblemUrl() != null ? q.getProblemUrl().trim() : null;

            if (qNum == null || qNum.isEmpty()) {
                logger.error("Configuration failed: questionNumber is blank");
                return buildErrorResponse(HttpStatus.BAD_REQUEST, "questionNumber must not be blank");
            }

            if (!VALID_QUESTION_NUMBERS.contains(qNum)) {
                logger.error("Configuration failed: invalid questionNumber '{}'", qNum);
                return buildErrorResponse(HttpStatus.BAD_REQUEST, "questionNumber must be one of Q1, Q2, Q3, Q4");
            }

            if (seenQuestionNumbers.contains(qNum)) {
                logger.error("Configuration failed: duplicate questionNumber '{}'", qNum);
                return buildErrorResponse(HttpStatus.BAD_REQUEST, "Duplicate questionNumber found: " + qNum);
            }
            seenQuestionNumbers.add(qNum);

            if (name == null || name.isEmpty()) {
                logger.error("Configuration failed: problemName is blank for {}", qNum);
                return buildErrorResponse(HttpStatus.BAD_REQUEST, "problemName must not be blank for " + qNum);
            }

            if (url == null || url.isEmpty()) {
                logger.error("Configuration failed: problemUrl is blank for {}", qNum);
                return buildErrorResponse(HttpStatus.BAD_REQUEST, "problemUrl must not be blank for " + qNum);
            }

            domainQuestions.add(new Question(qNum, name, url));
        }

        contestStateService.initializeContest(request.getContestUrl().trim(), domainQuestions);

        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("status", "CONFIGURED");
        responseBody.put("questionCount", 4);

        logger.info("Successfully configured contest for URL: {}", request.getContestUrl());
        return ResponseEntity.ok(responseBody);
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("status", "ERROR");
        errorBody.put("message", message);
        return ResponseEntity.status(status).body(errorBody);
    }
}
