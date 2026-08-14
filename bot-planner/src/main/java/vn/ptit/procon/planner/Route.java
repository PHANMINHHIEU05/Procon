package vn.ptit.procon.planner;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Position;

/** Immutable weighted route expressed entirely in domain directions. */
public record Route(
        Position start,
        Position goal,
        List<Direction> directions,
        int stepsUsed,
        int fuelUsed) {

    public Route {
        Objects.requireNonNull(start, "Route start must not be null");
        Objects.requireNonNull(goal, "Route goal must not be null");
        directions = List.copyOf(Objects.requireNonNull(directions, "Route directions must not be null"));
        if (stepsUsed < 0 || fuelUsed < 0) {
            throw new IllegalArgumentException("Route resource use must be non-negative");
        }
        if (directions.isEmpty() != start.equals(goal)) {
            throw new IllegalArgumentException("Only a start-equals-goal route may contain no moves");
        }
    }

    public List<MoveAction> toMoveActions() {
        return directions.stream().map(MoveAction::new).toList();
    }
}