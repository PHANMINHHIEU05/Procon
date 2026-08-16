package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.engine.DayState;

/** Optimistic movement-step lower bounds for all currently observed opponents. */
public final class OpponentWeightedArrivalLowerBound {

    public Map<Position, OptionalInt> lowerBounds(DayState state) {
        HexMap map = state.matchData().map();
        Set<Position> starts = new TreeSet<>(Comparator.comparingInt(Position::value));
        state.observedOthers().stream()
                .flatMap(group -> group.agents().stream())
                .map(ObservedOtherAgent::position)
                .filter(map::contains)
                .forEach(starts::add);

        Map<Position, Integer> minimums = new HashMap<>();
        for (Position start : starts) {
            for (Map.Entry<Position, Integer> entry : shortestPaths(map, start).entrySet()) {
                minimums.merge(entry.getKey(), entry.getValue(), Math::min);
            }
        }

        Map<Position, OptionalInt> result = new HashMap<>();
        for (var spot : state.matchData().udonSpots()) {
            Integer bound = minimums.get(spot.position());
            result.put(spot.position(), bound == null ? OptionalInt.empty() : OptionalInt.of(bound));
        }
        return Map.copyOf(result);
    }

    public OptionalInt shortestTravelSteps(HexMap map, Position start, Position target) {
        if (!map.contains(start) || !map.contains(target)) {
            return OptionalInt.empty();
        }
        Integer result = shortestPaths(map, start).get(target);
        return result == null ? OptionalInt.empty() : OptionalInt.of(result);
    }

    /** Immutable all-destination optimistic distances for one cached forecast source. */
    Map<Position, Integer> shortestTravelStepsFrom(HexMap map, Position start) {
        if (!map.contains(start)) {
            return Map.of();
        }
        return Map.copyOf(shortestPaths(map, start));
    }

    private Map<Position, Integer> shortestPaths(HexMap map, Position start) {
        Map<Position, Integer> distances = new HashMap<>();
        PriorityQueue<Node> frontier = new PriorityQueue<>(
                Comparator.comparingInt(Node::distance).thenComparingInt(node -> node.position().value()));
        distances.put(start, 0);
        frontier.add(new Node(start, 0));

        while (!frontier.isEmpty()) {
            Node current = frontier.poll();
            if (current.distance() != distances.getOrDefault(current.position(), Integer.MAX_VALUE)) {
                continue;
            }
            if (!map.isTraversable(current.position())) {
                continue;
            }
            int edgeCost = optimisticSourceCost(map.terrainAt(current.position()));
            for (Position neighbor : map.neighbors(current.position())) {
                if (!map.isTraversable(neighbor)) {
                    continue;
                }
                int candidate = Math.addExact(current.distance(), edgeCost);
                if (candidate < distances.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                    distances.put(neighbor, candidate);
                    frontier.add(new Node(neighbor, candidate));
                }
            }
        }
        return distances;
    }

    private int optimisticSourceCost(Terrain terrain) {
        return switch (terrain) {
            case ROAD -> 1;
            case PLAIN -> 2;
            case MOUNTAIN -> 3;
            case POND -> Integer.MAX_VALUE;
        };
    }

    private record Node(Position position, int distance) {
    }
}