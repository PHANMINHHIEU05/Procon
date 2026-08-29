package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Distribution-preserving team aggregate for M13.1. */
public record TeamNextDayHarvestCapacity(
        int remainingFutureDays,
        int nextDayStepBudget,
        List<PatrolNextDayHarvestCapacity> patrols,
        List<HarvestCapacityOrdinal> weakestFirstCapacityVector,
        int minimumPatrolDistinctSpots,
        int minimumPatrolDistinctBrands,
        int totalPatrolDistinctSpotCapacity,
        int totalPatrolDistinctBrandCapacity,
        int totalProjectedPatrolFuel,
        int routeCostCacheEntries,
        int pathfindingExecutions,
        int totalDpStatesEvaluated) {

    public TeamNextDayHarvestCapacity {
        if (remainingFutureDays < 0 || nextDayStepBudget < 0
                || minimumPatrolDistinctSpots < 0 || minimumPatrolDistinctBrands < 0
                || totalPatrolDistinctSpotCapacity < 0 || totalPatrolDistinctBrandCapacity < 0
                || totalProjectedPatrolFuel < 0 || routeCostCacheEntries < 0
                || pathfindingExecutions < 0 || totalDpStatesEvaluated < 0) {
            throw new IllegalArgumentException("Team harvest-capacity metrics must be non-negative");
        }
        patrols = List.copyOf(Objects.requireNonNull(patrols, "PATROL capacities must not be null"));
        weakestFirstCapacityVector = List.copyOf(Objects.requireNonNull(
                weakestFirstCapacityVector, "Weakest-first vector must not be null"));
        if (patrols.size() != weakestFirstCapacityVector.size()) {
            throw new IllegalArgumentException("Capacity vector must contain one tuple per PATROL");
        }
        List<HarvestCapacityOrdinal> sorted = patrols.stream()
                .map(PatrolNextDayHarvestCapacity::ordinal).sorted().toList();
        if (!sorted.equals(weakestFirstCapacityVector)) {
            throw new IllegalArgumentException("Capacity vector must be sorted weakest first");
        }
    }

    /** Compare only structural harvest capacity; raw fuel never makes proactive REFUEL positive. */
    public boolean betterStructuralCapacityThan(TeamNextDayHarvestCapacity other) {
        return compareStructural(this, Objects.requireNonNull(other, "Other capacity must not be null")) > 0;
    }

    static int compareStructural(TeamNextDayHarvestCapacity left, TeamNextDayHarvestCapacity right) {
        List<HarvestCapacityOrdinal> a = left.weakestFirstCapacityVector;
        List<HarvestCapacityOrdinal> b = right.weakestFirstCapacityVector;
        int common = Math.min(a.size(), b.size());
        for (int index = 0; index < common; index++) {
            int compared = a.get(index).compareTo(b.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        int count = Integer.compare(a.size(), b.size());
        if (count != 0) {
            return count;
        }
        int spots = Integer.compare(
                left.totalPatrolDistinctSpotCapacity, right.totalPatrolDistinctSpotCapacity);
        return spots != 0 ? spots : Integer.compare(
                left.totalPatrolDistinctBrandCapacity, right.totalPatrolDistinctBrandCapacity);
    }

    static TeamNextDayHarvestCapacity aggregate(
            int remainingDays,
            int nextDayBudget,
            List<PatrolNextDayHarvestCapacity> patrols,
            int cacheEntries,
            int pathfindingExecutions) {
        List<PatrolNextDayHarvestCapacity> ordered = patrols.stream()
                .sorted(Comparator.comparingInt(value -> value.agentId().value())).toList();
        List<HarvestCapacityOrdinal> vector = new ArrayList<>(ordered.stream()
                .map(PatrolNextDayHarvestCapacity::ordinal).toList());
        vector.sort(Comparator.naturalOrder());
        int minimumSpots = ordered.stream().mapToInt(
                PatrolNextDayHarvestCapacity::maxReachableDistinctSpots).min().orElse(0);
        int minimumBrands = ordered.stream().mapToInt(
                PatrolNextDayHarvestCapacity::maxReachableDistinctBrands).min().orElse(0);
        return new TeamNextDayHarvestCapacity(
                remainingDays, nextDayBudget, ordered, vector, minimumSpots, minimumBrands,
                ordered.stream().mapToInt(PatrolNextDayHarvestCapacity::maxReachableDistinctSpots).sum(),
                ordered.stream().mapToInt(PatrolNextDayHarvestCapacity::maxReachableDistinctBrands).sum(),
                ordered.stream().mapToInt(PatrolNextDayHarvestCapacity::projectedEndFuel).sum(),
                cacheEntries, pathfindingExecutions,
                ordered.stream().mapToInt(PatrolNextDayHarvestCapacity::dpStatesEvaluated).sum());
    }
}