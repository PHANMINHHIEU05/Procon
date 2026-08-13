package vn.ptit.procon.engine;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

public record RefueledEvent(
        int step,
        AgentId patrolId,
        Position position,
        int before,
        int after,
        List<AgentId> refuelAgents)
        implements SimulationEvent {

    public RefueledEvent {
        if (step <= 0 || before < 0 || after < before) {
            throw new IllegalArgumentException("Invalid REFUEL event values");
        }
        Objects.requireNonNull(patrolId, "PATROL ID must not be null");
        Objects.requireNonNull(position, "REFUEL position must not be null");
        refuelAgents = List.copyOf(
                Objects.requireNonNull(refuelAgents, "REFUEL agent IDs must not be null"));
        if (refuelAgents.isEmpty()) {
            throw new IllegalArgumentException("At least one REFUEL agent is required");
        }
    }
}