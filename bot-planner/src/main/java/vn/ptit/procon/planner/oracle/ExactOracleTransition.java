package vn.ptit.procon.planner.oracle;

import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.planner.Route;

/** One meaningful route transition, never a single movement command. */
public record ExactOracleTransition(AgentId agentId, Position target, Route route, int arrivalStep,
        int collectionGain, int fuelAfter, ExactOracleState resultingState) {
    public ExactOracleTransition {
        if (agentId == null || target == null || route == null || arrivalStep < 0 || collectionGain < 0
                || fuelAfter < 0 || resultingState == null) throw new IllegalArgumentException("Invalid transition");
    }
}
