package vn.ptit.procon.engine;

import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.domain.agent.AgentId;

/** Structured diagnostic for a rejected team plan. */
public record SimulationFailure(
        SimulationFailureCode code,
        Optional<AgentId> agentId,
        int step,
        int actionIndex,
        String message) {

    public SimulationFailure {
        Objects.requireNonNull(code, "Failure code must not be null");
        Objects.requireNonNull(agentId, "Failure agent ID must not be null");
        Objects.requireNonNull(message, "Failure message must not be null");
        if (step < 0) {
            throw new IllegalArgumentException("Failure step must be non-negative: " + step);
        }
        if (actionIndex < -1) {
            throw new IllegalArgumentException("Failure action index must be at least -1: " + actionIndex);
        }
    }

    public static SimulationFailure team(SimulationFailureCode code, String message) {
        return new SimulationFailure(code, Optional.empty(), 0, -1, message);
    }

    public static SimulationFailure agent(
            SimulationFailureCode code,
            AgentId agentId,
            int step,
            int actionIndex,
            String message) {
        return new SimulationFailure(code, Optional.of(agentId), step, actionIndex, message);
    }
}