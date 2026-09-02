/*
 * File: AcceptanceStatsParser.java
 * Author: Parser/Data Agent
 * Phase: Phase 5 — Parser Hardening
 * Purpose: Robust parser for raw LeetCode acceptance statistics strings using pure BigDecimal arithmetic.
 */

package com.leetcode.monitor.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Parser for converting raw LeetCode submission and acceptance display strings into structured {@link ParsedAcceptance} records.
 *
 * <p><strong>Approximation Caveat:</strong>
 * LeetCode problem pages frequently display acceptance and submission counts as rounded metric strings
 * (e.g., {@code "31.1K"}, {@code "1.2M"}). The parsed {@link BigDecimal} values produced by this class represent
 * mathematical expansions of those rounded figures (e.g., {@code "31.1K"} becomes {@code 31100}). Consequently,
 * these values are approximations of the true underlying contest integers (representing any integer in [31050, 31149]),
 * unless the raw input contains exact non-abbreviated integers (e.g., {@code "28,903"}).</p>
 *
 * <p><strong>BigDecimal Rationale:</strong>
 * Binary floating-point arithmetic ({@code double} or {@code float}) introduces IEEE-754 representation inaccuracies
 * and rounding drift. For example, {@code (long)(29.9 * 1000.0)} evaluates to {@code 29899} due to binary floating-point
 * imprecision ({@code 29.899999999999996}). To guarantee absolute deterministic computation and avoid off-by-one errors
 * or arithmetic drift, all suffix expansions and numeric conversions in this parser strictly utilize {@link BigDecimal}
 * with exact string-based constructors and multiplier operations.</p>
 *
 * <p><strong>Supported Formats:</strong></p>
 * <ul>
 *   <li>Standard integer counts: {@code "150 / 300"}</li>
 *   <li>Thousand-separated values: {@code "28,903 / 31,100"}</li>
 *   <li>Thousand multiplier suffix (K/k &times; 1,000): {@code "28,903 / 31.1K"}, {@code "1.5k / 2.0k"}</li>
 *   <li>Million multiplier suffix (M/m &times; 1,000,000): {@code "1.2M / 4.5M"}, {@code "2.0m / 5.5m"}</li>
 *   <li>Billion multiplier suffix (B/b &times; 1,000,000,000): {@code "4.5B / 10B"}, {@code "1.0b / 2.5b"}</li>
 *   <li>Arbitrary whitespace tolerance around delimiters and tokens: {@code "  28,903   /   31.1K  "}</li>
 * </ul>
 */
@Component
public class AcceptanceStatsParser {

    private static final Logger logger = LoggerFactory.getLogger(AcceptanceStatsParser.class);

    /**
     * Multiplier for thousand suffix (K/k): 1,000.
     */
    private static final BigDecimal MULTIPLIER_K = new BigDecimal("1000");

    /**
     * Multiplier for million suffix (M/m): 1,000,000.
     */
    private static final BigDecimal MULTIPLIER_M = new BigDecimal("1000000");

    /**
     * Multiplier for billion suffix (B/b): 1,000,000,000.
     */
    private static final BigDecimal MULTIPLIER_B = new BigDecimal("1000000000");

    /**
     * Delimiter separating accepted user count from total submitting user count.
     */
    private static final char DELIMITER = '/';

    /**
     * Parses a raw LeetCode acceptance string formatted as {@code "<accepted> / <total>"} into a {@link ParsedAcceptance} record.
     *
     * @param rawUsersAccepted the raw scraped string (e.g., {@code "28,903 / 31.1K"})
     * @return a {@link ParsedAcceptance} containing the exact {@link BigDecimal} counts
     * @throws ParseException if the input is null, empty, missing the delimiter, contains multiple delimiters,
     *                        or contains unparseable numeric tokens
     */
    public ParsedAcceptance parse(String rawUsersAccepted) {
        return parseAcceptance(rawUsersAccepted);
    }

