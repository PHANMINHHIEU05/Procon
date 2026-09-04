package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;

/**
 * Decides WHEN one cached strategic leg can legally be executed under a FIXED tanker trajectory.
 *
 * <p>PART 13: the input is a normalized PATROL state (where it stands, how many steps it has consumed,
 * how much fuel it holds at the end of its committed prefix), one {@link CachedTrajectoryEffect} taken
 * from the graph, and the authoritative {@link CachedSupportTrajectory} of the selected support root.
 * The output is the earliest legal execution window, or INFEASIBLE.
 *
 * <p>PART 12: no waiting is ever searched. The candidate start times are derived deterministically from
 * two sources and nothing else:
 * <ul>
 *   <li>the PATROL's own timeline — start immediately ({@code wait = 0});</li>
 *   <li>the authoritative support events — the earliest step the tanker can refuel the PATROL where it
 *       currently stands (PART 11/39), and, for every cell on the leg, the earliest start that makes the
 *       PATROL arrive exactly when the tanker can refuel it there (PART 14, mid-route rendezvous).</li>
 * </ul>
 * The candidate set therefore never exceeds {@code 2 + segments} entries, so this adds no branching to
 * the search: it only answers a question the search already asks once per edge.
 *
 * <p>PART 15: this scheduler is allowed to be optimistic about everything it cannot see (other patrols
 * consuming stock, for instance). It is NOT allowed to be optimistic about fuel: it applies the frozen
 * simulator order — a move pays its source-cell fuel when it starts, and refuelling lands at the end of
 * a step for an actor that genuinely occupies the cell. {@link StrategicChronologyReplay} re-derives the
 * same timeline for the whole team and remains the authority that rejects a negative-fuel replay.
 */
public final class SupportAwareTrajectoryScheduler {

    private final int capacity;
    private final int stepBudget;
    private final Set<Position> stationaryRefuelPositions;
    private final CachedSupportTrajectory support;
    private final Map<Position, int[]> refuelStepsByPosition = new HashMap<>();
    private int schedulesRequested;
    private int schedulesFeasible;
    private int waitsInserted;
    private int supportEventsUsed;
    private int candidatesEvaluated;

    private SupportAwareTrajectoryScheduler(int capacity, int stepBudget,
            Set<Position> stationaryRefuelPositions, CachedSupportTrajectory support) {
        this.capacity = capacity;
        this.stepBudget = stepBudget;
        this.stationaryRefuelPositions = stationaryRefuelPositions;
        this.support = support;
    }

