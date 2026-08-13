package vn.ptit.procon.engine;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

public record MoveCompletedEvent(
        int step, AgentId agentId, Position source, Position destination)
        implements SimulationEvent {

    public MoveCompletedEvent {
        if (step <= 0) {
            throw new IllegalArgumentException("Move completion step must be positive: " + step);
        }
        Objects.requireNonNull(agentId, "Agent ID must not be null");
        Objects.requireNonNull(source, "Move source must not be null");
        Objects.requireNonNull(destination, "Move destination must not be null");
    }
}