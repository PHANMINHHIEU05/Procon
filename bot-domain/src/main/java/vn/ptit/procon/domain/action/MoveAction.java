package vn.ptit.procon.domain.action;

import java.util.Objects;
import vn.ptit.procon.domain.map.Direction;

/** One directional movement command. */
public record MoveAction(Direction direction) implements AgentAction {

    public MoveAction {
        Objects.requireNonNull(direction, "Move direction must not be null");
    }
}