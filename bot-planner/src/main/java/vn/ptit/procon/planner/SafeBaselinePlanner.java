package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidation;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;

/** Conservative one-target-per-PATROL baseline planner. */
public final class SafeBaselinePlanner implements DayPlanner {

    private final WeightedRouteFinder routeFinder;
    private final PlanValidator validator;

    public SafeBaselinePlanner() {
        this(new WeightedRouteFinder(), new PlanValidator());
    }

    public SafeBaselinePlanner(WeightedRouteFinder routeFinder, PlanValidator validator) {
        this.routeFinder = Objects.requireNonNull(routeFinder, "Route finder must not be null");
        this.validator = Objects.requireNonNull(validator, "Plan validator must not be null");
    }

    @Override
    public TeamPlan plan(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        Map<Position, Integer> projectedStock = new LinkedHashMap<>(state.spotStock());
        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();

        for (AgentState agent : state.agents()) {
            List<AgentAction> agentActions = actionsFor(agent, state, projectedStock);
            actions.put(agent.id(), agentActions);
        }

        TeamPlan plan = new TeamPlan(actions);
        PlanValidation validation = validator.validate(state, plan);
        if (validation.valid()) {
            log("BASELINE_PLAN_VALID");
            return plan;
        }

        TeamPlan fallback = SafePlanFactory.waitAll(state);
        PlanValidation fallbackValidation = validator.validate(state, fallback);
        if (!fallbackValidation.valid()) {
            throw new IllegalStateException(
                    "Safe fallback plan rejected: " + fallbackValidation.failure().orElseThrow());
        }
        log("BASELINE_PLAN_FALLBACK", "reason", validation.failure().orElseThrow());
        return fallback;
    }

    private List<AgentAction> actionsFor(
            AgentState agent, DayState state, Map<Position, Integer> projectedStock) {
        if (agent.kind() == AgentKind.REFUEL
                || agent.fuel() instanceof FiniteFuel fuel && fuel.amount() == 0) {
            logNoTarget(state, agent, agent.kind() == AgentKind.REFUEL ? "REFUEL" : "ZERO_FUEL");
            return wait(state);
        }

        Optional<Target> target = stockedTargets(state, agent, projectedStock).stream()
                .min(Comparator.comparingInt((Target candidate) -> candidate.route.stepsUsed())
                        .thenComparingInt(candidate -> candidate.route.fuelUsed())
                        .thenComparingInt(candidate -> candidate.position.value()));
        if (target.isEmpty()) {
            logNoTarget(state, agent, "NO_FEASIBLE_UDON");
            return wait(state);
        }

        Target selected = target.orElseThrow();
        projectedStock.computeIfPresent(selected.position, (position, stock) -> Math.max(0, stock - 1));
        log("PLAN_TARGET", "day", state.day().value(), "agent", agent.id().value(),
                "from", agent.position().value(), "target", selected.position.value(),
                "steps", selected.route.stepsUsed(), "fuel", selected.route.fuelUsed(),
                "moves", selected.route.directions().size());
        if (selected.route.directions().isEmpty()) {
            return wait(state);
        }
        return List.copyOf(selected.route.toMoveActions());
    }

    private List<Target> stockedTargets(
            DayState state, AgentState agent, Map<Position, Integer> projectedStock) {
        List<Target> result = new ArrayList<>();
        state.matchData().udonSpots().stream()
                .map(spot -> spot.position())
                .sorted(Comparator.comparingInt(Position::value))
                .forEach(position -> {
                    if (projectedStock.getOrDefault(position, 0) <= 0) {
                        return;
                    }
                    routeFinder.find(state, agent, position)
                            .map(route -> new Target(position, route))
                            .ifPresent(result::add);
                });
        return result;
    }

    private List<AgentAction> wait(DayState state) {
        return List.of(new WaitAction(state.stepBudget()));
    }

    private void logNoTarget(DayState state, AgentState agent, String reason) {
        log("PLAN_NO_TARGET", "day", state.day().value(), "agent", agent.id().value(), "reason", reason);
    }

    private void log(String event, Object... fields) {
        StringBuilder message = new StringBuilder(event);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            message.append(' ').append(fields[index]).append('=').append(fields[index + 1]);
        }
        System.out.println(message);
    }

    private record Target(Position position, Route route) {
    }
}