package vn.ptit.procon.domain.agent;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** Immutable state of one assigned agent. */
public record AgentState(AgentId id, AgentKind kind, Position position, AgentFuel fuel) {

    public AgentState {
        Objects.requireNonNull(id, "Agent ID must not be null");
        Objects.requireNonNull(kind, "Agent kind must not be null");
        Objects.requireNonNull(position, "Agent position must not be null");
        Objects.requireNonNull(fuel, "Agent fuel must not be null");

        if (kind == AgentKind.PATROL && !(fuel instanceof FiniteFuel)) {
            throw new IllegalArgumentException("PATROL agents must have finite fuel");
        }
        if (kind == AgentKind.REFUEL && fuel != UnlimitedFuel.INSTANCE) {
            throw new IllegalArgumentException("REFUEL agents must have unlimited fuel");
        }
    }

    public static AgentState patrol(AgentId id, Position position, int fuel) {
        return new AgentState(id, AgentKind.PATROL, position, new FiniteFuel(fuel));
    }

    public static AgentState refuel(AgentId id, Position position) {
        return new AgentState(id, AgentKind.REFUEL, position, UnlimitedFuel.INSTANCE);
    }
}