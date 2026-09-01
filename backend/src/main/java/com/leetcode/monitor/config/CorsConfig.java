/*
 * File: CorsConfig.java
 * Author: REST API Agent
 * Phase: Phase 1 — Backend Skeleton
 * Purpose: Cross-Origin Resource Sharing (CORS) configuration for LeetCode Contest Monitor REST endpoints.
 *
 * Security Rationale:
 * 1. Strict Origin Restriction:
 *    Allowed origins are strictly restricted to the specific Chrome Extension ID
 *    ('chrome-extension://<EXTENSION_ID>'). Wildcard ('*') origins are explicitly forbidden
 *    to prevent arbitrary websites in the browser from issuing unauthorized requests to the local backend.
 * 2. Deterministic Extension ID (manifest.json 'key' field):
 *    Chrome assigns an extension ID based on the developer public key. By specifying a static 'key' field
 *    in manifest.json, the Chrome Extension maintains a fixed ID across all development environments and reloads,
 *    allowing this backend to reliably whitelist only that exact extension origin.
 */

package com.leetcode.monitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring configuration class for CORS rules.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * The origin of the Chrome Extension permitted to communicate with this backend.
     * Replace PLACEHOLDER_EXTENSION_ID with the fixed extension ID generated from the manifest 'key'.
     */
    public static final String EXTENSION_ORIGIN = "chrome-extension://PLACEHOLDER_EXTENSION_ID";

    /**
     * Configures CORS mappings for the application REST API endpoints (/api/**).
     * Restricts allowed origins to EXTENSION_ORIGIN, allowing GET, POST, and OPTIONS methods.
     *
     * @return a WebMvcConfigurer bean containing CORS mapping rules
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(EXTENSION_ORIGIN)
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
