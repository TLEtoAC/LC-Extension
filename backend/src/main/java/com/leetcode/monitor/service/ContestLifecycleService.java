/*
 * File: ContestLifecycleService.java
 * Author: Backend/Core Agent
 * Phase: Phase 11 — Robustness + Lifecycle
 * Purpose: Signal-based ENDED detection (ADR-006): if all four percentages
 *          are unchanged across 3 consecutive complete ingest cycles, mark
 *          lifecycle ENDED. Called only from the applyIngest critical section.
 *
 * A "cycle" is one complete Q1–Q4 ingest round. The first identical round
 * starts the streak; the third consecutive identical round returns ENDED
 * (≈15 minutes at the default 5-minute interval).
 *
 * Null percentages (PARSE_ERROR, timeout) do not count toward ENDED and
 * reset the streak so a scrape failure cannot false-end a live contest.
 *
 * No I/O. Mutable counters are safe because applyIngest is synchronized.
 */

package com.leetcode.monitor.service;

import com.leetcode.monitor.model.QuestionStats;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tracks complete ingest rounds and decides MONITORING vs ENDED.
 */
@Service
public class ContestLifecycleService {

    public static final int UNCHANGED_CYCLES_TO_END = 3;
    public static final String STATE_INITIALISED = "INITIALISED";
    public static final String STATE_MONITORING = "MONITORING";
    public static final String STATE_ENDED = "ENDED";

    private static final List<String> SLOTS = List.of("Q1", "Q2", "Q3", "Q4");

    private final Set<String> seenThisRound = new LinkedHashSet<>();
    private String lastFingerprint;
    private int identicalCycleStreak;

    /**
     * Clears round tracking. Call from {@code initializeContest} (critical section).
     */
    public void reset() {
        seenThisRound.clear();
        lastFingerprint = null;
        identicalCycleStreak = 0;
    }

    /**
     * Records the ingested slot and, after a complete Q1–Q4 round, updates
     * the unchanged-cycle streak.
     *
     * @param currentLifecycle previous snapshot lifecycle
     * @param questions        post-ingest question copies
     * @param ingestedSlot     slot just applied (Q1–Q4)
     * @return next lifecycle state to publish
     */
    public String nextLifecycleState(
            String currentLifecycle,
            List<QuestionStats> questions,
            String ingestedSlot
    ) {
        if (STATE_ENDED.equals(currentLifecycle)) {
            return STATE_ENDED;
        }

        String monitoring = STATE_INITIALISED.equals(currentLifecycle) ? STATE_MONITORING : currentLifecycle;
        if (ingestedSlot != null) {
            seenThisRound.add(ingestedSlot.trim().toUpperCase(Locale.ROOT));
        }
        if (!seenThisRound.containsAll(SLOTS)) {
            return monitoring;
        }

        seenThisRound.clear();
        if (!allHavePercentages(questions)) {
            lastFingerprint = null;
            identicalCycleStreak = 0;
            return monitoring;
        }

        String fingerprint = fingerprint(questions);
        if (fingerprint.equals(lastFingerprint)) {
            identicalCycleStreak++;
        } else {
            lastFingerprint = fingerprint;
            identicalCycleStreak = 1;
        }

        if (identicalCycleStreak >= UNCHANGED_CYCLES_TO_END) {
            return STATE_ENDED;
        }
        return monitoring;
    }

    int getIdenticalCycleStreak() {
        return identicalCycleStreak;
    }

    private static boolean allHavePercentages(List<QuestionStats> questions) {
        if (questions == null || questions.size() < SLOTS.size()) {
            return false;
        }
        return questions.stream().allMatch(qs -> qs != null && qs.getUsersAcceptedPercentage() != null);
    }

    /**
     * Stable fingerprint of the four percentages. BigDecimal plain string — no double.
     */
    static String fingerprint(List<QuestionStats> questions) {
        return questions.stream()
                .map(qs -> {
                    String slot = qs.getQuestionNumber() == null ? "?" : qs.getQuestionNumber().toUpperCase(Locale.ROOT);
                    BigDecimal pct = qs.getUsersAcceptedPercentage();
                    return slot + "=" + (pct == null ? "null" : pct.toPlainString());
                })
                .sorted()
                .collect(Collectors.joining("|"));
    }
}
