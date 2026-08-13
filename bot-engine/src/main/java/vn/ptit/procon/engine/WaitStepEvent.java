package vn.ptit.procon.engine;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

public record WaitStepEvent(int step, AgentId agentId, Position position, boolean automatic)
        implements SimulationEvent {

    public WaitStepEvent {
        if (step <= 0) {
            throw new IllegalArgumentException("WAIT event step must be positive: " + step);
        }
        Objects.requireNonNull(agentId, "Agent ID must not be null");
        Objects.requireNonNull(position, "WAIT position must not be null");
    }
}