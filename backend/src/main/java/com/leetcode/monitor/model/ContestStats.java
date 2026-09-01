/*
 * File: ContestStats.java
 * Author: REST API Agent
 * Phase: Phase 3 — Backend: ContestConfigController + Model Classes
 * Purpose: Immutable snapshot model representing the aggregate contest state and question metrics.
 */

package com.leetcode.monitor.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot representing the state of the contest monitor and question metrics at a specific point in time.
 */
public final class ContestStats {

    /**
     * URL of the active contest page on LeetCode.
     */
    private final String contestUrl;

    /**
     * Immutable list of question statistics (always 4 entries, ordered Q1 through Q4).
     */
    private final List<QuestionStats> questions;

    /**
     * Question numbers sorted in descending order of user acceptance percentage (empty list until first ingest).
     */
    private final List<String> ranking;

    /**
     * List of recent ranking overtake events recorded during monitoring.
     */
    private final List<RankingChange> recentChanges;

    /**
     * Timestamp when this snapshot was created or last updated.
     */
    private final Instant lastUpdated;

    /**
     * Lifecycle state of the contest monitor ("INITIALISED", "MONITORING", "ENDED").
     */
    private final String lifecycleState;

    /**
     * Map of pairwise question comparison outcomes for deduplicating overtake notifications (e.g., "Q1vsQ2" -> "GT"|"LT"|"EQ").
     */
    private final Map<String, String> pairwiseRelationships;

    /**
     * Constructs an immutable ContestStats snapshot with defensive copying of collection parameters.
     *
     * @param contestUrl            URL of the contest
     * @param questions             list of question statistics (ordered Q1 to Q4)
     * @param ranking               ordered list of question identifiers by acceptance rate
     * @param recentChanges         list of recent ranking change events
     * @param lastUpdated           timestamp of the snapshot
     * @param lifecycleState        lifecycle state of the contest ("INITIALISED", "MONITORING", "ENDED")
     * @param pairwiseRelationships map of pairwise ranking relationships
     */
    public ContestStats(
            String contestUrl,
            List<QuestionStats> questions,
            List<String> ranking,
            List<RankingChange> recentChanges,
            Instant lastUpdated,
            String lifecycleState,
            Map<String, String> pairwiseRelationships) {
        this.contestUrl = contestUrl;
        this.questions = questions != null ? Collections.unmodifiableList(new ArrayList<>(questions)) : Collections.emptyList();
        this.ranking = ranking != null ? Collections.unmodifiableList(new ArrayList<>(ranking)) : Collections.emptyList();
        this.recentChanges = recentChanges != null ? Collections.unmodifiableList(new ArrayList<>(recentChanges)) : Collections.emptyList();
        this.lastUpdated = lastUpdated != null ? lastUpdated : Instant.now();
        this.lifecycleState = lifecycleState;
        this.pairwiseRelationships = pairwiseRelationships != null ? Collections.unmodifiableMap(new LinkedHashMap<>(pairwiseRelationships)) : Collections.emptyMap();
    }

    /**
     * Creates a new Builder instance for constructing {@link ContestStats}.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the contest URL.
     *
     * @return the contest URL
     */
    public String getContestUrl() {
        return contestUrl;
    }

    /**
     * Gets the immutable list of question statistics.
     *
     * @return the list of question statistics (Q1 to Q4)
     */
    public List<QuestionStats> getQuestions() {
        return questions;
    }

    /**
     * Gets the current ranking order of question identifiers.
     *
     * @return the ranking list sorted descending by acceptance percentage
     */
    public List<String> getRanking() {
        return ranking;
    }

    /**
     * Gets the list of recent overtake events.
     *
     * @return the list of recent ranking changes
     */
    public List<RankingChange> getRecentChanges() {
        return recentChanges;
    }

    /**
     * Gets the snapshot timestamp.
     *
     * @return the last updated timestamp
     */
    public Instant getLastUpdated() {
        return lastUpdated;
    }

    /**
     * Gets the lifecycle state of the contest monitor.
     *
     * @return the lifecycle state ("INITIALISED", "MONITORING", "ENDED")
     */
    public String getLifecycleState() {
        return lifecycleState;
    }

    /**
     * Gets the map of pairwise relationships for overtake deduplication.
     *
     * @return the pairwise relationships map
     */
    public Map<String, String> getPairwiseRelationships() {
        return pairwiseRelationships;
    }

    /**
     * Builder for constructing immutable {@link ContestStats} instances.
     */
    public static class Builder {
        private String contestUrl;
        private List<QuestionStats> questions = new ArrayList<>();
        private List<String> ranking = new ArrayList<>();
        private List<RankingChange> recentChanges = new ArrayList<>();
        private Instant lastUpdated = Instant.now();
        private String lifecycleState;
        private Map<String, String> pairwiseRelationships = new LinkedHashMap<>();

        /**
         * Sets the contest URL.
         *
         * @param contestUrl the contest URL
         * @return this builder
         */
        public Builder contestUrl(String contestUrl) {
            this.contestUrl = contestUrl;
            return this;
        }

        /**
         * Sets the question statistics list.
         *
         * @param questions the list of question statistics
         * @return this builder
         */
        public Builder questions(List<QuestionStats> questions) {
            this.questions = questions != null ? new ArrayList<>(questions) : new ArrayList<>();
            return this;
        }

        /**
         * Sets the ranking list.
         *
         * @param ranking the ranking list
         * @return this builder
         */
        public Builder ranking(List<String> ranking) {
            this.ranking = ranking != null ? new ArrayList<>(ranking) : new ArrayList<>();
            return this;
        }

        /**
         * Sets the recent changes list.
         *
         * @param recentChanges the recent changes list
         * @return this builder
         */
        public Builder recentChanges(List<RankingChange> recentChanges) {
            this.recentChanges = recentChanges != null ? new ArrayList<>(recentChanges) : new ArrayList<>();
            return this;
        }

        /**
         * Sets the last updated timestamp.
         *
         * @param lastUpdated the timestamp
         * @return this builder
         */
        public Builder lastUpdated(Instant lastUpdated) {
            this.lastUpdated = lastUpdated;
            return this;
        }

        /**
         * Sets the lifecycle state.
         *
         * @param lifecycleState the lifecycle state ("INITIALISED", "MONITORING", "ENDED")
         * @return this builder
         */
        public Builder lifecycleState(String lifecycleState) {
            this.lifecycleState = lifecycleState;
            return this;
        }

        /**
         * Sets the pairwise relationships map.
         *
         * @param pairwiseRelationships the pairwise relationships map
         * @return this builder
         */
        public Builder pairwiseRelationships(Map<String, String> pairwiseRelationships) {
            this.pairwiseRelationships = pairwiseRelationships != null ? new LinkedHashMap<>(pairwiseRelationships) : new LinkedHashMap<>();
            return this;
        }

        /**
         * Builds the immutable ContestStats instance.
         *
         * @return the constructed ContestStats instance
         */
        public ContestStats build() {
            return new ContestStats(
                    contestUrl,
                    questions,
                    ranking,
                    recentChanges,
                    lastUpdated,
                    lifecycleState,
                    pairwiseRelationships
            );
        }
    }
}
