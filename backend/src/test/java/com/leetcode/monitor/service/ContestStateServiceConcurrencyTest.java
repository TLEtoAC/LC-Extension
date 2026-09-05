/*
 * File: ContestStateServiceConcurrencyTest.java
 * Author: Testing/QA Agent
 * Phase: Phase 6 — Four Questions + Concurrency
 * Purpose: Concurrent ingest tests — no lost updates, last-write-wins, isolated parse failures,
 *          and snapshot consistency under concurrent readers (no torn reads).
 */

package com.leetcode.monitor.service;

import com.leetcode.monitor.dto.ContestIngestRequest;
import com.leetcode.monitor.model.ContestStats;
import com.leetcode.monitor.model.Question;
import com.leetcode.monitor.model.QuestionStats;
import com.leetcode.monitor.model.ScrapingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@DisplayName("ContestStateService Concurrency Tests")
class ContestStateServiceConcurrencyTest {

    @Autowired
    private ContestStateService contestStateService;

    @BeforeEach
    void initializeContest() {
        contestStateService.initializeContest(
                "https://leetcode.com/contest/weekly-400",
                List.of(
                        new Question("Q1", "min-chairs", "https://leetcode.com/problems/min-chairs"),
                        new Question("Q2", "count-days", "https://leetcode.com/problems/count-days"),
                        new Question("Q3", "smallest-string", "https://leetcode.com/problems/smallest-string"),
                        new Question("Q4", "bitwise-and", "https://leetcode.com/problems/bitwise-and")
                )
        );
    }

    @Test
    @DisplayName("Four simultaneous ingests update all slots with no lost updates")
    void fourSimultaneousIngests_allQuestionsUpdated_noLostUpdates() throws Exception {
        runConcurrentIngests(List.of(
                new Ingest("Q1", "100 / 200"),
                new Ingest("Q2", "300 / 400"),
                new Ingest("Q3", "50 / 100"),
                new Ingest("Q4", "28,903 / 31.1K")
        ));

        ContestStats snapshot = contestStateService.getCurrentStats();
        assertNotNull(snapshot);
        assertEquals("MONITORING", snapshot.getLifecycleState());
        assertNotNull(snapshot.getLastUpdated());
        assertEquals(4, snapshot.getQuestions().size());

        Map<String, QuestionStats> bySlot = indexBySlot(snapshot);

        assertSlot(bySlot.get("Q1"), "min-chairs", ScrapingStatus.SUCCESS, "100", "200", "50");
        assertSlot(bySlot.get("Q2"), "count-days", ScrapingStatus.SUCCESS, "300", "400", "75");
        assertSlot(bySlot.get("Q3"), "smallest-string", ScrapingStatus.SUCCESS, "50", "100", "50");
        assertSlot(bySlot.get("Q4"), "bitwise-and", ScrapingStatus.SUCCESS, "28903", "31100", "92.9356913183");
    }

    @Test
    @DisplayName("Two concurrent ingests on the same question — last write wins with a complete slot")
    void sameQuestionIngestTwiceConcurrently_lastWriteWins() throws Exception {
        runConcurrentIngests(List.of(
                new Ingest("Q1", "10 / 20"),
                new Ingest("Q1", "30 / 40")
        ));

        QuestionStats q1 = indexBySlot(contestStateService.getCurrentStats()).get("Q1");
        assertNotNull(q1);
        assertEquals(ScrapingStatus.SUCCESS, q1.getScrapingStatus());
        assertNotNull(q1.getAcceptedUsers());
        assertNotNull(q1.getTotalUsers());
        assertNotNull(q1.getUsersAcceptedPercentage());

        boolean firstWrite = decimalEquals(q1.getAcceptedUsers(), "10")
                && decimalEquals(q1.getTotalUsers(), "20")
                && decimalEquals(q1.getUsersAcceptedPercentage(), "50");
        boolean secondWrite = decimalEquals(q1.getAcceptedUsers(), "30")
                && decimalEquals(q1.getTotalUsers(), "40")
                && decimalEquals(q1.getUsersAcceptedPercentage(), "75");
        assertTrue(firstWrite || secondWrite, "Final Q1 slot must be a complete last-write snapshot");
    }

    @Test
    @DisplayName("One parse failure does not wipe valid concurrent ingests")
    void concurrentIngest_oneParseFailure_othersPersist() throws Exception {
        runConcurrentIngests(List.of(
                new Ingest("Q1", "not-a-valid-acceptance-string"),
                new Ingest("Q2", "300 / 400"),
                new Ingest("Q3", "50 / 100"),
                new Ingest("Q4", "28,903 / 31.1K")
        ));

        Map<String, QuestionStats> bySlot = indexBySlot(contestStateService.getCurrentStats());

        QuestionStats q1 = bySlot.get("Q1");
        assertEquals(ScrapingStatus.PARSE_ERROR, q1.getScrapingStatus());
        assertNull(q1.getAcceptedUsers());
        assertNull(q1.getTotalUsers());
        assertNull(q1.getUsersAcceptedPercentage());
        assertNotNull(q1.getErrorMessage());

        assertSlot(bySlot.get("Q2"), "count-days", ScrapingStatus.SUCCESS, "300", "400", "75");
        assertSlot(bySlot.get("Q3"), "smallest-string", ScrapingStatus.SUCCESS, "50", "100", "50");
        assertSlot(bySlot.get("Q4"), "bitwise-and", ScrapingStatus.SUCCESS, "28903", "31100", "92.9356913183");
    }

