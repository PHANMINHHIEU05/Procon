package vn.ptit.procon.engine;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

public record MoveStartedEvent(
        int step, AgentId agentId, Position source, Position destination, int duration)
        implements SimulationEvent {

    public MoveStartedEvent {
        if (step < 0 || duration <= 0) {
            throw new IllegalArgumentException("Invalid move event timing");
        }
        Objects.requireNonNull(agentId, "Agent ID must not be null");
        Objects.requireNonNull(source, "Move source must not be null");
        Objects.requireNonNull(destination, "Move destination must not be null");
    }
}