/*
 * File: AcceptanceCalculationServiceTest.java
 * Author: Testing/QA Agent
 * Phase: Phase 6 — Four Questions + Concurrency
 * Purpose: Unit tests for BigDecimal acceptance percentage calculation.
 */

package com.leetcode.monitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("AcceptanceCalculationService Unit Tests")
class AcceptanceCalculationServiceTest {

    private AcceptanceCalculationService service;

    @BeforeEach
    void setUp() {
        service = new AcceptanceCalculationService();
    }

    @Test
    @DisplayName("28903 / 31100 yields 92.9356913183 at scale 10 HALF_UP")
    void calculatePercentage_typicalKSuffixValues() {
        BigDecimal result = service.calculatePercentage(new BigDecimal("28903"), new BigDecimal("31100"));
        assertEquals(0, new BigDecimal("92.9356913183").compareTo(result));
        assertEquals(10, result.scale());
    }

    @Test
    @DisplayName("Zero totalUsers throws IllegalArgumentException")
    void calculatePercentage_zeroTotal_throws() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.calculatePercentage(new BigDecimal("10"), BigDecimal.ZERO)
        );
        assertEquals("totalUsers cannot be zero", ex.getMessage());
    }

    @Test
    @DisplayName("Null arguments throw NullPointerException")
    void calculatePercentage_nullArgs_throwsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> service.calculatePercentage(null, new BigDecimal("100"))
        );
        assertThrows(
                NullPointerException.class,
                () -> service.calculatePercentage(new BigDecimal("10"), null)
        );
    }
}
