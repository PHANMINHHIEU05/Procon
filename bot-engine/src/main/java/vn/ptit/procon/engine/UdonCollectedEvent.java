package vn.ptit.procon.engine;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

public record UdonCollectedEvent(
        int step,
        AgentId agentId,
        Position position,
        BrandId brand,
        int remainingStock)
        implements SimulationEvent {

    public UdonCollectedEvent {
        if (step < 0 || remainingStock < 0) {
            throw new IllegalArgumentException("Invalid Udon collection event values");
        }
        Objects.requireNonNull(agentId, "Agent ID must not be null");
        Objects.requireNonNull(position, "Udon position must not be null");
        Objects.requireNonNull(brand, "Udon brand must not be null");
    }
}