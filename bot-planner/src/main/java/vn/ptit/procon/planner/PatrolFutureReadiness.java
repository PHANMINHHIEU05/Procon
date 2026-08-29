package vn.ptit.procon.planner;

import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/** Deterministic geometric opportunity available to one PATROL on the next day. */
public record PatrolFutureReadiness(
        AgentId agentId,
        Position projectedEndPosition,
        int projectedEndFuel,
        int remainingFutureDays,
        Set<Position> reachableOpportunityPositions,
        Set<BrandId> reachableOpportunityBrands,
        int minimumFuelToAnyOpportunity,
        int fuelSlackAfterNearestOpportunity) {

    public PatrolFutureReadiness {
        Objects.requireNonNull(agentId, "PATROL ID must not be null");
        Objects.requireNonNull(projectedEndPosition, "Projected PATROL position must not be null");
        reachableOpportunityPositions = Set.copyOf(
                Objects.requireNonNull(reachableOpportunityPositions,
                        "Reachable opportunity positions must not be null"));
        reachableOpportunityBrands = Set.copyOf(
                Objects.requireNonNull(reachableOpportunityBrands,
                        "Reachable opportunity brands must not be null"));
        if (projectedEndFuel < 0 || remainingFutureDays < 0
                || minimumFuelToAnyOpportunity < 0 || fuelSlackAfterNearestOpportunity < 0) {
            throw new IllegalArgumentException("Future readiness metrics must be non-negative");
        }
        if (reachableOpportunityPositions.isEmpty()
                && (minimumFuelToAnyOpportunity != 0 || fuelSlackAfterNearestOpportunity != 0)) {
            throw new IllegalArgumentException(
                    "An agent without an opportunity cannot have nearest-opportunity metrics");
        }
    }

    public int reachableOpportunitySpotCount() {
        return reachableOpportunityPositions.size();
    }

    public int reachableOpportunityBrandCount() {
        return reachableOpportunityBrands.size();
    }

    public boolean futureReady() {
        return remainingFutureDays > 0 && !reachableOpportunityPositions.isEmpty();
    }
}