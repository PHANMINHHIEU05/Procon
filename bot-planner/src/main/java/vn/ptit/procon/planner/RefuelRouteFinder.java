package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.rules.MovementRules;

/** Deterministic step-weighted Dijkstra search for unlimited-fuel REFUEL agents. */
public class RefuelRouteFinder {

    public Optional<Route> find(DayState state, AgentState refuel, Position goal) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(refuel, "REFUEL agent must not be null");
        Objects.requireNonNull(goal, "Route goal must not be null");
        if (refuel.kind() != AgentKind.REFUEL) {
            return Optional.empty();
        }

        Position start = refuel.position();
        HexMap map = state.matchData().map();
        if (!map.contains(start) || !map.contains(goal)
                || !map.isTraversable(start) || !map.isTraversable(goal)) {
            return Optional.empty();
        }

        Map<Position, Label> best = new HashMap<>();
        Map<Position, Predecessor> predecessors = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>(labelComparator());
        Label initial = new Label(start, 0, 0);
        best.put(start, initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            Label current = queue.poll();
            if (best.get(current.position()).compareTo(current) != 0) {
                continue;
            }
            if (current.position().equals(goal)) {
                return Optional.of(reconstruct(start, goal, current, predecessors));
            }

            for (Direction direction : Direction.values()) {
                Optional<Position> possibleNeighbor = map.neighbor(current.position(), direction);
                if (possibleNeighbor.isEmpty()) {
                    continue;
                }
                Position destination = possibleNeighbor.orElseThrow();
                if (map.terrainAt(destination) == Terrain.POND) {
                    continue;
                }
                TrafficStatus traffic = map.terrainAt(current.position()) == Terrain.ROAD
                        ? state.roadTraffic().get(current.position())
                        : null;
                if (map.terrainAt(current.position()) == Terrain.ROAD && traffic == null) {
                    continue;
                }
                Optional<MoveCost> possibleCost = MovementRules.costFromSource(
                        map, current.position(), traffic);
                if (possibleCost.isEmpty()) {
                    continue;
                }
                int nextSteps;
                try {
                    nextSteps = Math.addExact(current.steps(), possibleCost.orElseThrow().stepCost());
                } catch (ArithmeticException exception) {
                    continue;
                }
                if (nextSteps > state.stepBudget()) {
                    continue;
                }
                Label candidate = new Label(destination, nextSteps, current.moves() + 1);
                Label previous = best.get(destination);
                if (previous == null || candidate.compareTo(previous) < 0) {
                    best.put(destination, candidate);
                    predecessors.put(destination, new Predecessor(current.position(), direction));
                    queue.add(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private Route reconstruct(
            Position start,
            Position goal,
            Label finalLabel,
            Map<Position, Predecessor> predecessors) {
        List<Direction> reversed = new ArrayList<>();
        Position cursor = goal;
        while (!cursor.equals(start)) {
            Predecessor predecessor = predecessors.get(cursor);
            if (predecessor == null) {
                throw new IllegalStateException("Missing REFUEL route predecessor for " + cursor);
            }
            reversed.add(predecessor.direction());
            cursor = predecessor.previous();
        }
        return new Route(start, goal, new ArrayList<>(reversed.reversed()), finalLabel.steps(), 0);
    }

    private Comparator<Label> labelComparator() {
        return Comparator.comparingInt(Label::steps)
                .thenComparingInt(label -> label.position().value())
                .thenComparingInt(Label::moves);
    }

    private record Predecessor(Position previous, Direction direction) {
    }

    private record Label(Position position, int steps, int moves) implements Comparable<Label> {

        @Override
        public int compareTo(Label other) {
            return Comparator.comparingInt(Label::steps)
                    .thenComparingInt(label -> label.position().value())
                    .thenComparingInt(Label::moves)
                    .compare(this, other);
        }
    }
}
