package vn.ptit.procon.planner;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidation;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;

/** Deterministic same-day multi-target planner that prefers brand diversity. */
public final class BrandAwarePlanner implements DayPlanner {

    private final WeightedRouteFinder routeFinder;
    private final BrandAwarePatrolPlanner patrolPlanner;
    private final PlanValidator validator;

    public BrandAwarePlanner() {
        this(new WeightedRouteFinder(), new PlanValidator());
    }

    public BrandAwarePlanner(WeightedRouteFinder routeFinder, PlanValidator validator) {
        this.routeFinder = Objects.requireNonNull(routeFinder, "Route finder must not be null");
        this.patrolPlanner = new BrandAwarePatrolPlanner(this.routeFinder);
        this.validator = Objects.requireNonNull(validator, "Plan validator must not be null");
    }

    @Override
    public TeamPlan plan(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        Map<Position, Integer> projectedStock = new LinkedHashMap<>(state.spotStock());
        Set<BrandId> teamBrands = new LinkedHashSet<>();
        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();

        for (AgentState agent : state.agents()) {
            List<AgentAction> agentActions = agent.kind() == AgentKind.REFUEL
                    ? waitFullDay(state)
                    : patrolPlanner.plan(
                            agent,
                            state,
                            0,
                            ((FiniteFuel) agent.fuel()).amount(),
                            projectedStock,
                            teamBrands,
                            "BRAND_AWARE").actions();
            actions.put(agent.id(), agentActions);
        }

        TeamPlan plan = new TeamPlan(actions);
        PlanValidation validation = validator.validate(state, plan);
        if (validation.valid()) {
            log("BRAND_AWARE_PLAN_VALID");
            return plan;
        }

        TeamPlan fallback = SafePlanFactory.waitAll(state);
        PlanValidation fallbackValidation = validator.validate(state, fallback);
        if (!fallbackValidation.valid()) {
            throw new IllegalStateException(
                    "Safe fallback plan rejected: " + fallbackValidation.failure().orElseThrow());
        }
        log("BRAND_AWARE_PLAN_FALLBACK", "reason", validation.failure().orElseThrow());
        return fallback;
    }

    private List<AgentAction> waitFullDay(DayState state) {
        return List.of(new WaitAction(state.stepBudget()));
    }

    private void log(String event, Object... fields) {
        StringBuilder message = new StringBuilder(event);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            message.append(' ').append(fields[index]).append('=').append(fields[index + 1]);
        }
        System.out.println(message);
    }
}
