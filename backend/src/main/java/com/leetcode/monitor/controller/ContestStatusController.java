/*
 * File: ContestStatusController.java
 * Author: REST API Agent
 * Phase: Phase 10 — Status/Health API + Side Panel UI
 * Purpose: Lock-free GET /api/contest/status from AtomicReference.get()
 *          (Section 7A-2 / 22.3). Does not enter the ingest critical section.
 */

package com.leetcode.monitor.controller;

import com.leetcode.monitor.model.ContestStats;
import com.leetcode.monitor.service.ContestStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes the current contest snapshot for the side panel and popup.
 */
@RestController
@RequestMapping("/api/contest")
public class ContestStatusController {

    private static final Logger logger = LoggerFactory.getLogger(ContestStatusController.class);

    private final ContestStateService contestStateService;

    /**
     * @param contestStateService snapshot owner; reads are lock-free
     */
    public ContestStatusController(ContestStateService contestStateService) {
        this.contestStateService = contestStateService;
    }

    /**
     * Returns the published {@link ContestStats} snapshot, or a clear empty
     * envelope when no contest has been configured.
     *
     * <p>Read path: {@code AtomicReference.get()} only — no lock (Section 7A-2).</p>
     *
     * @return 200 OK with snapshot fields plus {@code initialized}
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        logger.debug("Received GET /api/contest/status request");
        ContestStats snapshot = contestStateService.getCurrentStats();
        if (snapshot == null) {
            return ResponseEntity.ok(uninitializedEnvelope());
        }
        return ResponseEntity.ok(toStatusBody(snapshot));
    }

    /**
     * Empty state for the UI — not an error (Phase 10).
     */
    static Map<String, Object> uninitializedEnvelope() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("initialized", false);
        body.put("contestUrl", null);
        body.put("questions", Collections.emptyList());
        body.put("ranking", Collections.emptyList());
        body.put("recentChanges", Collections.emptyList());
        body.put("lastUpdated", null);
        body.put("lifecycleState", null);
        body.put("pairwiseRelationships", Collections.emptyMap());
        return body;
    }

    private static Map<String, Object> toStatusBody(ContestStats snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("initialized", true);
        body.put("contestUrl", snapshot.getContestUrl());
        body.put("questions", snapshot.getQuestions());
        body.put("ranking", snapshot.getRanking());
        body.put("recentChanges", snapshot.getRecentChanges());
        body.put("lastUpdated", snapshot.getLastUpdated());
        body.put("lifecycleState", snapshot.getLifecycleState());
        body.put("pairwiseRelationships", snapshot.getPairwiseRelationships());
        return body;
    }
}
