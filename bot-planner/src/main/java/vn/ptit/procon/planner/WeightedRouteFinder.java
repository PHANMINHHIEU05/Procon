package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.rules.FuelRules;
import vn.ptit.procon.rules.MovementRules;

/** Deterministic Dijkstra search with remaining PATROL fuel in the state. */
public class WeightedRouteFinder {

    public Optional<Route> find(DayState state, AgentState agent, Position goal) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(agent, "Route agent must not be null");
        Objects.requireNonNull(goal, "Route goal must not be null");
        Position start = agent.position();

        HexMap map = state.matchData().map();
        if (!map.contains(start) || !map.contains(goal) || !map.isTraversable(start)
                || !map.isTraversable(goal)) {
            return Optional.empty();
        }
        if (!(agent.fuel() instanceof FiniteFuel finiteFuel)) {
            return Optional.empty();
        }
        int initialFuel = finiteFuel.amount();
        SearchState initial = new SearchState(start, initialFuel);
        Map<SearchState, Label> best = new HashMap<>();
        Map<SearchState, Predecessor> predecessors = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>(labelComparator());
        Label initialLabel = new Label(initial, 0, 0, 0);
        best.put(initial, initialLabel);
        queue.add(initialLabel);

        while (!queue.isEmpty()) {
            Label current = queue.poll();
            if (best.get(current.state).compareTo(current) != 0) {
                continue;
            }
            if (current.state.position.equals(goal)) {
                return Optional.of(reconstruct(start, goal, current.state, current, predecessors));
            }

            for (Direction direction : Direction.values()) {
                Optional<Position> neighbor = map.neighbor(current.state.position, direction);
                if (neighbor.isEmpty()) {
                    continue;
                }
                Position destination = neighbor.orElseThrow();
                if (map.terrainAt(destination) == Terrain.POND) {
                    continue;
                }
                TrafficStatus traffic = map.terrainAt(current.state.position) == Terrain.ROAD
                        ? state.roadTraffic().get(current.state.position)
                        : null;
                if (map.terrainAt(current.state.position) == Terrain.ROAD && traffic == null) {
                    continue;
                }
                Optional<MoveCost> possibleCost = MovementRules.costFromSource(
                        map, current.state.position, traffic);
                if (possibleCost.isEmpty()) {
                    continue;
                }
                MoveCost cost = possibleCost.orElseThrow();
                int nextSteps;
                try {
                    nextSteps = Math.addExact(current.steps, cost.stepCost());
                } catch (ArithmeticException exception) {
                    continue;
                }
                FiniteFuel currentFuel = new FiniteFuel(current.state.fuel);
                if (nextSteps > state.stepBudget() || !FuelRules.canAfford(currentFuel, cost)) {
                    continue;
                }
                FiniteFuel remainingFuel = (FiniteFuel) FuelRules.remainingFuelAfterMove(currentFuel, cost);
                SearchState next = new SearchState(destination, remainingFuel.amount());
                int nextFuelUsed = initialFuel - next.fuel;
                Label candidate = new Label(next, nextSteps, nextFuelUsed, current.moves + 1);
                Label previous = best.get(next);
                if (previous == null || candidate.compareTo(previous) < 0) {
                    best.put(next, candidate);
                    predecessors.put(next, new Predecessor(current.state, direction));
                    queue.add(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private Route reconstruct(
            Position start,
            Position goal,
            SearchState finalState,
            Label finalLabel,
            Map<SearchState, Predecessor> predecessors) {
        List<Direction> reversed = new ArrayList<>();
        SearchState cursor = finalState;
        while (!cursor.position.equals(start)) {
            Predecessor predecessor = predecessors.get(cursor);
            if (predecessor == null) {
                throw new IllegalStateException("Missing route predecessor for " + cursor);
            }
            reversed.add(predecessor.direction);
            cursor = predecessor.previous;
        }
        List<Direction> directions = new ArrayList<>(reversed.reversed());
        return new Route(start, goal, directions, finalLabel.steps, finalLabel.fuelUsed);
    }

    private Comparator<Label> labelComparator() {
        return Comparator.comparingInt((Label label) -> label.steps)
                .thenComparingInt(label -> label.fuelUsed)
                .thenComparingInt(label -> label.state.position.value())
                .thenComparingInt(label -> -label.state.fuel)
                .thenComparingInt(label -> label.moves);
    }

    private record SearchState(Position position, int fuel) {
    }

    private record Predecessor(SearchState previous, Direction direction) {
    }

    private record Label(SearchState state, int steps, int fuelUsed, int moves)
            implements Comparable<Label> {

        @Override
        public int compareTo(Label other) {
            return Comparator.comparingInt((Label label) -> label.steps)
                    .thenComparingInt(label -> label.fuelUsed)
                    .thenComparingInt(label -> label.state.position.value())
                    .thenComparingInt(label -> -label.state.fuel)
                    .thenComparingInt(label -> label.moves)
                    .compare(this, other);
        }
    }
}