    @Test
    @DisplayName("Concurrent readers never observe torn metric triples")
    void concurrentIngest_withConcurrentReads_snapshotAlwaysConsistent() throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(4);
        CountDownLatch readersDone = new CountDownLatch(4);
        AtomicInteger writeFailures = new AtomicInteger();
        AtomicBoolean torn = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(8);

        List<Ingest> writers = List.of(
                new Ingest("Q1", "100 / 200"),
                new Ingest("Q2", "300 / 400"),
                new Ingest("Q3", "50 / 100"),
                new Ingest("Q4", "28,903 / 31.1K")
        );

        try {
            for (Ingest ingest : writers) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        contestStateService.applyIngest(
                                ingest.questionNumber(),
                                new ContestIngestRequest(ingest.raw(), ScrapingStatus.SUCCESS)
                        );
                    } catch (Exception ex) {
                        writeFailures.incrementAndGet();
                    } finally {
                        writersDone.countDown();
                    }
                });
            }

            for (int i = 0; i < 4; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        while (writersDone.getCount() > 0) {
                            assertSnapshotConsistent(contestStateService.getCurrentStats(), torn);
                        }
                        assertSnapshotConsistent(contestStateService.getCurrentStats(), torn);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        readersDone.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(writersDone.await(10, TimeUnit.SECONDS));
            assertTrue(readersDone.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, writeFailures.get());
        assertTrue(!torn.get(), "Readers observed a torn QuestionStats (percentage set without counts, or vice versa)");

        ContestStats finalSnapshot = contestStateService.getCurrentStats();
        assertEquals("MONITORING", finalSnapshot.getLifecycleState());
        Map<String, QuestionStats> bySlot = indexBySlot(finalSnapshot);
        assertSlot(bySlot.get("Q1"), "min-chairs", ScrapingStatus.SUCCESS, "100", "200", "50");
        assertSlot(bySlot.get("Q2"), "count-days", ScrapingStatus.SUCCESS, "300", "400", "75");
        assertSlot(bySlot.get("Q3"), "smallest-string", ScrapingStatus.SUCCESS, "50", "100", "50");
        assertSlot(bySlot.get("Q4"), "bitwise-and", ScrapingStatus.SUCCESS, "28903", "31100", "92.9356913183");
    }

    private record Ingest(String questionNumber, String raw) {
    }

    private void runConcurrentIngests(List<Ingest> ingests) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(ingests.size());
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(ingests.size());

        try {
            for (Ingest ingest : ingests) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        contestStateService.applyIngest(
                                ingest.questionNumber(),
                                new ContestIngestRequest(ingest.raw(), ScrapingStatus.SUCCESS)
                        );
                    } catch (Exception ex) {
                        failures.incrementAndGet();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, failures.get(), "All ingest threads must complete without throwing");
    }

    private static Map<String, QuestionStats> indexBySlot(ContestStats snapshot) {
        return snapshot.getQuestions().stream()
                .collect(Collectors.toMap(QuestionStats::getQuestionNumber, Function.identity()));
    }

    private static void assertSlot(
            QuestionStats stats,
            String problemName,
            ScrapingStatus status,
            String accepted,
            String total,
            String percentage) {
        assertNotNull(stats);
        assertEquals(problemName, stats.getProblemName());
        assertEquals(status, stats.getScrapingStatus());
        assertTrue(decimalEquals(stats.getAcceptedUsers(), accepted), "acceptedUsers");
        assertTrue(decimalEquals(stats.getTotalUsers(), total), "totalUsers");
        assertTrue(decimalEquals(stats.getUsersAcceptedPercentage(), percentage), "usersAcceptedPercentage");
    }

    private static boolean decimalEquals(BigDecimal actual, String expected) {
        return actual != null && actual.compareTo(new BigDecimal(expected)) == 0;
    }

    private static void assertSnapshotConsistent(ContestStats snapshot, AtomicBoolean torn) {
        if (snapshot == null) {
            return;
        }
        for (QuestionStats qs : snapshot.getQuestions()) {
            boolean hasAccepted = qs.getAcceptedUsers() != null;
            boolean hasTotal = qs.getTotalUsers() != null;
            boolean hasPercentage = qs.getUsersAcceptedPercentage() != null;
            if (hasAccepted != hasTotal || hasAccepted != hasPercentage) {
                torn.set(true);
            }
        }
    }
}