    /**
     * Builds a scheduler for one support root.
     *
     * <p>The stationary refuel cells are exactly the ones {@link StrategicChronologyReplay} treats as
     * stationary: every REFUEL agent except the one the selected root puts in motion.
     */
    public static SupportAwareTrajectoryScheduler of(DayState state, CachedSupportTrajectory support) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(support, "Support trajectory must not be null");
        Set<Position> stationary = state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.REFUEL)
                .filter(agent -> !(support.present() && agent.id().equals(support.refuelId())))
                .map(AgentState::position)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new SupportAwareTrajectoryScheduler(state.matchData().patrolFuelCapacity().value(),
                state.stepBudget(), Set.copyOf(stationary), support);
    }

    public CachedSupportTrajectory support() { return support; }

    public int schedulesRequested() { return schedulesRequested; }

    public int schedulesFeasible() { return schedulesFeasible; }

    public int waitsInserted() { return waitsInserted; }

    public int supportEventsUsed() { return supportEventsUsed; }

    public int candidatesEvaluated() { return candidatesEvaluated; }

    /**
     * The earliest legal execution of {@code effect} for a PATROL standing at {@code position} with
     * {@code fuel} in the tank after {@code elapsed} consumed steps.
     *
     * <p>PART 9: a caller that only knows the geometry exists learns here, and only here, whether the
     * geometry is currently fuel/time feasible.
     */
    public SupportAwareSchedule schedule(Position position, int elapsed, int fuel,
            CachedTrajectoryEffect effect) {
        Objects.requireNonNull(position, "Patrol position must not be null");
        Objects.requireNonNull(effect, "Trajectory effect must not be null");
        schedulesRequested++;
        if (!effect.start().equals(position)) return SupportAwareSchedule.infeasible("LEG_START_MISMATCH");
        if (effect.segments().isEmpty()) return SupportAwareSchedule.infeasible("EMPTY_LEG");
        if (elapsed + effect.stepsUsed() > stepBudget) {
            return SupportAwareSchedule.infeasible("LEG_EXCEEDS_DAY_BUDGET");
        }
        String reason = "FUEL";
        for (int wait : candidateWaits(position, elapsed, effect)) {
            candidatesEvaluated++;
            SupportAwareSchedule attempt = attempt(position, elapsed, fuel, effect, wait);
            if (attempt.feasible()) {
                schedulesFeasible++;
                if (attempt.waited()) waitsInserted++;
                supportEventsUsed += attempt.supportEventsUsed();
                return attempt;
            }
            if (wait == 0) reason = attempt.reason();
        }
        return SupportAwareSchedule.infeasible(reason);
    }

    /** Convenience overload: schedule a leg with no support root at all (PART 8 semantics). */
    public boolean feasible(Position position, int elapsed, int fuel, CachedTrajectoryEffect effect) {
        return schedule(position, elapsed, fuel, effect).feasible();
    }

    /**
     * PART 12: the bounded, deterministic candidate set. Every element is justified by either the
     * PATROL's own timeline or one authoritative support event; nothing else is ever tried.
     */
    private List<Integer> candidateWaits(Position position, int elapsed, CachedTrajectoryEffect effect) {
        NavigableSet<Integer> waits = new TreeSet<>();
        waits.add(0);
        // A PATROL parked on a STATIONARY tanker is refuelled at the end of every step it waits, but never
        // at step 0, because the frozen simulator only refuels from step 1 onwards.
        if (stationaryRefuelPositions.contains(position)) waits.add(1);
        if (support.present()) {
            // PART 11/39: wait exactly long enough for the tanker to reach the PATROL where it stands.
            firstSupportStep(position, elapsed + 1).ifPresent(step -> waits.add(step - elapsed));
            // PART 14: or start late enough to meet the tanker at a cell in the middle of the leg.
            for (CachedTrajectoryEffect.Segment segment : effect.segments()) {
                firstSupportStep(segment.destination(), elapsed + segment.endOffset())
                        .ifPresent(step -> waits.add(step - segment.endOffset() - elapsed));
            }
        }
        List<Integer> bounded = new ArrayList<>();
        for (int wait : waits) {
            if (wait < 0 || elapsed + wait + effect.stepsUsed() > stepBudget) continue;
            bounded.add(wait);
        }
        return List.copyOf(bounded);
    }

    private OptionalInt firstSupportStep(Position position, int minStep) {
        int[] steps = refuelStepsByPosition.computeIfAbsent(position, key -> support.refuelOpportunitySteps(key)
                .stream().mapToInt(Integer::intValue).toArray());
        int floor = Math.max(minStep, 1);
        for (int step : steps) if (step >= floor) return OptionalInt.of(step);
        return OptionalInt.empty();
    }

    /**
     * Re-derives the schedule of a WHOLE committed prefix, leg by leg, from the PATROL's own start.
     *
     * <p>PART 12/13: the inserted waiting is a derived quantity, so it never has to be stored in the search
     * state. One PATROL's fuel timeline is independent of every other PATROL — refuelling sets the tank to
     * capacity regardless of who else is refuelled, and collecting udon costs no fuel — so replaying this
     * prefix alone reproduces exactly the waits the team-wide chronology will settle.
     *
     * @return the scheduled legs in order, or {@code null} when any leg is infeasible
     */
    public List<StrategicChronologyReplay.ScheduledLeg> scheduleAll(Position start, int initialFuel,
            List<CachedTrajectoryEffect> legs) {
        Objects.requireNonNull(start, "Patrol start must not be null");
        Objects.requireNonNull(legs, "Legs must not be null");
        List<StrategicChronologyReplay.ScheduledLeg> scheduled = new ArrayList<>();
        Position cursor = start;
        int elapsed = 0;
        int fuel = initialFuel;
        for (CachedTrajectoryEffect leg : legs) {
            SupportAwareSchedule schedule = schedule(cursor, elapsed, fuel, leg);
            if (!schedule.feasible()) return null;
            scheduled.add(new StrategicChronologyReplay.ScheduledLeg(schedule.insertedWaitDuration(), leg));
            cursor = leg.goal();
            elapsed = schedule.scheduledEndStep();
            fuel = schedule.fuelAfter();
        }
        return List.copyOf(scheduled);
    }

    /**
     * Replays ONE candidate schedule in the frozen simulator order.
     *
     * <p>Waiting genuinely occupies the current cell, so a support event during the lead-in wait refuels
     * the PATROL. A move in progress does not genuinely occupy its retained source, so no refuelling can
     * happen on the intermediate steps of a move. An arrival genuinely occupies its destination, which is
     * what makes a mid-route rendezvous legal.
     */
    private SupportAwareSchedule attempt(Position position, int elapsed, int fuel,
            CachedTrajectoryEffect effect, int wait) {
        int tank = fuel;
        int events = 0;
        for (int step = elapsed + 1; step <= elapsed + wait; step++) {
            if (tank >= capacity || !refuels(step, position)) continue;
            tank = capacity;
            events++;
        }
        int base = elapsed + wait;
        int cursor = base;
        for (CachedTrajectoryEffect.Segment segment : effect.segments()) {
            int stepCost = segment.endOffset() - segment.startOffset();
            int fuelCost = segment.fuelAfter() - segment.fuelBefore();
            if (cursor + stepCost > stepBudget) return SupportAwareSchedule.infeasible("TIME");
            if (tank < fuelCost) {
                return SupportAwareSchedule.infeasible("FUEL_AT_STEP_" + (cursor + 1));
            }
            tank -= fuelCost;
            cursor += stepCost;
            if (tank < capacity && refuels(cursor, segment.destination())) {
                tank = capacity;
                events++;
            }
        }
        return new SupportAwareSchedule(true, base + 1, cursor, wait, fuel, tank, events,
                SupportAwareSchedule.OK);
    }

    /** The two authoritative refuelling sources, in the frozen simulator's own terms. */
    private boolean refuels(int step, Position position) {
        return stationaryRefuelPositions.contains(position) || support.canRefuel(step, position);
    }
}
