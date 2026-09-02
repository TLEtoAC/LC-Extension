/*
 * File: ParsedAcceptance.java
 * Author: Parser/Data Agent
 * Phase: Phase 5 — Parser Hardening
 * Purpose: Immutable record representing parsed numeric acceptance statistics (accepted users and total submitting users).
 */

package com.leetcode.monitor.parser;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable record representing parsed numeric acceptance counts extracted from raw LeetCode submission strings.
 *
 * <p><strong>Approximation Caveat:</strong> The values encapsulated in this record are approximations
 * derived from LeetCode's rounded display strings (e.g. {@code "31.1K"} parses to {@code 31100}, representing an
 * underlying integer in the range [31050, 31149]). Exact integer counts are only available when LeetCode displays
 * raw integers without abbreviation suffixes.</p>
 *
 * <p><strong>BigDecimal Rationale:</strong> All numeric counts are stored as {@link BigDecimal} instances to prevent
 * floating-point drift, IEEE-754 precision loss, and integer overflow that can occur when converting large contest metrics
 * using standard {@code double} or {@code float} types.</p>
 *
 * @param acceptedUsers the count of distinct users who successfully solved the problem (never {@code null})
 * @param totalUsers    the count of distinct users who made submissions to the problem (never {@code null})
 */
public record ParsedAcceptance(BigDecimal acceptedUsers, BigDecimal totalUsers) {

    /**
     * Compact constructor validating that neither {@code acceptedUsers} nor {@code totalUsers} is null.
     *
     * @param acceptedUsers count of accepted users
     * @param totalUsers    count of total submitting users
     * @throws NullPointerException if {@code acceptedUsers} or {@code totalUsers} is {@code null}
     */
    public ParsedAcceptance {
        Objects.requireNonNull(acceptedUsers, "acceptedUsers cannot be null");
        Objects.requireNonNull(totalUsers, "totalUsers cannot be null");
    }
}
