package vn.ptit.procon.planner.v2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.rules.MovementRules;

/**
 * Daily deterministic Pareto route catalog for Planner V2.
 *
 * <p>All Dijkstra work happens while the catalog is built. Search reads immutable concrete routes
 * only, including the arrival time of every Udon spot crossed by a leg.</p>
 */
final class JointRouteCatalog {

    record SpotArrival(Position spot, int step) {
    }

    record CatalogRoute(Route route, List<SpotArrival> spotArrivals) {
        CatalogRoute {
            Objects.requireNonNull(route, "Route must not be null");
            spotArrivals = List.copyOf(Objects.requireNonNull(
                    spotArrivals, "Spot arrivals must not be null"));
        }
    }

    private final Map<Position, Map<Position, List<CatalogRoute>>> routesByStart;
    private final List<UdonSpot> spots;
    private final int pathfindingExecutions;

    private JointRouteCatalog(
            Map<Position, Map<Position, List<CatalogRoute>>> routesByStart,
            List<UdonSpot> spots,
            int pathfindingExecutions) {
        this.routesByStart = routesByStart;
        this.spots = List.copyOf(spots);
        this.pathfindingExecutions = pathfindingExecutions;
    }

    static JointRouteCatalog forState(DayState state) {
        return forState(state, false);
    }

    static JointRouteCatalog forState(DayState state, boolean includeOpponentSources) {
        Objects.requireNonNull(state, "Day state must not be null");
        List<UdonSpot> spots = state.matchData().udonSpots().stream()
                .sorted(Comparator.comparingInt(spot -> spot.position().value()))
                .toList();
        Map<Position, UdonSpot> spotsByPosition = new LinkedHashMap<>();
        spots.forEach(spot -> spotsByPosition.put(spot.position(), spot));

        Set<Position> sourceSet = new LinkedHashSet<>();
        for (AgentState agent : state.agents()) {
            if (includeOpponentSources || agent.kind() == AgentKind.PATROL) {
                sourceSet.add(agent.position());
            }
        }
        if (includeOpponentSources) {
            state.observedOthers().stream().flatMap(group -> group.agents().stream())
                    .map(vn.ptit.procon.domain.opponent.ObservedOtherAgent::position)
                    .forEach(sourceSet::add);
        }
        spots.forEach(spot -> sourceSet.add(spot.position()));
        List<Position> sources = sourceSet.stream()
                .sorted(Comparator.comparingInt(Position::value))
                .toList();

        Map<Position, Map<Position, List<CatalogRoute>>> allRoutes = new LinkedHashMap<>();
        for (Position source : sources) {
            allRoutes.put(source, routesFrom(state, source, spots, spotsByPosition));
        }
        return new JointRouteCatalog(Map.copyOf(allRoutes), spots, sources.size());
    }

    List<CatalogRoute> routes(Position start, Position goal) {
        return routesByStart.getOrDefault(start, Map.of()).getOrDefault(goal, List.of());
    }

    int pathfindingExecutions() {
        return pathfindingExecutions;
    }

    List<UdonSpot> spots() {
        return spots;
    }

    private static Map<Position, List<CatalogRoute>> routesFrom(
            DayState state,
            Position start,
            List<UdonSpot> goals,
            Map<Position, UdonSpot> spotsByPosition) {
        HexMap map = state.matchData().map();
        int maxSteps = state.stepBudget();
        int maxFuel = state.matchData().patrolFuelCapacity().value();
        Map<Position, List<Label>> labelsByPosition = new LinkedHashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>(Label.ORDER);
        Label root = new Label(start, 0, 0, List.of());
        labelsByPosition.put(start, new ArrayList<>(List.of(root)));
        queue.add(root);

        while (!queue.isEmpty()) {
            Label current = queue.poll();
            if (!labelsByPosition.getOrDefault(current.position, List.of()).contains(current)) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                Position destination = map.neighbor(current.position, direction).orElse(null);
                if (destination == null || map.terrainAt(destination) == Terrain.POND) {
                    continue;
                }
                TrafficStatus traffic = map.terrainAt(current.position) == Terrain.ROAD
                        ? state.roadTraffic().get(current.position) : null;
                MoveCost cost = MovementRules.costFromSource(map, current.position, traffic).orElse(null);
                if (cost == null) {
                    continue;
                }
                int nextSteps = current.steps + cost.stepCost();
                int nextFuel = current.fuel + cost.patrolFuelCost();
                if (nextSteps > maxSteps || nextFuel > maxFuel) {
                    continue;
                }
                List<Direction> directions = new ArrayList<>(current.directions);
                directions.add(direction);
                Label candidate = new Label(destination, nextSteps, nextFuel, directions);
                if (addParetoLabel(labelsByPosition, candidate)) {
                    queue.add(candidate);
                }
            }
        }

