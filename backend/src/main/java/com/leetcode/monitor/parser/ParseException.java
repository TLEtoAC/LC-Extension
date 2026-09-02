/*
 * File: ParseException.java
 * Author: Parser/Data Agent
 * Phase: Phase 5 — Parser Hardening
 * Purpose: Custom unchecked exception thrown when parsing invalid or malformed acceptance statistic strings.
 */

package com.leetcode.monitor.parser;

/**
 * Custom exception thrown when raw acceptance strings cannot be parsed due to invalid format,
 * missing components, unsupported suffixes, or malformed numeric expressions.
 *
 * <p>This exception extends {@link IllegalArgumentException} to provide clear error descriptions
 * while seamlessly integrating with standard Spring exception handlers and controller validation.</p>
 */
public class ParseException extends IllegalArgumentException {

    /**
     * Constructs a new {@code ParseException} with the specified detail message.
     *
     * @param message human-readable description of the parse failure
     */
    public ParseException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code ParseException} with the specified detail message and cause.
     *
     * @param message human-readable description of the parse failure
     * @param cause   the underlying cause of the failure
     */
    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
