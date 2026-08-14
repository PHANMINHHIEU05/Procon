package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/** Immutable description of one feasible next PATROL visit in team coordination. */
public record TeamTargetCandidate(
        AgentId patrolAgentId,
        Position targetPosition,
        BrandId brand,
        Route route,
        int routeSteps,
        int routeFuel,
        boolean newBrandForPatrolToday,
        boolean newBrandForTeamToday,
        int projectedCollectionGain,
        int resultingFuel) {

    public TeamTargetCandidate {
        Objects.requireNonNull(patrolAgentId, "PATROL agent ID must not be null");
        Objects.requireNonNull(targetPosition, "Target position must not be null");
        Objects.requireNonNull(brand, "Target brand must not be null");
        Objects.requireNonNull(route, "Route must not be null");
        if (!targetPosition.equals(route.goal())) {
            throw new IllegalArgumentException("Candidate target must equal its route goal");
        }
        if (routeSteps != route.stepsUsed() || routeFuel != route.fuelUsed()) {
            throw new IllegalArgumentException("Candidate resource use must equal its route");
        }
        if (routeSteps < 0 || routeFuel < 0 || projectedCollectionGain <= 0 || resultingFuel < 0) {
            throw new IllegalArgumentException("Candidate resources and collection gain are invalid");
        }
    }
}