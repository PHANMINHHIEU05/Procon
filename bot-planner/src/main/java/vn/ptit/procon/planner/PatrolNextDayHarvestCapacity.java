package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** Exact bounded next-day multi-opportunity capacity for one projected PATROL end state. */
public record PatrolNextDayHarvestCapacity(
        AgentId agentId,
        Position projectedEndPosition,
        int projectedEndFuel,
        int remainingFutureDays,
        boolean stationaryOpportunityAvailable,
        int maxReachableDistinctSpots,
        int maxReachableDistinctBrands,
        int bestRemainingFuelAtMaxSpotCount,
        int dpStatesEvaluated) {

    public PatrolNextDayHarvestCapacity {
        Objects.requireNonNull(agentId, "PATROL ID must not be null");
        Objects.requireNonNull(projectedEndPosition, "Projected end position must not be null");
        if (projectedEndFuel < 0 || remainingFutureDays < 0
                || maxReachableDistinctSpots < 0 || maxReachableDistinctBrands < 0
                || bestRemainingFuelAtMaxSpotCount < 0 || dpStatesEvaluated < 0) {
            throw new IllegalArgumentException("Next-day harvest-capacity metrics must be non-negative");
        }
        if (maxReachableDistinctBrands > maxReachableDistinctSpots) {
            throw new IllegalArgumentException("Distinct brand capacity cannot exceed distinct spot capacity");
        }
        if (bestRemainingFuelAtMaxSpotCount > projectedEndFuel) {
            throw new IllegalArgumentException("Remaining fuel cannot exceed projected end fuel");
        }
        if (remainingFutureDays == 0 && (stationaryOpportunityAvailable
                || maxReachableDistinctSpots != 0 || maxReachableDistinctBrands != 0
                || bestRemainingFuelAtMaxSpotCount != 0 || dpStatesEvaluated != 0)) {
            throw new IllegalArgumentException("Final-day harvest capacity must be neutral");
        }
    }

    /** Structural distribution tuple. Fuel alone deliberately does not create harvest capacity. */
    public HarvestCapacityOrdinal ordinal() {
        return new HarvestCapacityOrdinal(maxReachableDistinctBrands, maxReachableDistinctSpots);
    }
}