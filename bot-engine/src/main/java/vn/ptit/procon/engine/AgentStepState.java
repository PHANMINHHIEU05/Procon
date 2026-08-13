package vn.ptit.procon.engine;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentFuel;
import vn.ptit.procon.domain.map.Position;

/** Immutable end-of-step view of one agent. */
public record AgentStepState(Position position, AgentFuel fuel, AgentActivity activity) {

    public AgentStepState {
        Objects.requireNonNull(position, "Step position must not be null");
        Objects.requireNonNull(fuel, "Step fuel must not be null");
        Objects.requireNonNull(activity, "Step activity must not be null");
    }
}