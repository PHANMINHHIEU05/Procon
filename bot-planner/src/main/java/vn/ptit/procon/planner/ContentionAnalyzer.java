package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.rules.MovementRules;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.domain.udon.UdonSpot;

/** Pure EVEN-R geometric analysis over the authoritative current-day snapshot. */
public final class ContentionAnalyzer {

    public ContentionMetrics analyze(DayState state, UdonSpot spot) {
        Objects.requireNonNull(spot, "Udon spot must not be null");
        return analyze(state, spot.position());
    }

    public ContentionMetrics analyze(DayState state, Position target) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(target, "Target position must not be null");
        HexMap map = state.matchData().map();
        if (!map.contains(target)) {
            throw new IllegalArgumentException("Target position is outside the map: " + target);
        }

        OptionalInt ourNearest = state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL)
                .mapToInt(agent -> hexDistance(map, agent.position(), target))
                .min();
        List<Integer> otherDistances = validOtherAgents(state, map).stream()
                .mapToInt(agent -> hexDistance(map, agent.position(), target))
                .boxed()
                .toList();
        OptionalInt otherNearest = otherDistances.stream().mapToInt(Integer::intValue).min();
        int within1 = (int) otherDistances.stream().filter(distance -> distance <= 1).count();
        int within2 = (int) otherDistances.stream().filter(distance -> distance <= 2).count();
        OptionalInt advantage = ourNearest.isPresent() && otherNearest.isPresent()
                ? OptionalInt.of(otherNearest.getAsInt() - ourNearest.getAsInt())
                : OptionalInt.empty();
        ContentionClassification classification = classify(ourNearest, otherNearest);
        return new ContentionMetrics(
                target, ourNearest, otherNearest, within1, within2, advantage, classification);
    }

    public int hexDistance(HexMap map, Position first, Position second) {
        Objects.requireNonNull(map, "Hex map must not be null");
        if (!map.contains(first) || !map.contains(second)) {
            throw new IllegalArgumentException("Hex distance positions must be inside the map");
        }
        Cube a = cube(map, first);
        Cube b = cube(map, second);
        return Math.max(
                Math.abs(a.x - b.x),
                Math.max(Math.abs(a.y - b.y), Math.abs(a.z - b.z)));
    }

    public SpotContentionSummary summarizeSpots(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        return summarizeSpots(state, position -> analyze(state, position));
    }

    SpotContentionSummary summarizeSpots(
            DayState state, Function<Position, ContentionMetrics> metricsForPosition) {
        int safe = 0;
        int tied = 0;
        int contested = 0;
        int unobserved = 0;
        for (UdonSpot spot : state.matchData().udonSpots()) {
            switch (metricsForPosition.apply(spot.position()).classification()) {
                case SAFE -> safe++;
                case TIED -> tied++;
                case CONTESTED -> contested++;
                case UNOBSERVED -> unobserved++;
            }
        }
        return new SpotContentionSummary(
                state.matchData().udonSpots().size(), safe, tied, contested, unobserved);
    }

    public RouteContentionMetrics analyzeRoute(
            DayState state,
            Route route,
            Map<Position, Integer> branchStock,
            Set<Position> alreadyVisited,
            Map<Position, UdonSpot> spotsByPosition) {
        return analyzeRoute(
                state, route, branchStock, alreadyVisited, spotsByPosition,
                position -> analyze(state, position));
    }

    RouteContentionMetrics analyzeRoute(
            DayState state,
            Route route,
            Map<Position, Integer> branchStock,
            Set<Position> alreadyVisited,
            Map<Position, UdonSpot> spotsByPosition,
            Function<Position, ContentionMetrics> metricsForPosition) {
        Objects.requireNonNull(route, "Route must not be null");
        Objects.requireNonNull(branchStock, "Branch stock must not be null");
        Objects.requireNonNull(alreadyVisited, "Visited positions must not be null");
        Objects.requireNonNull(spotsByPosition, "Udon spots must not be null");
        Objects.requireNonNull(metricsForPosition, "Contention metric provider must not be null");
        Set<Position> visited = new java.util.LinkedHashSet<>(alreadyVisited);
        Position cursor = route.start();
        int total = 0;
        int safe = 0;
        int tied = 0;
        int contested = 0;
        int stronglyContested = 0;
        for (vn.ptit.procon.domain.map.Direction direction : route.directions()) {
            cursor = state.matchData().map().neighbor(cursor, direction).orElseThrow();
            if (!spotsByPosition.containsKey(cursor)
                    || !visited.add(cursor)
                    || branchStock.getOrDefault(cursor, 0) <= 0) {
                continue;
            }
            total++;
            ContentionMetrics metrics = metricsForPosition.apply(cursor);
            switch (metrics.classification()) {
                case SAFE, UNOBSERVED -> safe++;
                case TIED -> tied++;
                case CONTESTED -> {
                    contested++;
                    if (metrics.distanceAdvantage().isPresent()
                            && metrics.distanceAdvantage().getAsInt() <= -2) {
                        stronglyContested++;
                    }
                }
            }
        }
        return new RouteContentionMetrics(total, safe, tied, contested, stronglyContested);
    }

    public Map<Position, OptionalInt> opponentLowerBounds(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        HexMap map = state.matchData().map();
        List<ObservedOtherAgent> others = validOtherAgents(state, map);
        Map<Position, OptionalInt> bounds = new java.util.LinkedHashMap<>();
        for (UdonSpot spot : state.matchData().udonSpots()) {
            OptionalInt minOpponentDist = others.stream()
                    .mapToInt(agent -> hexDistance(map, agent.position(), spot.position()))
                    .min();
            bounds.put(spot.position(), minOpponentDist);
        }
        return Map.copyOf(bounds);
    }

    public ArrivalContentionMetrics analyzeArrival(
            Position target, int ourArrivalStep, OptionalInt opponentLowerBound) {
        Objects.requireNonNull(target, "Target position must not be null");
        if (ourArrivalStep < 0) {
            throw new IllegalArgumentException("Our arrival step must be non-negative");
        }
        Objects.requireNonNull(opponentLowerBound, "Opponent lower bound must not be null");

        if (opponentLowerBound.isEmpty()) {
            return new ArrivalContentionMetrics(
                    target,
                    ourArrivalStep,
                    OptionalInt.empty(),
                    OptionalInt.empty(),
                    ArrivalContentionClassification.UNOBSERVED);
        }

        int lowerBound = opponentLowerBound.getAsInt();
        int advantage = lowerBound - ourArrivalStep;
        ArrivalContentionClassification classification = advantage > 0
                ? ArrivalContentionClassification.ARRIVAL_SAFE
                : (advantage == 0
                        ? ArrivalContentionClassification.ARRIVAL_TIED
                        : ArrivalContentionClassification.ARRIVAL_AT_RISK);

        return new ArrivalContentionMetrics(
                target,
                ourArrivalStep,
                OptionalInt.of(lowerBound),
                OptionalInt.of(advantage),
                classification);
    }

    public RouteArrivalContentionMetrics analyzeRouteArrival(
            DayState state,
            Route route,
            Map<Position, OptionalInt> opponentLowerBounds,
            int initialArrivalStep) {
        Map<Position, UdonSpot> spotsByPosition = new LinkedHashMap<>();
        for (UdonSpot spot : state.matchData().udonSpots()) {
            spotsByPosition.put(spot.position(), spot);
        }
        return analyzeRouteArrival(
                state,
                route,
                initialArrivalStep,
                state.spotStock(),
                Set.of(),
                spotsByPosition,
                target -> analyze(state, target),
                target -> opponentLowerBounds.getOrDefault(target, OptionalInt.empty()));
    }

    public RouteArrivalContentionMetrics analyzeRouteArrival(
            DayState state,
            Route route,
            int initialArrivalStep,
            Map<Position, Integer> branchStock,
            Set<Position> alreadyVisited,
            Map<Position, UdonSpot> spotsByPosition,
            Function<Position, ContentionMetrics> staticMetricsProvider,
            Function<Position, OptionalInt> opponentLowerBoundProvider) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(route, "Route must not be null");
        if (initialArrivalStep < 0) {
            throw new IllegalArgumentException("Initial arrival step must be non-negative");
        }
        Objects.requireNonNull(branchStock, "Branch stock must not be null");
        Objects.requireNonNull(alreadyVisited, "Visited positions must not be null");
        Objects.requireNonNull(spotsByPosition, "Udon spots must not be null");
        Objects.requireNonNull(staticMetricsProvider, "Static contention metric provider must not be null");
        Objects.requireNonNull(opponentLowerBoundProvider, "Opponent lower bound provider must not be null");

        Set<Position> visited = new java.util.LinkedHashSet<>(alreadyVisited);
        Position cursor = route.start();
        int currentStep = initialArrivalStep;
        int total = 0;
        int arrivalSafe = 0;
        int arrivalTied = 0;
        int arrivalAtRisk = 0;
        int unobserved = 0;
        int staticSafe = 0;
        int staticTied = 0;
        int staticContested = 0;
        int stronglyStaticContested = 0;

        HexMap map = state.matchData().map();
        for (vn.ptit.procon.domain.map.Direction direction : route.directions()) {
            TrafficStatus traffic = map.terrainAt(cursor) == vn.ptit.procon.domain.map.Terrain.ROAD
                    ? state.roadTraffic().get(cursor)
                    : null;
            MoveCost cost = MovementRules.costFromSource(map, cursor, traffic).orElseThrow();
            currentStep += cost.stepCost();
            cursor = map.neighbor(cursor, direction).orElseThrow();

            if (!spotsByPosition.containsKey(cursor)
                    || !visited.add(cursor)
                    || branchStock.getOrDefault(cursor, 0) <= 0) {
                continue;
            }

            total++;
            ContentionMetrics staticMetrics = staticMetricsProvider.apply(cursor);
            switch (staticMetrics.classification()) {
                case SAFE, UNOBSERVED -> staticSafe++;
                case TIED -> staticTied++;
                case CONTESTED -> {
                    staticContested++;
                    if (staticMetrics.distanceAdvantage().isPresent()
                            && staticMetrics.distanceAdvantage().getAsInt() <= -2) {
                        stronglyStaticContested++;
                    }
                }
            }

            OptionalInt oppBound = opponentLowerBoundProvider.apply(cursor);
            ArrivalContentionMetrics arrivalMetrics = analyzeArrival(cursor, currentStep, oppBound);
            switch (arrivalMetrics.classification()) {
                case ARRIVAL_SAFE -> arrivalSafe++;
                case ARRIVAL_TIED -> arrivalTied++;
                case ARRIVAL_AT_RISK -> arrivalAtRisk++;
                case UNOBSERVED -> {
                    arrivalSafe++;
                    unobserved++;
                }
            }
        }

        return new RouteArrivalContentionMetrics(
                total,
                arrivalSafe,
                arrivalTied,
                arrivalAtRisk,
                unobserved,
                staticSafe,
                staticTied,
                staticContested,
                stronglyStaticContested);
    }

    private List<ObservedOtherAgent> validOtherAgents(DayState state, HexMap map) {
        List<ObservedOtherAgent> result = new ArrayList<>();
        state.observedOthers().forEach(group -> group.agents().stream()
                .filter(agent -> map.contains(agent.position()))
                .forEach(result::add));
        return List.copyOf(result);
    }

    private ContentionClassification classify(OptionalInt ours, OptionalInt others) {
        if (others.isEmpty()) {
            return ContentionClassification.UNOBSERVED;
        }
        if (ours.isEmpty()) {
            return ContentionClassification.CONTESTED;
        }
        int comparison = Integer.compare(ours.getAsInt(), others.getAsInt());
        if (comparison < 0) {
            return ContentionClassification.SAFE;
        }
        if (comparison == 0) {
            return ContentionClassification.TIED;
        }
        return ContentionClassification.CONTESTED;
    }

    private Cube cube(HexMap map, Position position) {
        int row = map.rowOf(position);
        int column = map.columnOf(position);
        int x = column - (row + (row & 1)) / 2;
        int z = row;
        return new Cube(x, -x - z, z);
    }

    private record Cube(int x, int y, int z) {
    }
}