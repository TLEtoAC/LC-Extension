# Changelog

## [Unreleased]

## Phase 7 — Alarm Scheduler Polish (2026-09-05)
**Lead:** Extension/Background Agent | **Reviewer:** Architect
**Side:** extension/
- [x] options.html / options.js — scrape interval input (default 5, clamp 1–60), persisted as `monitoringIntervalMinutes`
- [x] alarmScheduler.js — `registerMonitoringAlarm` reads stored interval; `updateMonitoringInterval` clears + recreates `scrapeCycle`; `stopMonitoringAlarm` + `handleContestEnded` Phase 11 hook
- [x] background.js — `MONITORING_INTERVAL_SAVED` / `CONTEST_ENDED`; SW start restores missing alarm, clears orphaned `cycleInProgress`
- [x] models.md — Agent Council role → plan model vs actual model (Cursor Grok 4.6)

## Phase 6 — Four Questions + Concurrency (2026-09-05)
**Lead:** Extension/Background Agent + Backend/Core Agent | **Reviewer:** Edge-Case Red Team
**Side:** extension/ + backend/
- [x] AcceptanceCalculationService.java — BigDecimal percentage, scale 10 HALF_UP; zero total throws
- [x] ContestStateService.applyIngest — synchronized critical section, deep-copy QuestionStats, parse + calculate, PARSE_ERROR on failure
- [x] ContestStateServiceConcurrencyTest — 4 simultaneous ingests, last-write-wins, isolated parse failure, concurrent readers
- [x] alarmScheduler.js — registerMonitoringAlarm, runScrapeCycle Q1→Q4, pending-scrape map, NAVIGATION_TIMEOUT on 20s wait
- [x] background.js — handleScrapeResult → postIngest; immediate cycle after discovery; scrapeCycle alarm
- [x] backendClient.postIngest — normalize 1 / "1" / "Q1" → URL slot Qn

## Phase 5 — Parser Hardening (2026-09-03)
**Lead:** Parser/Data Agent + Testing/QA Agent | **Reviewer:** Architect
**Side:** backend/
- [x] AcceptanceStatsParser.java — K/M/B suffixes, comma stripping, zero `double` math
- [x] ParsedAcceptance.java / ParseException.java
- [x] AcceptanceStatsParserTest.java — 40 tests including float-drift regressions

## Phase 1 — Backend Skeleton (2026-08-16)
**Lead:** REST API Agent | **Reviewer:** Architect
**Side:** backend/
- [x] pom.xml
- [x] LcMonitorApplication.java
- [x] CorsConfig.java
- [x] ContestHealthController.java
- [x] application.properties
- [x] ContestHealthControllerTest.java

## Phase 2 — Extension Scaffold + Tab Lifecycle (2026-08-16)
**Lead:** Extension/Background Agent | **Reviewer:** Architect
**Side:** extension/
- [x] manifest.json
- [x] background/background.js
- [x] background/tabLifecycleManager.js
- [x] background/backendClient.js
- [x] ui/options.html / options.js
- [x] package.json
- [x] tests/httpBoundaryProof.md

## Phase 3 — Contest Discovery Content Script (2026-08-17)
**Lead:** Content Script/DOM Agent | **Reviewer:** Architect
**Side:** extension/
- [x] extension/content-scripts/contestPageScript.js

## Phase 4 — Problem Page DOM Scraping Content Script (2026-09-03)
**Lead:** Content Script/DOM Agent | **Reviewer:** Architect
**Side:** extension/
- [x] extension/content-scripts/problemPageScript.js

