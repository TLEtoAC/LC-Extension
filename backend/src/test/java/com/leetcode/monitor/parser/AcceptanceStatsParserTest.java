/*
 * File: AcceptanceStatsParserTest.java
 * Author: Parser/Data Agent
 * Phase: Phase 5 — Parser Hardening
 * Purpose: Comprehensive unit tests and float-drift regression tests for AcceptanceStatsParser.
 */

package com.leetcode.monitor.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("AcceptanceStatsParser Unit Tests")
class AcceptanceStatsParserTest {

    private AcceptanceStatsParser parser;

    @BeforeEach
    void setUp() {
        parser = new AcceptanceStatsParser();
    }

    @Nested
    @DisplayName("Valid Acceptance String Parsing")
    class ValidParsingTests {

        @Test
        @DisplayName("Parses standard integer strings correctly")
        void testStandardIntegers() {
            ParsedAcceptance result = parser.parse("150 / 300");
            assertNotNull(result);
            assertEquals(new BigDecimal("150"), result.acceptedUsers());
            assertEquals(new BigDecimal("300"), result.totalUsers());
        }

        @Test
        @DisplayName("Parses comma thousands separators correctly")
        void testCommaSeparators() {
            ParsedAcceptance result = parser.parse("28,903 / 31,100");
            assertNotNull(result);
            assertEquals(new BigDecimal("28903"), result.acceptedUsers());
            assertEquals(new BigDecimal("31100"), result.totalUsers());
        }

        @Test
        @DisplayName("Parses mixed comma and 'K' suffix correctly")
        void testMixedCommaAndKSuffix() {
            ParsedAcceptance result = parser.parse("28,903 / 31.1K");
            assertNotNull(result);
            assertEquals(new BigDecimal("28903"), result.acceptedUsers());
            assertEquals(new BigDecimal("31100"), result.totalUsers());
        }

        @Test
        @DisplayName("Parses lowercase 'k' suffix correctly")
        void testLowercaseKSuffix() {
            ParsedAcceptance result = parser.parse("15.5k / 30.0k");
            assertNotNull(result);
            assertEquals(new BigDecimal("15500"), result.acceptedUsers());
            assertEquals(new BigDecimal("30000"), result.totalUsers());
        }

        @Test
        @DisplayName("Parses 'M' and 'm' million suffixes correctly")
        void testMillionSuffix() {
            ParsedAcceptance resultUpper = parser.parse("1.2M / 4.5M");
            assertNotNull(resultUpper);
            assertEquals(new BigDecimal("1200000"), resultUpper.acceptedUsers());
            assertEquals(new BigDecimal("4500000"), resultUpper.totalUsers());

            ParsedAcceptance resultLower = parser.parse("2.05m / 3.5m");
            assertNotNull(resultLower);
            assertEquals(new BigDecimal("2050000"), resultLower.acceptedUsers());
            assertEquals(new BigDecimal("3500000"), resultLower.totalUsers());
        }

        @Test
        @DisplayName("Parses 'B' and 'b' billion suffixes correctly")
        void testBillionSuffix() {
            ParsedAcceptance result = parser.parse("4.5B / 10B");
            assertNotNull(result);
            assertEquals(new BigDecimal("4500000000"), result.acceptedUsers());
            assertEquals(new BigDecimal("10000000000"), result.totalUsers());

            ParsedAcceptance resultLower = parser.parse("1.25b / 2b");
            assertNotNull(resultLower);
            assertEquals(new BigDecimal("1250000000"), resultLower.acceptedUsers());
            assertEquals(new BigDecimal("2000000000"), resultLower.totalUsers());
        }

        @Test
        @DisplayName("Handles arbitrary whitespace around tokens and delimiter")
        void testWhitespaceTolerance() {
            ParsedAcceptance result = parser.parse("  \t 28,903  \t /   31.1K \n ");
            assertNotNull(result);
            assertEquals(new BigDecimal("28903"), result.acceptedUsers());
            assertEquals(new BigDecimal("31100"), result.totalUsers());
        }

        @Test
        @DisplayName("Parses zero values correctly")
        void testZeroValues() {
            ParsedAcceptance result = parser.parse("0 / 0");
            assertNotNull(result);
            assertEquals(BigDecimal.ZERO, result.acceptedUsers());
            assertEquals(BigDecimal.ZERO, result.totalUsers());

            ParsedAcceptance resultSuffixZero = parser.parse("0.0K / 0.00M");
            assertNotNull(resultSuffixZero);
            assertEquals(BigDecimal.ZERO, resultSuffixZero.acceptedUsers());
            assertEquals(BigDecimal.ZERO, resultSuffixZero.totalUsers());
        }

