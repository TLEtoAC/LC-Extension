/*
 * File: LcMonitorApplication.java
 * Author: REST API Agent
 * Phase: Phase 1 — Backend Skeleton
 * Purpose: Main entry point for the LeetCode Contest Monitor Spring Boot backend application.
 */

package com.leetcode.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class bootstrapping the LeetCode Contest Monitor backend.
 */
@SpringBootApplication
public class LcMonitorApplication {

    /**
     * Entry point of the Spring Boot application.
     *
     * @param args command line arguments passed to the application
     */
    public static void main(String[] args) {
        SpringApplication.run(LcMonitorApplication.class, args);
    }
}
