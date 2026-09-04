package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.Route;

/**
 * PART 7/47: turns ONE selected existing R3 support root plus V3's own PATROL routes into ONE combined
 * {@link TeamPlan}.
 *
 * <p>The REFUEL actor is materialised from the authoritative R3 action sequence, verbatim, and padded with
 * WAIT to the day budget exactly as the existing full-day contract requires. Every PATROL emits the
 * deterministic lead-in waiting the scheduler derived (PART 12) followed by its committed moves, and is
 * padded to the same budget. No fuel is granted anywhere: the tank a PATROL spends after a wait exists
 * only because the tanker really stands on its cell at that step, which {@code PlanValidator} and
 * {@code DaySimulator} then confirm or reject.
 */
public final class V3SupportPlanMaterializer {

    private V3SupportPlanMaterializer() { }

    /** The combined plan, or empty when the committed routes cannot be executed under this root. */
    public static Optional<TeamPlan> materialize(DayState state, V3SupportRootContext root,
            SupportAwareTrajectoryScheduler scheduler, Map<AgentId, List<Route>> routesByPatrol) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(root, "Support root must not be null");
        Objects.requireNonNull(scheduler, "Scheduler must not be null");
        Objects.requireNonNull(routesByPatrol, "Committed routes must not be null");
        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            if (root.present() && agent.id().equals(root.refuelAgentId())) {
                List<AgentAction> tanker = new ArrayList<>(root.refuelActions());
                int consumed = root.trajectory().actionSteps();
                if (consumed > state.stepBudget()) return Optional.empty();
                if (consumed < state.stepBudget()) tanker.add(new WaitAction(state.stepBudget() - consumed));
                actions.put(agent.id(), List.copyOf(tanker));
                continue;
            }
            if (agent.kind() != AgentKind.PATROL) {
                actions.put(agent.id(), List.of(new WaitAction(state.stepBudget())));
                continue;
            }
            List<Route> routes = routesByPatrol.getOrDefault(agent.id(), List.of());
            List<StrategicChronologyReplay.ScheduledLeg> schedule = schedule(state, scheduler, agent, routes);
            if (schedule == null) return Optional.empty();
            List<AgentAction> sequence = new ArrayList<>();
            int used = 0;
            for (int leg = 0; leg < routes.size(); leg++) {
                int lead = schedule.get(leg).leadInWaitSteps();
                // A zero-length lead-in emits nothing: WaitAction rejects a non-positive duration.
                if (lead > 0) {
                    sequence.add(new WaitAction(lead));
                    used += lead;
                }
                for (var direction : routes.get(leg).directions()) sequence.add(new MoveAction(direction));
                used += routes.get(leg).stepsUsed();
                if (used > state.stepBudget()) return Optional.empty();
            }
            if (used < state.stepBudget()) sequence.add(new WaitAction(state.stepBudget() - used));
            actions.put(agent.id(), List.copyOf(sequence));
        }
        try {
            return Optional.of(new TeamPlan(actions));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * The scheduled legs of one PATROL, or {@code null} when a leg is infeasible under this root.
     *
     * <p>PART 12/13: the lead-in waiting is derived, never searched, so the same committed routes always
     * produce the same plan.
     */
    public static List<StrategicChronologyReplay.ScheduledLeg> schedule(DayState state,
            SupportAwareTrajectoryScheduler scheduler, AgentState patrol, List<Route> routes) {
        List<CachedTrajectoryEffect> effects = routes.stream()
                .map(route -> CachedTrajectoryEffect.from(state, patrol.id(), route)).toList();
        return scheduler.scheduleAll(patrol.position(), ((FiniteFuel) patrol.fuel()).amount(), effects);
    }
}
