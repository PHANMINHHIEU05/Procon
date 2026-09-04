package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.rules.MovementRules;

/**
 * The step-indexed occupancy of ONE mobile REFUEL actor, derived from its authoritative action
 * sequence rather than from its service metadata.
 *
 * <p>The full tanker route is the authority: this class walks the exported R3 REFUEL actions with the
 * frozen {@link MovementRules} costs and records, for every step of the day, where the tanker is and
 * whether it genuinely occupies that cell at the end of the step. That distinction is what makes the
 * frozen {@code DaySimulator} refuel rule reproducible without a second simulator:
 *
 * <ul>
 *   <li>{@link Motion#WAITING} and {@link Motion#ARRIVING} genuinely occupy the cell, so they can
 *       refuel a co-located PATROL that also genuinely occupies it;</li>
 *   <li>{@link Motion#MOVING} is a move still in progress. The tanker still retains its source cell
 *       for those steps but does NOT genuinely occupy it, so it cannot refuel there.</li>
 * </ul>
 *
 * <p>No pathfinding happens here: directions come from the existing R3 actions and costs come from the
 * map. The trailing WAIT padding that the full-day materialisation appends is part of the timeline,
 * because a tanker parked at its final cell really does keep refuelling there.
 */
public record CachedSupportTrajectory(AgentId refuelId, Position start, int stepBudget,
        List<Occupancy> timeline, List<AgentAction> actions, int actionSteps) {

    /** End-of-step occupancy semantics of the REFUEL actor, mirroring the frozen simulator. */
    public enum Motion { MOVING, ARRIVING, WAITING }

    /** Where the tanker is at the end of {@code step}, and whether it genuinely occupies that cell. */
    public record Occupancy(int step, Position position, Motion motion) {
        public Occupancy {
            Objects.requireNonNull(position, "Occupancy position must not be null");
            Objects.requireNonNull(motion, "Occupancy motion must not be null");
            if (step <= 0) throw new IllegalArgumentException("Occupancy step must be positive: " + step);
        }

        /** True when a co-located PATROL that also genuinely occupies the cell may be refuelled. */
        public boolean genuinelyOccupies() { return motion != Motion.MOVING; }
    }

    public CachedSupportTrajectory {
        Objects.requireNonNull(refuelId, "REFUEL id must not be null");
        Objects.requireNonNull(start, "REFUEL start must not be null");
        timeline = List.copyOf(Objects.requireNonNull(timeline, "Timeline must not be null"));
        actions = List.copyOf(Objects.requireNonNull(actions, "Actions must not be null"));
        if (stepBudget < 0 || actionSteps < 0) {
            throw new IllegalArgumentException("Support trajectory budgets must be non-negative");
        }
    }

    /** The NO_REFUEL trajectory: no mobile tanker, therefore no support event anywhere. */
    public static CachedSupportTrajectory none() {
        return new CachedSupportTrajectory(new AgentId(0), new Position(0), 0, List.of(), List.of(), 0);
    }

    public boolean present() { return !timeline.isEmpty(); }

    /**
     * Walks the authoritative REFUEL action sequence once and records every step of the day.
     *
     * @throws IllegalArgumentException when an action is not legal on the map, so a malformed export
     *         can never silently become an optimistic support promise
     */
    public static CachedSupportTrajectory from(DayState state, AgentId refuelId, Position start,
            List<AgentAction> actions) {
        Objects.requireNonNull(state, "Day state must not be null");
        List<Occupancy> timeline = new ArrayList<>();
        Position cursor = Objects.requireNonNull(start, "REFUEL start must not be null");
        int step = 0;
        for (AgentAction action : Objects.requireNonNull(actions, "REFUEL actions must not be null")) {
            if (action instanceof WaitAction wait) {
                for (int index = 0; index < wait.steps(); index++) {
                    timeline.add(new Occupancy(++step, cursor, Motion.WAITING));
                }
                continue;
            }
            MoveAction move = (MoveAction) action;
            Position source = cursor;
            Position destination = state.matchData().map().neighbor(source, move.direction())
                    .orElseThrow(() -> new IllegalArgumentException("REFUEL move leaves the map at " + source));
            MoveCost cost = MovementRules.costFromSource(state.matchData().map(), source, traffic(state, source))
                    .orElseThrow(() -> new IllegalArgumentException("REFUEL move is impassable at " + source));
            for (int index = 1; index < cost.stepCost(); index++) {
                timeline.add(new Occupancy(++step, source, Motion.MOVING));
            }
            timeline.add(new Occupancy(++step, destination, Motion.ARRIVING));
            cursor = destination;
        }
        int consumed = step;
        // Full-day materialisation pads the tanker with WAIT, and a parked tanker still refuels.
        while (step < state.stepBudget()) timeline.add(new Occupancy(++step, cursor, Motion.WAITING));
        return new CachedSupportTrajectory(refuelId, start, state.stepBudget(), List.copyOf(timeline),
                List.copyOf(actions), consumed);
    }

    private static vn.ptit.procon.domain.traffic.TrafficStatus traffic(DayState state, Position position) {
        return state.matchData().map().terrainAt(position) == Terrain.ROAD
                ? state.roadTraffic().get(position) : null;
    }

    /** The tanker cell at the end of {@code step}, or empty outside the recorded timeline. */
    public Occupancy at(int step) {
        if (step <= 0 || step > timeline.size()) return null;
        return timeline.get(step - 1);
    }

    /** True when a PATROL that genuinely occupies {@code position} at {@code step} can be refuelled. */
    public boolean canRefuel(int step, Position position) {
        Occupancy occupancy = at(step);
        return occupancy != null && occupancy.genuinelyOccupies() && occupancy.position().equals(position);
    }

    /** Every step at which this tanker could refuel a PATROL parked on {@code position}, in order. */
    public List<Integer> refuelOpportunitySteps(Position position) {
        List<Integer> steps = new ArrayList<>();
        for (Occupancy occupancy : timeline) {
            if (occupancy.genuinelyOccupies() && occupancy.position().equals(position)) steps.add(occupancy.step());
        }
        return List.copyOf(steps);
    }

    /** Distinct cells at which this tanker can ever refuel; diagnostics and audits only. */
    public Set<Position> refuelCapablePositions() {
        Set<Position> positions = new LinkedHashSet<>();
        timeline.stream().filter(Occupancy::genuinelyOccupies).forEach(value -> positions.add(value.position()));
        return Set.copyOf(positions);
    }

    /**
     * A canonical fingerprint of the support events that are still ahead of {@code fromStep}.
     *
     * <p>PART 19: two otherwise equal PATROL states under different future tanker behaviour are not
     * future-equivalent, so this string is part of the search state identity.
     */
    public String futureFingerprint(int fromStep) {
        if (timeline.isEmpty()) return "NONE";
        StringBuilder builder = new StringBuilder();
        for (Occupancy occupancy : timeline) {
            if (occupancy.step() <= fromStep || !occupancy.genuinelyOccupies()) continue;
            if (!builder.isEmpty()) builder.append(',');
            builder.append(occupancy.step()).append('@').append(occupancy.position().value());
        }
        return builder.isEmpty() ? "NONE" : builder.toString();
    }
}
