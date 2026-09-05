/*
 * File: ComparisonService.java
 * Author: Backend/Core Agent
 * Phase: Phase 9 — Overtaking Detection
 * Purpose: Pairwise percentage comparison inside the ContestStateService
 *          critical section (Section 17 / 18 / 7A-2). Emits a RankingChange
 *          only on a Qx <= Qy → Qx > Qy transition (ties included).
 *
 * Dedup: last-known pairwise relationships live on the ContestStats snapshot.
 * Unchanged pairs emit nothing. Null percentages (PARSE_ERROR, timeout)
 * skip that pair and keep the previous relationship so other pairs still work.
 *
 * No I/O. BigDecimal.compareTo only.
 */

package com.leetcode.monitor.service;

import com.leetcode.monitor.model.QuestionStats;
import com.leetcode.monitor.model.RankingChange;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects pairwise overtakes between contest questions.
 *
 * <p>Must be invoked from the synchronized {@code applyIngest} critical section
 * so the "before" snapshot cannot mutate mid-comparison.</p>
 */
@Service
public class ComparisonService {

    public static final String REL_GT = "GT";
    public static final String REL_LT = "LT";
    public static final String REL_EQ = "EQ";

    /** Newest-first history cap so the snapshot cannot grow without bound. */
    static final int MAX_RECENT_CHANGES = 20;

    /**
     * Result of one comparison pass — both collections are published on the snapshot.
     *
     * @param recentChanges         newest-first overtake events
     * @param pairwiseRelationships canonical {@code QavsQb} key → how Qa compares to Qb (GT|LT|EQ)
     */
    public record ComparisonOutcome(
            List<RankingChange> recentChanges,
            Map<String, String> pairwiseRelationships
    ) {
    }

    /**
     * Compares previous vs current question percentages and returns new events
     * plus the updated pairwise map.
     *
     * @param previousQuestions previous snapshot questions (not mutated)
     * @param currentQuestions  deep-copied questions after this ingest
     * @param previousPairwise  last-known relationships from the previous snapshot
     * @param previousChanges   existing recent-change history
     * @return outcome to publish on the new snapshot
     */
    public ComparisonOutcome detectOvertakes(
            List<QuestionStats> previousQuestions,
            List<QuestionStats> currentQuestions,
            Map<String, String> previousPairwise,
            List<RankingChange> previousChanges
    ) {
        Map<String, QuestionStats> currentBySlot = indexBySlot(currentQuestions);
        Map<String, String> nextPairwise = new LinkedHashMap<>();
        if (previousPairwise != null) {
            nextPairwise.putAll(previousPairwise);
        }

        List<RankingChange> newEvents = new ArrayList<>();
        List<String> slots = new ArrayList<>(currentBySlot.keySet());
        slots.sort(String.CASE_INSENSITIVE_ORDER);

        Instant now = Instant.now();
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                String left = slots.get(i);
                String right = slots.get(j);
                String key = pairKey(left, right);

                BigDecimal leftPct = percentageOf(currentBySlot.get(left));
                BigDecimal rightPct = percentageOf(currentBySlot.get(right));
                if (leftPct == null || rightPct == null) {
                    // Keep previous relationship; do not emit or wipe other pairs.
                    continue;
                }

                String currentRel = relation(leftPct, rightPct);
                String previousRel = nextPairwise.get(key);
                nextPairwise.put(key, currentRel);

                RankingChange event = overtakeEvent(left, right, previousRel, currentRel, now);
                if (event != null) {
                    newEvents.add(event);
                }
            }
        }

        List<RankingChange> merged = mergeRecentChanges(newEvents, previousChanges);
        return new ComparisonOutcome(merged, nextPairwise);
    }

    /**
     * Canonical pair key, lower question number first (e.g. {@code Q1vsQ3}).
     *
     * @param first  one slot
     * @param second the other slot
     * @return stable map key
     */
    public static String pairKey(String first, String second) {
        String a = normalizeSlot(first);
        String b = normalizeSlot(second);
        if (a.compareToIgnoreCase(b) <= 0) {
            return a + "vs" + b;
        }
        return b + "vs" + a;
    }

    /**
     * Section 18: emit only Qx &lt;= Qy → Qx &gt; Qy (EQ counts as &lt;=).
     */
    private static RankingChange overtakeEvent(
            String left,
            String right,
            String previousRel,
            String currentRel,
            Instant timestamp
    ) {
        if (previousRel == null || previousRel.equals(currentRel)) {
            return null;
        }
        // left overtook right: left was <= right (LT or EQ), now left > right (GT)
        if ((REL_LT.equals(previousRel) || REL_EQ.equals(previousRel)) && REL_GT.equals(currentRel)) {
            return new RankingChange(left + " overtook " + right, left, right, timestamp);
        }
        // right overtook left: right was <= left (GT or EQ), now right > left (LT)
        if ((REL_GT.equals(previousRel) || REL_EQ.equals(previousRel)) && REL_LT.equals(currentRel)) {
            return new RankingChange(right + " overtook " + left, right, left, timestamp);
        }
        return null;
    }

    private static String relation(BigDecimal left, BigDecimal right) {
        int cmp = left.compareTo(right);
        if (cmp > 0) {
            return REL_GT;
        }
        if (cmp < 0) {
            return REL_LT;
        }
        return REL_EQ;
    }

    private static List<RankingChange> mergeRecentChanges(
            List<RankingChange> newEvents,
            List<RankingChange> previousChanges
    ) {
        List<RankingChange> merged = new ArrayList<>(newEvents);
        if (previousChanges != null) {
            merged.addAll(previousChanges);
        }
        if (merged.size() > MAX_RECENT_CHANGES) {
            return new ArrayList<>(merged.subList(0, MAX_RECENT_CHANGES));
        }
        return merged;
    }

    private static Map<String, QuestionStats> indexBySlot(List<QuestionStats> questions) {
        Map<String, QuestionStats> bySlot = new LinkedHashMap<>();
        if (questions == null) {
            return bySlot;
        }
        for (QuestionStats stats : questions) {
            if (stats != null && stats.getQuestionNumber() != null) {
                bySlot.put(normalizeSlot(stats.getQuestionNumber()), stats);
            }
        }
        return bySlot;
    }

    private static BigDecimal percentageOf(QuestionStats stats) {
        return stats == null ? null : stats.getUsersAcceptedPercentage();
    }

    private static String normalizeSlot(String slot) {
        return slot == null ? "" : slot.trim().toUpperCase(Locale.ROOT);
    }
}
