package vn.ptit.procon.planner;

import java.util.Comparator;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** Immutable route-wide metrics used only to guide bounded M8.1 search. */
public record HarvestCandidateMetrics(
        int routeCollectionGain,
        int routeSteps,
        int routeFuel,
        int resultingFuel,
        boolean teamNewBrand,
        Position targetPosition,
        AgentId patrolAgentId) {

    private static final Comparator<HarvestCandidateMetrics> DENSITY_PREFERENCE =
            HarvestCandidateMetrics::compareDensity;

    private static final Comparator<HarvestCandidateMetrics> COVERAGE_PREFERENCE = Comparator
            .comparing(HarvestCandidateMetrics::teamNewBrand).reversed()
            .thenComparing(Comparator.comparingInt(
                    HarvestCandidateMetrics::routeCollectionGain).reversed())
            .thenComparing(DENSITY_PREFERENCE)
            .thenComparingInt(HarvestCandidateMetrics::routeSteps)
            .thenComparingInt(HarvestCandidateMetrics::routeFuel)
            .thenComparing(Comparator.comparingInt(
                    HarvestCandidateMetrics::resultingFuel).reversed())
            .thenComparingInt(candidate -> candidate.targetPosition().value())
            .thenComparingInt(candidate -> candidate.patrolAgentId().value());

    private static final Comparator<HarvestCandidateMetrics> HARVEST_PREFERENCE = Comparator
            .comparingInt(HarvestCandidateMetrics::routeCollectionGain).reversed()
            .thenComparing(DENSITY_PREFERENCE)
            .thenComparingInt(HarvestCandidateMetrics::routeSteps)
            .thenComparingInt(HarvestCandidateMetrics::routeFuel)
            .thenComparing(Comparator.comparingInt(
                    HarvestCandidateMetrics::resultingFuel).reversed())
            .thenComparingInt(candidate -> candidate.targetPosition().value())
            .thenComparingInt(candidate -> candidate.patrolAgentId().value());

    public HarvestCandidateMetrics {
        if (routeCollectionGain <= 0 || routeSteps < 0 || routeFuel < 0 || resultingFuel < 0) {
            throw new IllegalArgumentException("Harvest candidate metrics are invalid");
        }
        if (targetPosition == null || patrolAgentId == null) {
            throw new NullPointerException("Harvest candidate identity must not be null");
        }
    }

    public static Comparator<HarvestCandidateMetrics> coveragePreference() {
        return COVERAGE_PREFERENCE;
    }

    public static Comparator<HarvestCandidateMetrics> harvestPreference() {
        return HARVEST_PREFERENCE;
    }

    public static Comparator<HarvestCandidateMetrics> densityPreference() {
        return DENSITY_PREFERENCE;
    }

    private static int compareDensity(
            HarvestCandidateMetrics first, HarvestCandidateMetrics second) {
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
