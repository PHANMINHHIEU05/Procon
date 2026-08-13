package vn.ptit.procon.engine;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

public record FuelConsumedEvent(
        int step, AgentId agentId, Position position, int before, int after)
        implements SimulationEvent {

    public FuelConsumedEvent {
        if (step < 0 || before < 0 || after < 0 || after > before) {
            throw new IllegalArgumentException("Invalid fuel consumption event values");
        }
        Objects.requireNonNull(agentId, "Agent ID must not be null");
        Objects.requireNonNull(position, "Fuel event position must not be null");
    }
}