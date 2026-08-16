package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** Lexicographic M9.3 candidate guidance aligned with the risk-adjusted objective. */
record RiskAdjustedCandidateMetrics(
        boolean teamNewBrand,
        int adjustedCollectionScore,
        int rawCollectionGain,
        int arrivalAtRiskCollections,
        int arrivalSafeCollections,
        int stronglyStaticContestedCollections,
        int routeSteps,
        int routeFuel,
        int resultingFuel,
        Position targetPosition,
        AgentId patrolAgentId) {

    private static final Comparator<RiskAdjustedCandidateMetrics> DENSITY =
            RiskAdjustedCandidateMetrics::compareDensity;

    private static final Comparator<RiskAdjustedCandidateMetrics> COVERAGE = Comparator
            .comparing(RiskAdjustedCandidateMetrics::teamNewBrand).reversed()
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedCandidateMetrics::adjustedCollectionScore).reversed())
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedCandidateMetrics::rawCollectionGain).reversed())
            .thenComparingInt(RiskAdjustedCandidateMetrics::arrivalAtRiskCollections)
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedCandidateMetrics::arrivalSafeCollections).reversed())
            .thenComparing(DENSITY)
            .thenComparingInt(RiskAdjustedCandidateMetrics::routeSteps)
            .thenComparingInt(RiskAdjustedCandidateMetrics::routeFuel)
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    private static final Comparator<RiskAdjustedCandidateMetrics> HARVEST = Comparator
            .comparingInt(RiskAdjustedCandidateMetrics::adjustedCollectionScore).reversed()
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedCandidateMetrics::rawCollectionGain).reversed())
            .thenComparingInt(RiskAdjustedCandidateMetrics::arrivalAtRiskCollections)
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedCandidateMetrics::arrivalSafeCollections).reversed())
            .thenComparingInt(RiskAdjustedCandidateMetrics::stronglyStaticContestedCollections)
            .thenComparing(DENSITY)
            .thenComparingInt(RiskAdjustedCandidateMetrics::routeSteps)
            .thenComparingInt(RiskAdjustedCandidateMetrics::routeFuel)
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedCandidateMetrics::resultingFuel).reversed())
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    RiskAdjustedCandidateMetrics {
        Objects.requireNonNull(targetPosition, "Target position must not be null");
        Objects.requireNonNull(patrolAgentId, "PATROL agent ID must not be null");
    }

    static Comparator<RiskAdjustedCandidateMetrics> coveragePreference() {
        return COVERAGE;
    }

    static Comparator<RiskAdjustedCandidateMetrics> harvestPreference() {
        return HARVEST;
    }

    private static int compareDensity(RiskAdjustedCandidateMetrics first, RiskAdjustedCandidateMetrics second) {
        if (first.routeSteps == 0 || second.routeSteps == 0) {
            if (first.routeSteps == second.routeSteps) {
                return 0;
            }
            return first.routeSteps == 0 ? -1 : 1;
        }
        long firstScaled = (long) first.rawCollectionGain * second.routeSteps;
        long secondScaled = (long) second.rawCollectionGain * first.routeSteps;
        return Long.compare(secondScaled, firstScaled);
    }
}