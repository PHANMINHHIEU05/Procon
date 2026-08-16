package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** Lexicographic M10 candidate guidance aligned with intent-adjusted plan value. */
record IntentAwareCandidateMetrics(
        boolean teamNewBrand,
        int adjustedScore,
        int forecastRealizableGain,
        int rawGain,
        int likelyClaimedFirst,
        int tieCollections,
        int routeSteps,
        int routeFuel,
        int resultingFuel,
        Position targetPosition,
        AgentId patrolAgentId) {

    private static final Comparator<IntentAwareCandidateMetrics> DENSITY =
            IntentAwareCandidateMetrics::compareDensity;

    private static final Comparator<IntentAwareCandidateMetrics> COVERAGE = Comparator
            .comparing(IntentAwareCandidateMetrics::teamNewBrand).reversed()
            .thenComparing(Comparator.comparingInt(IntentAwareCandidateMetrics::adjustedScore).reversed())
            .thenComparing(Comparator.comparingInt(
                    IntentAwareCandidateMetrics::forecastRealizableGain).reversed())
            .thenComparing(Comparator.comparingInt(IntentAwareCandidateMetrics::rawGain).reversed())
            .thenComparingInt(IntentAwareCandidateMetrics::likelyClaimedFirst)
            .thenComparingInt(IntentAwareCandidateMetrics::routeSteps)
            .thenComparingInt(IntentAwareCandidateMetrics::routeFuel)
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    private static final Comparator<IntentAwareCandidateMetrics> HARVEST = Comparator
            .comparingInt(IntentAwareCandidateMetrics::adjustedScore).reversed()
            .thenComparing(Comparator.comparingInt(
                    IntentAwareCandidateMetrics::forecastRealizableGain).reversed())
            .thenComparing(Comparator.comparingInt(IntentAwareCandidateMetrics::rawGain).reversed())
            .thenComparingInt(IntentAwareCandidateMetrics::likelyClaimedFirst)
            .thenComparingInt(IntentAwareCandidateMetrics::tieCollections)
            .thenComparing(DENSITY)
            .thenComparingInt(IntentAwareCandidateMetrics::routeSteps)
            .thenComparingInt(IntentAwareCandidateMetrics::routeFuel)
            .thenComparing(Comparator.comparingInt(IntentAwareCandidateMetrics::resultingFuel).reversed())
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    IntentAwareCandidateMetrics {
        Objects.requireNonNull(targetPosition, "Target position must not be null");
        Objects.requireNonNull(patrolAgentId, "PATROL agent ID must not be null");
    }

    static Comparator<IntentAwareCandidateMetrics> coveragePreference() {
        return COVERAGE;
    }

    static Comparator<IntentAwareCandidateMetrics> harvestPreference() {
        return HARVEST;
    }

    private static int compareDensity(IntentAwareCandidateMetrics first, IntentAwareCandidateMetrics second) {
        if (first.routeSteps == 0 || second.routeSteps == 0) {
            if (first.routeSteps == second.routeSteps) {
                return 0;
            }
            return first.routeSteps == 0 ? -1 : 1;
        }
        long firstScaled = (long) first.forecastRealizableGain * second.routeSteps;
        long secondScaled = (long) second.forecastRealizableGain * first.routeSteps;
        return Long.compare(secondScaled, firstScaled);
    }
}