        Map<Position, List<CatalogRoute>> result = new LinkedHashMap<>();
        for (UdonSpot goal : goals) {
            if (goal.position().equals(start)) {
                continue;
            }
            List<CatalogRoute> routes = labelsByPosition.getOrDefault(goal.position(), List.of()).stream()
                    .map(label -> toCatalogRoute(state, start, goal.position(), label, spotsByPosition))
                    .sorted(Comparator.comparingInt((CatalogRoute value) -> value.route().stepsUsed())
                            .thenComparingInt(value -> value.route().fuelUsed())
                            .thenComparing(value -> directionKey(value.route().directions())))
                    .toList();
            if (!routes.isEmpty()) {
                result.put(goal.position(), routes);
            }
        }
        return Map.copyOf(result);
    }

    private static boolean addParetoLabel(Map<Position, List<Label>> labelsByPosition, Label candidate) {
        List<Label> existing = labelsByPosition.computeIfAbsent(
                candidate.position, ignored -> new ArrayList<>());
        if (existing.stream().anyMatch(label -> dominates(label, candidate))) {
            return false;
        }
        existing.removeIf(label -> dominates(candidate, label));
        existing.add(candidate);
        existing.sort(Label.ORDER);
        return true;
    }

    private static boolean dominates(Label left, Label right) {
        if (left.steps == right.steps && left.fuel == right.fuel) {
            return directionKey(left.directions).compareTo(directionKey(right.directions)) <= 0;
        }
        return left.steps <= right.steps && left.fuel <= right.fuel;
    }

    private static CatalogRoute toCatalogRoute(
            DayState state,
            Position start,
            Position goal,
            Label label,
            Map<Position, UdonSpot> spotsByPosition) {
        Position cursor = start;
        int elapsed = 0;
        List<SpotArrival> spotArrivals = new ArrayList<>();
        for (Direction direction : label.directions) {
            TrafficStatus traffic = state.matchData().map().terrainAt(cursor) == Terrain.ROAD
                    ? state.roadTraffic().get(cursor) : null;
            MoveCost cost = MovementRules.costFromSource(
                    state.matchData().map(), cursor, traffic).orElseThrow();
            elapsed += cost.stepCost();
            cursor = state.matchData().map().neighbor(cursor, direction).orElseThrow();
            if (spotsByPosition.containsKey(cursor)) {
                spotArrivals.add(new SpotArrival(cursor, elapsed));
            }
        }
        if (!cursor.equals(goal) || elapsed != label.steps) {
            throw new IllegalStateException("Route catalog reconstruction diverged from its label");
        }
        return new CatalogRoute(
                new Route(start, goal, label.directions, label.steps, label.fuel), spotArrivals);
    }

    private static String directionKey(List<Direction> directions) {
        StringBuilder value = new StringBuilder(directions.size());
        for (Direction direction : directions) {
            value.append((char) ('0' + direction.code()));
        }
        return value.toString();
    }

    private record Label(Position position, int steps, int fuel, List<Direction> directions) {
        private static final Comparator<Label> ORDER = Comparator
                .comparingInt(Label::steps)
                .thenComparingInt(Label::fuel)
                .thenComparingInt(label -> label.position.value())
                .thenComparing(label -> directionKey(label.directions));

        private Label {
            directions = List.copyOf(directions);
        }
    }
}
