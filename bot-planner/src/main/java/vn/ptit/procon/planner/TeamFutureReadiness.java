package vn.ptit.procon.planner;

import java.util.List;
import java.util.Objects;

/** Distribution-aware aggregate of projected PATROL readiness. */
public record TeamFutureReadiness(
        int remainingFutureDays,
        List<PatrolFutureReadiness> patrols,
        int futureReadyPatrolCount,
        int totalReachableOpportunitySpots,
        int totalReachableOpportunityBrands,
        int minimumPatrolReadiness,
        int totalProjectedPatrolFuel,
        int routeCostCacheEntries,
        int routeCostPathfindingExecutions) {

    public TeamFutureReadiness {
        if (remainingFutureDays < 0 || futureReadyPatrolCount < 0
                || totalReachableOpportunitySpots < 0 || totalReachableOpportunityBrands < 0
                || minimumPatrolReadiness < 0 || totalProjectedPatrolFuel < 0
                || routeCostCacheEntries < 0 || routeCostPathfindingExecutions < 0) {
            throw new IllegalArgumentException("Team future readiness metrics must be non-negative");
        }
        patrols = List.copyOf(Objects.requireNonNull(patrols, "PATROL readiness must not be null"));
        if (futureReadyPatrolCount > patrols.size()) {
            throw new IllegalArgumentException("Ready PATROL count cannot exceed PATROL count");
        }
    }

}