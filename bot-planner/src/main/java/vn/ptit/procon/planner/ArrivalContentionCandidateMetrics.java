package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** Lexicographic M9.2 search guidance. */
public record ArrivalContentionCandidateMetrics(
        boolean teamNewBrand,
        int routeCollectionGain,
        int arrivalSafeCollections,
        int arrivalTiedCollections,
        int arrivalAtRiskCollections,
        int staticSafeCollections,
        int staticTiedCollections,
        int staticContestedCollections,
        int stronglyStaticContestedCollections,
        int routeSteps,
        int routeFuel,
        int resultingFuel,
        Position targetPosition,
        AgentId patrolAgentId) {

    private static final Comparator<ArrivalContentionCandidateMetrics> DENSITY =
            ArrivalContentionCandidateMetrics::compareDensity;

    private static final Comparator<ArrivalContentionCandidateMetrics> COVERAGE = Comparator
            .comparing(ArrivalContentionCandidateMetrics::teamNewBrand).reversed()
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionCandidateMetrics::arrivalSafeCollections).reversed())
            .thenComparingInt(ArrivalContentionCandidateMetrics::arrivalAtRiskCollections)
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionCandidateMetrics::routeCollectionGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionCandidateMetrics::staticSafeCollections).reversed())
            .thenComparing(DENSITY)
            .thenComparingInt(ArrivalContentionCandidateMetrics::routeSteps)
            .thenComparingInt(ArrivalContentionCandidateMetrics::routeFuel)
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    private static final Comparator<ArrivalContentionCandidateMetrics> HARVEST = Comparator
            .comparingInt(ArrivalContentionCandidateMetrics::arrivalSafeCollections).reversed()
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionCandidateMetrics::routeCollectionGain).reversed())
            .thenComparingInt(ArrivalContentionCandidateMetrics::arrivalAtRiskCollections)
            .thenComparingInt(ArrivalContentionCandidateMetrics::stronglyStaticContestedCollections)
            .thenComparing(DENSITY)
            .thenComparingInt(ArrivalContentionCandidateMetrics::routeSteps)
            .thenComparingInt(ArrivalContentionCandidateMetrics::routeFuel)
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionCandidateMetrics::resultingFuel).reversed())
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    public ArrivalContentionCandidateMetrics {
        Objects.requireNonNull(targetPosition, "Target position must not be null");
        Objects.requireNonNull(patrolAgentId, "PATROL agent ID must not be null");
    }

    public static Comparator<ArrivalContentionCandidateMetrics> coveragePreference() {
        return COVERAGE;
    }

    public static Comparator<ArrivalContentionCandidateMetrics> harvestPreference() {
        return HARVEST;
    }

    public static Comparator<ArrivalContentionCandidateMetrics> densityPreference() {
        return DENSITY;
    }

    private static int compareDensity(
            ArrivalContentionCandidateMetrics first, ArrivalContentionCandidateMetrics second) {
        if (first.routeSteps == 0 || second.routeSteps == 0) {
            if (first.routeSteps == second.routeSteps) {
                return 0;
            }
            return first.routeSteps == 0 ? -1 : 1;
        }
        long firstScaled = (long) first.routeCollectionGain * second.routeSteps;
        long secondScaled = (long) second.routeCollectionGain * first.routeSteps;
        return Long.compare(secondScaled, firstScaled);
    }
}
