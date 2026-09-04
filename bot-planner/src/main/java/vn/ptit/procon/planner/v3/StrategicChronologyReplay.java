package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.engine.DayState;

/** Settles all cached arrivals in simulator order, independent of expansion order. */
public final class StrategicChronologyReplay {
    private int replays;
    private int eventsProcessed;
    private int supportEventsProcessed;

    public ChronologyResult replay(DayState state, Map<AgentId, List<CachedTrajectoryEffect>> routes) {
        Objects.requireNonNull(state); Objects.requireNonNull(routes);
        replays++;
        Map<Position, Integer> stock = new LinkedHashMap<>(state.spotStock());
        Map<AgentId, Set<Position>> visited = new LinkedHashMap<>();
        Map<AgentId, Integer> portions = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            visited.put(agent.id(), new LinkedHashSet<>());
            portions.put(agent.id(), 0);
        }
        List<Arrival> arrivals = new ArrayList<>();
        for (AgentState agent : state.agents()) {
            if (agent.kind() == AgentKind.PATROL) arrivals.add(new Arrival(0, agent.id(), agent.position()));
            int offset = 0;
            for (CachedTrajectoryEffect effect : routes.getOrDefault(agent.id(), List.of())) {
                for (CachedTrajectoryEffect.Encounter encounter : effect.encounters()) {
                    arrivals.add(new Arrival(offset + encounter.relativeStep(), agent.id(), encounter.position()));
                }
                offset += effect.stepsUsed();
            }
        }
        arrivals.sort(Comparator.comparingInt(Arrival::step).thenComparingInt(a -> a.agentId().value()));
        eventsProcessed += arrivals.size();
        Set<BrandId> brands = new LinkedHashSet<>();
        List<Claim> claims = new ArrayList<>();
        for (Arrival arrival : arrivals) {
            if (!visited.getOrDefault(arrival.agentId(), Set.of()).add(arrival.position())) continue;
            var spot = state.matchData().udonSpots().stream()
                    .filter(candidate -> candidate.position().equals(arrival.position())).findFirst().orElse(null);
            if (spot == null || stock.getOrDefault(arrival.position(), 0) <= 0) continue;
            int remaining = stock.get(arrival.position()) - 1;
            stock.put(arrival.position(), remaining);
            portions.computeIfPresent(arrival.agentId(), (id, count) -> count + 1);
            brands.add(spot.brand());
            claims.add(new Claim(arrival.step(), arrival.agentId(), arrival.position(), spot.brand(), remaining));
        }
        Map<AgentId, Position> finalPositions = new LinkedHashMap<>();
        Map<AgentId, Integer> finalElapsed = new LinkedHashMap<>();
        Map<AgentId, Integer> finalFuel = new LinkedHashMap<>();
        Set<Position> stationaryRefuelPositions = state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.REFUEL
                        && routes.getOrDefault(agent.id(), List.of()).isEmpty())
                .map(AgentState::position).collect(Collectors.toSet());
        for (AgentState agent : state.agents()) {
            Position position = agent.position(); int elapsed = 0;
            int currentFuel = agent.fuel() instanceof FiniteFuel finite ? finite.amount() : Integer.MAX_VALUE;
            if (agent.kind() == AgentKind.PATROL && routes.getOrDefault(agent.id(), List.of()).isEmpty()
                    && stationaryRefuelPositions.contains(agent.position()) && state.stepBudget() > 0) {
                currentFuel = state.matchData().patrolFuelCapacity().value();
            }
            for (CachedTrajectoryEffect effect : routes.getOrDefault(agent.id(), List.of())) {
                for (CachedTrajectoryEffect.Segment segment : effect.segments()) {
                    if (currentFuel != Integer.MAX_VALUE) {
                        currentFuel -= segment.fuelAfter() - segment.fuelBefore();
                        if (stationaryRefuelPositions.contains(segment.destination())) {
                            currentFuel = state.matchData().patrolFuelCapacity().value();
                        }
                    }
                }
                position = effect.goal(); elapsed += effect.stepsUsed();
            }
            finalPositions.put(agent.id(), position); finalElapsed.put(agent.id(), elapsed);
            finalFuel.put(agent.id(), agent.fuel() instanceof FiniteFuel
                    ? Math.max(0, currentFuel) : Integer.MAX_VALUE);
        }
        String fingerprint = claims.stream().map(c -> c.step() + ":" + c.agentId().value() + ":" + c.position().value())
                .collect(Collectors.joining(","));
        return new ChronologyResult(stock, portions, brands, visited, claims, finalPositions, finalElapsed, finalFuel, fingerprint);
    }

    public int replays() { return replays; }
    public int eventsProcessed() { return eventsProcessed; }
    public int supportEventsProcessed() { return supportEventsProcessed; }

    /**
     * The SAME global chronology, now also fed by the authoritative REFUEL trajectory of the selected
     * support root.
     *
     * <p>PART 6: this is not a second simulator. It settles one step-ordered timeline for every agent and
     * applies the frozen simulator rules in the frozen order — a move consumes fuel when it starts, spots
     * are collected on arrival, and refuelling happens at the end of the step for the two actors that
     * genuinely occupy the cell. Expansion order therefore cannot change the result.
     *
     * <p>PART 15: the schedule handed in may be optimistic. This replay is the authority that rejects it:
     * a move that starts without enough fuel makes the result {@link ChronologyResult#fuelFeasible()}
     * false instead of silently clamping to zero.
     */
    public ChronologyResult replaySupported(DayState state, Map<AgentId, List<ScheduledLeg>> schedules,
            CachedSupportTrajectory support) {
        Objects.requireNonNull(state); Objects.requireNonNull(schedules); Objects.requireNonNull(support);
        replays++;
        int budget = state.stepBudget();
        int capacity = state.matchData().patrolFuelCapacity().value();
        Map<AgentId, Walk> walks = new LinkedHashMap<>();
        List<AgentState> patrols = state.agents().stream().filter(agent -> agent.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(agent -> agent.id().value())).toList();
        for (AgentState patrol : patrols) {
            Walk walk = Walk.of(patrol.position(), schedules.getOrDefault(patrol.id(), List.of()), budget);
            if (walk == null) return infeasible(state, "SCHEDULE_EXCEEDS_DAY_BUDGET");
            walks.put(patrol.id(), walk);
        }
        Set<Position> stationaryRefuelPositions = state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.REFUEL)
                .filter(agent -> !(support.present() && agent.id().equals(support.refuelId())))
                .filter(agent -> schedules.getOrDefault(agent.id(), List.of()).isEmpty())
                .map(AgentState::position).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<AgentId, Integer> fuel = new LinkedHashMap<>();
        patrols.forEach(patrol -> fuel.put(patrol.id(),
                patrol.fuel() instanceof FiniteFuel finite ? finite.amount() : Integer.MAX_VALUE));
        // PART 21: the state a partial plan reaches is the state at the END of its committed prefix.
        // Refuelling that only happens during the trailing all-day WAIT is not a committed decision,
        // so it must not be visible to the scheduler as fuel already in the tank.
        Map<AgentId, Integer> fuelAtElapsed = new LinkedHashMap<>();
        patrols.stream().filter(patrol -> walks.get(patrol.id()).elapsed == 0)
                .forEach(patrol -> fuelAtElapsed.put(patrol.id(), fuel.get(patrol.id())));
        List<RefuelEvent> refuelEvents = new ArrayList<>();
        for (int step = 1; step <= budget; step++) {
            for (AgentState patrol : patrols) {
                int cost = walks.get(patrol.id()).startingMoveFuel(step);
                if (cost <= 0) continue;
                int available = fuel.get(patrol.id());
                if (available < cost) {
                    return infeasible(state, "NEGATIVE_FUEL_AT_STEP_" + step + "_PATROL_" + patrol.id().value());
                }
                fuel.put(patrol.id(), available - cost);
            }
            for (AgentState patrol : patrols) {
                Walk walk = walks.get(patrol.id());
                if (!walk.genuinelyOccupies(step)) continue;
                Position cell = walk.position(step);
                boolean stationary = stationaryRefuelPositions.contains(cell);
                boolean mobile = support.canRefuel(step, cell);
                if (!stationary && !mobile) continue;
                int before = fuel.get(patrol.id());
                if (before >= capacity) continue;
                supportEventsProcessed++;
                refuelEvents.add(new RefuelEvent(step, patrol.id(), cell, before, capacity, stationary
                        ? RefuelSource.STATIONARY_REFUEL
                        : walk.arriving(step) ? RefuelSource.ARRIVAL_REFILL
                                : RefuelSource.INCIDENTAL_SPATIAL_REFILL));
                fuel.put(patrol.id(), capacity);
            }
            for (AgentState patrol : patrols) {
                if (walks.get(patrol.id()).elapsed == step) fuelAtElapsed.put(patrol.id(), fuel.get(patrol.id()));
            }
        }
        return settle(state, support, walks, fuel, fuelAtElapsed, refuelEvents, budget);
    }

    /** Settles collections from the same step-ordered timeline the fuel loop above walked. */
    private ChronologyResult settle(DayState state, CachedSupportTrajectory support, Map<AgentId, Walk> walks,
            Map<AgentId, Integer> fuel, Map<AgentId, Integer> fuelAtElapsed, List<RefuelEvent> refuelEvents,
            int budget) {
        Map<Position, Integer> stock = new LinkedHashMap<>(state.spotStock());
        Map<AgentId, Set<Position>> visited = new LinkedHashMap<>();
        Map<AgentId, Integer> portions = new LinkedHashMap<>();
        state.agents().forEach(agent -> { visited.put(agent.id(), new LinkedHashSet<>()); portions.put(agent.id(), 0); });
        List<Arrival> arrivals = new ArrayList<>();
        walks.forEach((id, walk) -> {
            arrivals.add(new Arrival(0, id, walk.start));
            for (int step = 1; step <= budget; step++) if (walk.arriving(step)) arrivals.add(new Arrival(step, id, walk.position(step)));
        });
        arrivals.sort(Comparator.comparingInt(Arrival::step).thenComparingInt(value -> value.agentId().value()));
        eventsProcessed += arrivals.size();
        Set<BrandId> brands = new LinkedHashSet<>();
        List<Claim> claims = new ArrayList<>();
        for (Arrival arrival : arrivals) {
            if (!visited.getOrDefault(arrival.agentId(), Set.of()).add(arrival.position())) continue;
            var spot = state.matchData().udonSpots().stream()
                    .filter(candidate -> candidate.position().equals(arrival.position())).findFirst().orElse(null);
            if (spot == null || stock.getOrDefault(arrival.position(), 0) <= 0) continue;
            int remaining = stock.get(arrival.position()) - 1;
            stock.put(arrival.position(), remaining);
            portions.computeIfPresent(arrival.agentId(), (id, count) -> count + 1);
            brands.add(spot.brand());
            claims.add(new Claim(arrival.step(), arrival.agentId(), arrival.position(), spot.brand(), remaining));
        }
        Map<AgentId, Position> finalPositions = new LinkedHashMap<>();
        Map<AgentId, Integer> finalElapsed = new LinkedHashMap<>();
        Map<AgentId, Integer> finalFuel = new LinkedHashMap<>();
        Map<AgentId, Integer> atElapsed = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            Walk walk = walks.get(agent.id());
            if (walk != null) {
                finalPositions.put(agent.id(), walk.finalPosition);
                finalElapsed.put(agent.id(), walk.elapsed);
                finalFuel.put(agent.id(), fuel.get(agent.id()));
                atElapsed.put(agent.id(), fuelAtElapsed.getOrDefault(agent.id(), fuel.get(agent.id())));
                continue;
            }
            boolean mobile = support.present() && agent.id().equals(support.refuelId());
            finalPositions.put(agent.id(), mobile ? support.timeline().getLast().position() : agent.position());
            finalElapsed.put(agent.id(), mobile ? support.actionSteps() : 0);
            int initial = agent.fuel() instanceof FiniteFuel finite ? finite.amount() : Integer.MAX_VALUE;
            finalFuel.put(agent.id(), initial);
            atElapsed.put(agent.id(), initial);
        }
        String fingerprint = claims.stream().map(c -> c.step() + ":" + c.agentId().value() + ":" + c.position().value())
                .collect(Collectors.joining(","));
        return new ChronologyResult(stock, portions, brands, visited, claims, finalPositions, finalElapsed,
                finalFuel, fingerprint, List.copyOf(refuelEvents), true, "NONE", atElapsed);
    }

    private static ChronologyResult infeasible(DayState state, String reason) {
        Map<AgentId, Position> positions = new LinkedHashMap<>();
        Map<AgentId, Integer> elapsed = new LinkedHashMap<>();
        Map<AgentId, Integer> fuel = new LinkedHashMap<>();
        state.agents().forEach(agent -> {
            positions.put(agent.id(), agent.position());
            elapsed.put(agent.id(), 0);
            fuel.put(agent.id(), agent.fuel() instanceof FiniteFuel finite ? finite.amount() : Integer.MAX_VALUE);
        });
        return new ChronologyResult(state.spotStock(), Map.of(), Set.of(), Map.of(), List.of(), positions,
                elapsed, fuel, "", List.of(), false, reason, fuel);
    }

    /**
     * The step-indexed motion of one PATROL under a committed schedule.
     *
     * <p>It mirrors the frozen simulator exactly: a move of cost {@code c} spends its first {@code c-1}
     * steps retaining the source cell without genuinely occupying it, then arrives on step {@code c}.
     */
    private static final class Walk {
        private final Position start;
        private final Position[] positions;
        private final byte[] motion;
        private final int[] startingMoveFuel;
        private final Position finalPosition;
        private final int elapsed;
        private static final byte MOVING = 0, ARRIVING = 1, WAITING = 2;

        private Walk(Position start, Position[] positions, byte[] motion, int[] startingMoveFuel,
                Position finalPosition, int elapsed) {
            this.start = start; this.positions = positions; this.motion = motion;
            this.startingMoveFuel = startingMoveFuel; this.finalPosition = finalPosition; this.elapsed = elapsed;
        }

        static Walk of(Position start, List<ScheduledLeg> legs, int budget) {
            Position[] positions = new Position[budget + 2];
            byte[] motion = new byte[budget + 2];
            int[] startingFuel = new int[budget + 2];
            Position cursor = start;
            int step = 0;
            for (ScheduledLeg leg : legs) {
                for (int index = 0; index < leg.leadInWaitSteps(); index++) {
                    if (++step > budget) return null;
                    positions[step] = cursor; motion[step] = WAITING;
                }
                for (CachedTrajectoryEffect.Segment segment : leg.effect().segments()) {
                    int cost = segment.endOffset() - segment.startOffset();
                    if (step + cost > budget) return null;
                    startingFuel[step + 1] = segment.fuelAfter() - segment.fuelBefore();
                    for (int index = 1; index < cost; index++) {
                        step++; positions[step] = segment.source(); motion[step] = MOVING;
                    }
                    step++; positions[step] = segment.destination(); motion[step] = ARRIVING;
                    cursor = segment.destination();
                }
            }
            int consumed = step;
            while (step < budget) { step++; positions[step] = cursor; motion[step] = WAITING; }
            return new Walk(start, positions, motion, startingFuel, cursor, consumed);
        }

        int startingMoveFuel(int step) { return step < startingMoveFuel.length ? startingMoveFuel[step] : 0; }
        Position position(int step) { return positions[step]; }
        boolean arriving(int step) { return positions[step] != null && motion[step] == ARRIVING; }
        boolean genuinelyOccupies(int step) { return positions[step] != null && motion[step] != MOVING; }
    }

    public record Arrival(int step, AgentId agentId, Position position) {
        public Arrival { Objects.requireNonNull(agentId); Objects.requireNonNull(position); }
    }
    public record Claim(int step, AgentId agentId, Position position, BrandId brand, int remainingStock) { }

    /**
     * One committed strategic leg plus the waiting deliberately inserted before it.
     *
     * <p>PART 11/12: a PATROL is not required to start a leg at step 0. When a leg only becomes legal
     * after an authoritative support event, the schedule carries the exact lead-in wait that reaches it,
     * so the same chronology settles both the waiting and the movement.
     */
    public record ScheduledLeg(int leadInWaitSteps, CachedTrajectoryEffect effect) {
        public ScheduledLeg {
            Objects.requireNonNull(effect, "Scheduled leg effect must not be null");
            if (leadInWaitSteps < 0) throw new IllegalArgumentException("Lead-in wait must be non-negative");
        }
        public static ScheduledLeg immediate(CachedTrajectoryEffect effect) { return new ScheduledLeg(0, effect); }
        public int totalSteps() { return leadInWaitSteps + effect.stepsUsed(); }
    }

    /** Where a settled refuelling came from. The chronology classifies it by observable geometry only. */
    public enum RefuelSource { ARRIVAL_REFILL, INCIDENTAL_SPATIAL_REFILL, STATIONARY_REFUEL }

    /** One refuelling settled by the global chronology, in simulator order. */
    public record RefuelEvent(int step, AgentId patrolId, Position position, int before, int after,
            RefuelSource source) {
        public RefuelEvent {
            Objects.requireNonNull(patrolId); Objects.requireNonNull(position); Objects.requireNonNull(source);
        }
    }

    public record ChronologyResult(Map<Position, Integer> remainingStock, Map<AgentId, Integer> portions,
            Set<BrandId> brands, Map<AgentId, Set<Position>> visited, List<Claim> claims,
            Map<AgentId, Position> finalPositions, Map<AgentId, Integer> finalElapsed,
            Map<AgentId, Integer> finalFuel, String fingerprint, List<RefuelEvent> refuelEvents,
            boolean fuelFeasible, String infeasibleReason, Map<AgentId, Integer> fuelAtElapsed) {
        public ChronologyResult(Map<Position, Integer> remainingStock, Map<AgentId, Integer> portions,
                Set<BrandId> brands, Map<AgentId, Set<Position>> visited, List<Claim> claims,
                Map<AgentId, Position> finalPositions, Map<AgentId, Integer> finalElapsed,
                Map<AgentId, Integer> finalFuel, String fingerprint) {
            this(remainingStock, portions, brands, visited, claims, finalPositions, finalElapsed, finalFuel,
                    fingerprint, List.of(), true, "NONE", finalFuel);
        }
        public ChronologyResult {
            remainingStock = Map.copyOf(remainingStock); portions = Map.copyOf(portions); brands = Set.copyOf(brands);
            visited = visited.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
            claims = List.copyOf(claims); finalPositions = Map.copyOf(finalPositions); finalElapsed = Map.copyOf(finalElapsed);
            finalFuel = Map.copyOf(finalFuel); fingerprint = Objects.requireNonNull(fingerprint);
            refuelEvents = List.copyOf(Objects.requireNonNull(refuelEvents));
            infeasibleReason = Objects.requireNonNull(infeasibleReason);
            fuelAtElapsed = Map.copyOf(Objects.requireNonNull(fuelAtElapsed));
        }
        public int collections() { return portions.values().stream().mapToInt(Integer::intValue).sum(); }
        /** PART 26: refuelling the R3 metadata predicted, versus refuelling the geometry merely caused. */
        public List<RefuelEvent> refuelEvents(RefuelSource source) {
            return refuelEvents.stream().filter(event -> event.source() == source).toList();
        }
    }
}
