package vn.ptit.procon.domain.agent;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** Agent identity and setup position before a role is assigned. */
public record InitialAgent(AgentId id, Position position) {

    public InitialAgent {
        Objects.requireNonNull(id, "Agent ID must not be null");
        Objects.requireNonNull(position, "Initial position must not be null");
    }
}