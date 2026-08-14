package vn.ptit.procon.runtime;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** Sanitized per-agent position and finite-fuel parity values. */
public record AgentParityMismatch(
        AgentId agentId,
        Position predictedPosition,
        Position actualPosition,
        Integer predictedFuel,
        Integer actualFuel) {

    public AgentParityMismatch {
        Objects.requireNonNull(agentId, "Agent ID must not be null");
        Objects.requireNonNull(predictedPosition, "Predicted position must not be null");
    }
}
