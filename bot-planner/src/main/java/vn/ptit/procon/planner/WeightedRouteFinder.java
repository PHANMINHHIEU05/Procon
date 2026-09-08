package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
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

/**
 * Deterministic Dijkstra search with remaining PATROL fuel in the state.
 *
 * <p>The label order is <strong>goal-independent</strong>: {@link #labelComparator()} ranks by
 * {@code (steps, fuelUsed, position, -fuel, moves)} and never mentions the goal, and there is no
 * goal-directed heuristic. For a fixed {@code (start, initialFuel)} source the sequence of settled labels
 * is therefore the same whatever the goal is; the goal only decides <em>when the traversal stops</em>.
 * {@link #findAll} exploits exactly that: one traversal answers a whole goal set, and every answer it
 * returns is the same {@link Route} the per-goal {@link #find} would have reconstructed.
 */
public class WeightedRouteFinder {

    public Optional<Route> find(DayState state, AgentState agent, Position goal) {
        if (!V3WorkAuditProbe.isEnabled()) {
            return search(state, agent, goal);
        }
        long started = System.nanoTime();
        Optional<Route> route = search(state, agent, goal);
        V3WorkAuditProbe.recordRouteQuery(state, agent.position(),
                agent.fuel() instanceof FiniteFuel finite ? finite.amount() : -1, goal,
                System.nanoTime() - started);
        return route;
    }

    /**
     * Every route from ONE {@code (position, fuel)} source to a whole goal set, in ONE traversal.
     *
     * <p>Goals that are absent from the map, non-traversable or unreachable within the step budget and the
     * tank are simply absent from the returned map, which is precisely what {@link #find} reports as
     * {@link Optional#empty()} for them. The traversal stops as soon as every reachable goal has been
     * settled, so its cost is the cost of the single most distant goal rather than the sum over goals.
     *
     * <p>Probe accounting: one call is one single-source Dijkstra, so it is recorded once, keyed on its
     * source rather than on any one goal.
     */
    public Map<Position, Route> findAll(DayState state, AgentState agent, Collection<Position> goals) {
        if (!V3WorkAuditProbe.isEnabled()) {
            return searchAll(state, agent, goals);
        }
        long started = System.nanoTime();
        Map<Position, Route> routes = searchAll(state, agent, goals);
        V3WorkAuditProbe.recordRouteQuery(state, agent.position(),
                agent.fuel() instanceof FiniteFuel finite ? finite.amount() : -1, agent.position(),
                System.nanoTime() - started);
        return routes;
    }

    private Optional<Route> search(DayState state, AgentState agent, Position goal) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(agent, "Route agent must not be null");
        Objects.requireNonNull(goal, "Route goal must not be null");
        return Optional.ofNullable(explore(state, agent, List.of(goal)).get(goal));
    }

    private Map<Position, Route> searchAll(DayState state, AgentState agent,
            Collection<Position> goals) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(agent, "Route agent must not be null");
        Objects.requireNonNull(goals, "Route goals must not be null");
        return explore(state, agent, goals);
    }

    /**
     * The one traversal both entry points share. It settles labels in the goal-independent order above and
     * reconstructs a route for a wanted position at the moment that position is first settled — the same
     * moment, and therefore the same predecessor chain and the same label, the single-goal search used.
     */
    private Map<Position, Route> explore(DayState state, AgentState agent, Collection<Position> goals) {
        Position start = agent.position();

        HexMap map = state.matchData().map();
        if (!map.contains(start) || !map.isTraversable(start)) {
            return Map.of();
        }
        if (!(agent.fuel() instanceof FiniteFuel finiteFuel)) {
            return Map.of();
        }
        // The same per-goal admissibility guard the single-goal search applied up front, kept here so an
        // unusable goal never makes the traversal run longer than it has to.
        Set<Position> wanted = new LinkedHashSet<>();
        for (Position goal : goals) {
            Objects.requireNonNull(goal, "Route goal must not be null");
            if (map.contains(goal) && map.isTraversable(goal)) {
                wanted.add(goal);
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<Position, Route> found = new LinkedHashMap<>();
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
            Position settled = current.state.position;
            if (wanted.contains(settled) && !found.containsKey(settled)) {
                found.put(settled, reconstruct(start, settled, current.state, current, predecessors));
                if (found.size() == wanted.size()) {
                    return found;
                }
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
        return found;
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