        @Test
        @DisplayName("Static method parseAcceptance matches instance method parse")
        void testStaticMethodEquivalence() {
            ParsedAcceptance instanceResult = parser.parse("28,903 / 31.1K");
            ParsedAcceptance staticResult = AcceptanceStatsParser.parseAcceptance("28,903 / 31.1K");
            assertEquals(instanceResult, staticResult);
        }
    }

    @Nested
    @DisplayName("Float Drift Regression Tests")
    class FloatDriftRegressionTests {

        @Test
        @DisplayName("Regression: '29.9K' does not truncate to 29899 (double drift)")
        void test29Point9K() {
            // In double arithmetic: 29.9 * 1000.0 = 29899.999999999996 -> (long) 29899
            ParsedAcceptance result = parser.parse("29.9K / 30K");
            assertEquals(new BigDecimal("29900"), result.acceptedUsers());
            assertEquals(new BigDecimal("30000"), result.totalUsers());
        }

        @Test
        @DisplayName("Regression: '1.15K' does not truncate to 1149 (double drift)")
        void test1Point15K() {
            // In double arithmetic: 1.15 * 1000.0 = 1149.9999999999998 -> (long) 1149
            ParsedAcceptance result = parser.parse("1.15K / 2K");
            assertEquals(new BigDecimal("1150"), result.acceptedUsers());
            assertEquals(new BigDecimal("2000"), result.totalUsers());
        }

        @Test
        @DisplayName("Regression: '59.9K' does not drift")
        void test59Point9K() {
            ParsedAcceptance result = parser.parse("59.9K / 100K");
            assertEquals(new BigDecimal("59900"), result.acceptedUsers());
            assertEquals(new BigDecimal("100000"), result.totalUsers());
        }

        @Test
        @DisplayName("Regression: '0.07M' parses exactly to 70000")
        void test0Point07M() {
            ParsedAcceptance result = parser.parse("0.07M / 1M");
            assertEquals(new BigDecimal("70000"), result.acceptedUsers());
            assertEquals(new BigDecimal("1000000"), result.totalUsers());
        }

        @Test
        @DisplayName("Regression: '0.29M' parses exactly to 290000")
        void test0Point29M() {
            ParsedAcceptance result = parser.parse("0.29M / 0.5M");
            assertEquals(new BigDecimal("290000"), result.acceptedUsers());
            assertEquals(new BigDecimal("500000"), result.totalUsers());
        }
    }

    @Nested
    @DisplayName("Malformed Input and Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Throws ParseException on null input")
        void testNullInput() {
            ParseException ex = assertThrows(ParseException.class, () -> parser.parse(null));
            assertEquals("Raw acceptance string cannot be null", ex.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t\t", "\n"})
        @DisplayName("Throws ParseException on empty or blank input")
        void testBlankInput(String input) {
            ParseException ex = assertThrows(ParseException.class, () -> parser.parse(input));
            assertEquals("Raw acceptance string cannot be empty or blank", ex.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {"28903", "31.1K", "no-delimiter", "100 - 200"})
        @DisplayName("Throws ParseException when delimiter is missing")
        void testMissingDelimiter(String input) {
            ParseException ex = assertThrows(ParseException.class, () -> parser.parse(input));
            org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("missing '/' delimiter"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"28 / 30 / 40", "1 / 2 / 3 / 4", "/ /"})
        @DisplayName("Throws ParseException when multiple delimiters are present")
        void testMultipleDelimiters(String input) {
            ParseException ex = assertThrows(ParseException.class, () -> parser.parse(input));
            org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("expected exactly one '/' delimiter"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"/ 31.1K", "28,903 /", " / ", "\t/\t"})
        @DisplayName("Throws ParseException when operand is missing before or after delimiter")
        void testMissingOperand(String input) {
            ParseException ex = assertThrows(ParseException.class, () -> parser.parse(input));
            org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("accepted or total count is missing"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc / 31.1K", "28,903 / xyz", "foo / bar", "12.34.56 / 100"})
        @DisplayName("Throws ParseException when tokens contain invalid numeric formats")
        void testInvalidNumericCharacters(String input) {
            ParseException ex = assertThrows(ParseException.class, () -> parser.parse(input));
            org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("Invalid numeric value"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"K / 31.1K", "28,903 / M", "B / K"})
        @DisplayName("Throws ParseException when suffix exists without numeric digits")
        void testMissingNumericBeforeSuffix(String input) {
            ParseException ex = assertThrows(ParseException.class, () -> parser.parse(input));
            org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("missing numeric value before suffix"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"-100 / 200", "100 / -200", "-1.5K / 3K"})
        @DisplayName("Throws ParseException when negative numbers are provided")
        void testNegativeNumbers(String input) {
            ParseException ex = assertThrows(ParseException.class, () -> parser.parse(input));
            org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("Negative counts are not permitted"));
        }
    }
}
