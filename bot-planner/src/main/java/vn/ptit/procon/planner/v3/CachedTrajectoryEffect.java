package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.rules.MovementRules;

/**
 * A route's immutable movement effect.  It is deliberately built from an
 * already selected Route; construction never performs path finding.
 */
public record CachedTrajectoryEffect(AgentId agentId, Position start, Position goal,
        List<Position> traversedPositions, List<Segment> segments, List<Encounter> encounters,
        int stepsUsed, int fuelUsed) {
    public CachedTrajectoryEffect {
        Objects.requireNonNull(agentId); Objects.requireNonNull(start); Objects.requireNonNull(goal);
        traversedPositions = List.copyOf(traversedPositions);
        segments = List.copyOf(segments);
        encounters = List.copyOf(encounters);
        if (stepsUsed < 0 || fuelUsed < 0) throw new IllegalArgumentException("Negative trajectory resources");
    }

    public static CachedTrajectoryEffect from(DayState state, AgentId agentId, Route route) {
        Objects.requireNonNull(state); Objects.requireNonNull(route);
        HexMap map = state.matchData().map();
        Position cursor = route.start();
        int elapsed = 0;
        int fuel = 0;
        List<Position> positions = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        List<Encounter> encounters = new ArrayList<>();
        positions.add(cursor);
        for (Direction direction : route.directions()) {
            Position source = cursor;
            Position destination = map.neighbor(source, direction)
                    .orElseThrow(() -> new IllegalArgumentException("Cached route leaves map: " + source));
            TrafficStatus traffic = map.terrainAt(source) == vn.ptit.procon.domain.map.Terrain.ROAD
                    ? state.roadTraffic().get(source) : null;
            MoveCost cost = MovementRules.costFromSource(map, source, traffic)
                    .orElseThrow(() -> new IllegalArgumentException("Cached route starts at impassable cell: " + source));
            int nextElapsed = Math.addExact(elapsed, cost.stepCost());
            int nextFuel = Math.addExact(fuel, cost.patrolFuelCost());
            segments.add(new Segment(source, destination, elapsed, nextElapsed, fuel, nextFuel));
            cursor = destination;
            elapsed = nextElapsed;
            fuel = nextFuel;
            positions.add(cursor);
            encounters.add(new Encounter(cursor, elapsed));
        }
        if (!cursor.equals(route.goal()) || elapsed != route.stepsUsed() || fuel != route.fuelUsed()) {
            throw new IllegalArgumentException("Route metadata does not match authoritative movement costs");
        }
        return new CachedTrajectoryEffect(agentId, route.start(), route.goal(), positions, segments,
                encounters, elapsed, fuel);
    }

    public record Segment(Position source, Position destination, int startOffset, int endOffset,
            int fuelBefore, int fuelAfter) { }

    /** A possible spot arrival; stock and brand settlement remain dynamic. */
    public record Encounter(Position position, int relativeStep) {
        public Encounter {
            Objects.requireNonNull(position);
            if (relativeStep <= 0) throw new IllegalArgumentException("Trajectory arrival must be positive");
        }
    }
}
