package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** M14-only top-K guidance; complete-plan denial remains the authoritative terminal objective. */
record RelativeMarginCandidateMetrics(
        boolean newOwnSemiBrand,
        int ownSemiCollectionGain,
        int strongDenialGain,
        int followOnDenialGain,
        int semiScoreGain,
        int rawGain,
        int routeSteps,
        int routeFuel,
        int resultingFuel,
        Position target,
        AgentId patrol) {

    private static final Comparator<RelativeMarginCandidateMetrics> COVERAGE = Comparator
            .comparing(RelativeMarginCandidateMetrics::newOwnSemiBrand).reversed()
            .thenComparing(Comparator.comparingInt(
                    RelativeMarginCandidateMetrics::ownSemiCollectionGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    RelativeMarginCandidateMetrics::strongDenialGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    RelativeMarginCandidateMetrics::resultingFuel).reversed())
            .thenComparing(Comparator.comparingInt(
                    RelativeMarginCandidateMetrics::followOnDenialGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    RelativeMarginCandidateMetrics::semiScoreGain).reversed())
            .thenComparing(Comparator.comparingInt(RelativeMarginCandidateMetrics::rawGain).reversed())
            .thenComparingInt(RelativeMarginCandidateMetrics::routeSteps)
            .thenComparingInt(RelativeMarginCandidateMetrics::routeFuel)
            .thenComparingInt(value -> value.target.value())
            .thenComparingInt(value -> value.patrol.value());

    private static final Comparator<RelativeMarginCandidateMetrics> HARVEST = Comparator
            .comparingInt(RelativeMarginCandidateMetrics::projectedStrongSwingGain).reversed()
            .thenComparing(Comparator.comparingInt(
                    RelativeMarginCandidateMetrics::ownSemiCollectionGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    RelativeMarginCandidateMetrics::strongDenialGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    RelativeMarginCandidateMetrics::resultingFuel).reversed())
            .thenComparing(Comparator.comparingInt(
                    RelativeMarginCandidateMetrics::followOnDenialGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    RelativeMarginCandidateMetrics::semiScoreGain).reversed())
            .thenComparing(Comparator.comparingInt(RelativeMarginCandidateMetrics::rawGain).reversed())
            .thenComparingInt(RelativeMarginCandidateMetrics::routeSteps)
            .thenComparingInt(RelativeMarginCandidateMetrics::routeFuel)
            .thenComparingInt(value -> value.target.value())
            .thenComparingInt(value -> value.patrol.value());

    RelativeMarginCandidateMetrics {
        Objects.requireNonNull(target, "Candidate target must not be null");
        Objects.requireNonNull(patrol, "Candidate PATROL must not be null");
        if (ownSemiCollectionGain < 0 || strongDenialGain < 0 || followOnDenialGain < 0
                || semiScoreGain < 0 || rawGain < 0 || routeSteps < 0 || routeFuel < 0
                || resultingFuel < 0) {
            throw new IllegalArgumentException("Relative-margin candidate metrics must be non-negative");
        }
    }

    int projectedStrongSwingGain() {
        return ownSemiCollectionGain + strongDenialGain;
    }

    static Comparator<RelativeMarginCandidateMetrics> coveragePreference() {
        return COVERAGE;
    }

    static Comparator<RelativeMarginCandidateMetrics> harvestPreference() {
        return HARVEST;
    }
}