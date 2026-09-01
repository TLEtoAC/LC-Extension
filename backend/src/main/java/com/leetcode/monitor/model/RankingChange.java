/*
 * File: RankingChange.java
 * Author: REST API Agent
 * Phase: Phase 3 — Backend: ContestConfigController + Model Classes
 * Purpose: Immutable domain model recording a ranking overtake event between contest questions.
 */

package com.leetcode.monitor.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record representing an overtake event where one contest question surpasses another in user acceptance percentage.
 *
 * @param description             Human-readable description of the overtake event (e.g., "Q3 overtook Q1").
 * @param questionNumberOvertaker Question slot identifier of the overtaking question (e.g., "Q3").
 * @param questionNumberOvertaken Question slot identifier of the overtaken question (e.g., "Q1").
 * @param timestamp               Timestamp when the overtake event was detected and recorded.
 */
public record RankingChange(
        String description,
        String questionNumberOvertaker,
        String questionNumberOvertaken,
        Instant timestamp
) {

    /**
     * Compact constructor validating ranking change fields.
     *
     * @param description             human-readable event description
     * @param questionNumberOvertaker overtaking question identifier
     * @param questionNumberOvertaken overtaken question identifier
     * @param timestamp               event timestamp
     */
    public RankingChange {
        Objects.requireNonNull(description, "description cannot be null");
        Objects.requireNonNull(questionNumberOvertaker, "questionNumberOvertaker cannot be null");
        Objects.requireNonNull(questionNumberOvertaken, "questionNumberOvertaken cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
    }
}
