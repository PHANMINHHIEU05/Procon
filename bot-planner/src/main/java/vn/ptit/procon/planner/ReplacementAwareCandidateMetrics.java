package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * M15-only top-K guidance; the complete-plan replacement-aware margin stays authoritative.
 *
 * <p>{@code fullDayContestGain} is read from the immutable full-day opponent baseline rather than from
 * a three-target prefix, so a spot deep inside an opponent's whole-day route still scores and can
 * survive candidate pruning. Old modes keep their own candidate ordering untouched.</p>
 */
record ReplacementAwareCandidateMetrics(
        boolean newOwnSemiBrand,
        int ownSemiCollectionGain,
        int fullDayContestGain,
        int strongContestGain,
        int semiScoreGain,
        int rawGain,
        int routeSteps,
        int routeFuel,
        int resultingFuel,
        Position target,
        AgentId patrol) {

    private static final Comparator<ReplacementAwareCandidateMetrics> COVERAGE = Comparator
            .comparing(ReplacementAwareCandidateMetrics::newOwnSemiBrand).reversed()
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::ownSemiCollectionGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::fullDayContestGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::resultingFuel).reversed())
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::strongContestGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::semiScoreGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::rawGain).reversed())
            .thenComparingInt(ReplacementAwareCandidateMetrics::routeSteps)
            .thenComparingInt(ReplacementAwareCandidateMetrics::routeFuel)
            .thenComparingInt(value -> value.target.value())
            .thenComparingInt(value -> value.patrol.value());

    private static final Comparator<ReplacementAwareCandidateMetrics> HARVEST = Comparator
            .comparingInt(ReplacementAwareCandidateMetrics::projectedContestGain).reversed()
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::ownSemiCollectionGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::fullDayContestGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::resultingFuel).reversed())
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::strongContestGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::semiScoreGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    ReplacementAwareCandidateMetrics::rawGain).reversed())
            .thenComparingInt(ReplacementAwareCandidateMetrics::routeSteps)
            .thenComparingInt(ReplacementAwareCandidateMetrics::routeFuel)
            .thenComparingInt(value -> value.target.value())
            .thenComparingInt(value -> value.patrol.value());

    ReplacementAwareCandidateMetrics {
        Objects.requireNonNull(target, "Candidate target must not be null");
        Objects.requireNonNull(patrol, "Candidate PATROL must not be null");
        if (ownSemiCollectionGain < 0 || fullDayContestGain < 0 || strongContestGain < 0
                || semiScoreGain < 0 || rawGain < 0 || routeSteps < 0 || routeFuel < 0
                || resultingFuel < 0) {
            throw new IllegalArgumentException(
                    "Replacement-aware candidate metrics must be non-negative");
        }
        if (strongContestGain > fullDayContestGain) {
            throw new IllegalArgumentException(
                    "Strong contested collections cannot exceed the contested collections holding them");
        }
    }

    int projectedContestGain() {
        return ownSemiCollectionGain + fullDayContestGain;
    }

    static Comparator<ReplacementAwareCandidateMetrics> coveragePreference() {
        return COVERAGE;
    }

    static Comparator<ReplacementAwareCandidateMetrics> harvestPreference() {
        return HARVEST;
    }
}
