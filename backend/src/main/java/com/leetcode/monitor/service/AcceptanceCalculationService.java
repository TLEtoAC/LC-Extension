/*
 * File: AcceptanceCalculationService.java
 * Author: Backend/Core Agent
 * Phase: Phase 6 — Four Questions + Concurrency
 * Purpose: BigDecimal-only acceptance percentage calculation (never double).
 */

package com.leetcode.monitor.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Calculates Users Accepted percentage using exact {@link BigDecimal} arithmetic.
 *
 * <p>Formula: {@code acceptedUsers / totalUsers × 100}, scale 10, {@link RoundingMode#HALF_UP}.
 * Scale is applied to the percentage result (multiply by 100, then divide) so
 * {@code 28903 / 31100} yields {@code 92.9356913183}, not an intermediate-ratio rounding of
 * {@code 0.9293569132 × 100 = 92.9356913200}.</p>
 */
@Service
public class AcceptanceCalculationService {

    private static final int CALCULATION_SCALE = 10;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Computes {@code acceptedUsers / totalUsers × 100} with scale 10 and HALF_UP rounding.
     *
     * @param acceptedUsers count of users who solved the problem
     * @param totalUsers    count of users who submitted
     * @return acceptance percentage
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code totalUsers} is zero
     */
    public BigDecimal calculatePercentage(BigDecimal acceptedUsers, BigDecimal totalUsers) {
        Objects.requireNonNull(acceptedUsers, "acceptedUsers cannot be null");
        Objects.requireNonNull(totalUsers, "totalUsers cannot be null");

        if (totalUsers.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("totalUsers cannot be zero");
        }

        return acceptedUsers.multiply(HUNDRED).divide(totalUsers, CALCULATION_SCALE, ROUNDING);
    }
}