    /**
     * Static utility method for parsing raw LeetCode acceptance strings formatted as {@code "<accepted> / <total>"}.
     *
     * @param rawUsersAccepted the raw scraped string (e.g., {@code "28,903 / 31.1K"})
     * @return a {@link ParsedAcceptance} containing the exact {@link BigDecimal} counts
     * @throws ParseException if the input is null, empty, missing the delimiter, contains multiple delimiters,
     *                        or contains unparseable numeric tokens
     */
    public static ParsedAcceptance parseAcceptance(String rawUsersAccepted) {
        if (rawUsersAccepted == null) {
            logger.error("Acceptance string parsing failed: input string is null");
            throw new ParseException("Raw acceptance string cannot be null");
        }

        String trimmedInput = rawUsersAccepted.trim();
        if (trimmedInput.isEmpty()) {
            logger.error("Acceptance string parsing failed: input string is empty or whitespace-only");
            throw new ParseException("Raw acceptance string cannot be empty or blank");
        }

        int firstSlashIndex = trimmedInput.indexOf(DELIMITER);
        if (firstSlashIndex < 0) {
            logger.error("Acceptance string parsing failed: missing '{}' delimiter in '{}'", DELIMITER, rawUsersAccepted);
            throw new ParseException("Invalid acceptance string format: missing '" + DELIMITER + "' delimiter in '" + rawUsersAccepted + "'");
        }

        int lastSlashIndex = trimmedInput.lastIndexOf(DELIMITER);
        if (firstSlashIndex != lastSlashIndex) {
            logger.error("Acceptance string parsing failed: multiple '{}' delimiters in '{}'", DELIMITER, rawUsersAccepted);
            throw new ParseException("Invalid acceptance string format: expected exactly one '" + DELIMITER + "' delimiter, found multiple in '" + rawUsersAccepted + "'");
        }

        String acceptedPart = trimmedInput.substring(0, firstSlashIndex).trim();
        String totalPart = trimmedInput.substring(firstSlashIndex + 1).trim();

        if (acceptedPart.isEmpty() || totalPart.isEmpty()) {
            logger.error("Acceptance string parsing failed: missing operand before or after delimiter in '{}'", rawUsersAccepted);
            throw new ParseException("Invalid acceptance string format: accepted or total count is missing in '" + rawUsersAccepted + "'");
        }

        BigDecimal acceptedUsers = parseSingleValue(acceptedPart, "accepted count in '" + rawUsersAccepted + "'");
        BigDecimal totalUsers = parseSingleValue(totalPart, "total count in '" + rawUsersAccepted + "'");

        logger.debug("Successfully parsed acceptance string '{}' -> accepted={}, total={}", rawUsersAccepted, acceptedUsers, totalUsers);
        return new ParsedAcceptance(acceptedUsers, totalUsers);
    }

    /**
     * Parses an individual numeric token (which may contain commas and a K/M/B suffix) into a {@link BigDecimal}.
     *
     * @param token the individual token string (e.g., {@code "28,903"}, {@code "31.1K"}, {@code "1.2M"})
     * @return the parsed {@link BigDecimal} value
     * @throws ParseException if the token is null, empty, contains invalid characters, or represents a negative quantity
     */
    public BigDecimal parseValue(String token) {
        return parseSingleValue(token, "token '" + token + "'");
    }

    /**
     * Static utility method to parse an individual numeric token into a normalized {@link BigDecimal}.
     *
     * @param token   the individual token string (e.g., {@code "28,903"}, {@code "31.1K"})
     * @param context human-readable context string for error reporting
     * @return the parsed and normalized {@link BigDecimal} value
     * @throws ParseException if the token is null, empty, contains invalid characters, or represents a negative quantity
     */
    public static BigDecimal parseSingleValue(String token, String context) {
        if (token == null) {
            throw new ParseException("Numeric token cannot be null for " + context);
        }

        String cleaned = token.trim().replace(",", "");
        if (cleaned.isEmpty()) {
            throw new ParseException("Numeric token cannot be empty for " + context);
        }

        char lastChar = cleaned.charAt(cleaned.length() - 1);
        BigDecimal multiplier = null;
        String numericPart = cleaned;

        if (lastChar == 'K' || lastChar == 'k') {
            multiplier = MULTIPLIER_K;
            numericPart = cleaned.substring(0, cleaned.length() - 1).trim();
        } else if (lastChar == 'M' || lastChar == 'm') {
            multiplier = MULTIPLIER_M;
            numericPart = cleaned.substring(0, cleaned.length() - 1).trim();
        } else if (lastChar == 'B' || lastChar == 'b') {
            multiplier = MULTIPLIER_B;
            numericPart = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        if (numericPart.isEmpty()) {
            throw new ParseException("Invalid number format: missing numeric value before suffix in '" + token + "' for " + context);
        }

        BigDecimal baseValue;
        try {
            baseValue = new BigDecimal(numericPart);
        } catch (NumberFormatException ex) {
            logger.error("Failed to parse numeric string '{}' from token '{}' for {}: {}", numericPart, token, context, ex.getMessage());
            throw new ParseException("Invalid numeric value '" + numericPart + "' in '" + token + "' for " + context, ex);
        }

        if (baseValue.compareTo(BigDecimal.ZERO) < 0) {
            logger.error("Negative value detected in token '{}' for {}", token, context);
            throw new ParseException("Negative counts are not permitted: '" + token + "' for " + context);
        }

        BigDecimal result = multiplier != null ? baseValue.multiply(multiplier) : baseValue;

        // Normalize decimal scale to strip trailing zeroes, but retain scale 0 for whole numbers
        if (result.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        result = result.stripTrailingZeros();
        if (result.scale() < 0) {
            result = result.setScale(0);
        }

        return result;
    }
}
