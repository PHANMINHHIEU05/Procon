package vn.ptit.procon.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;

/** Deterministic protocol-independent fallback plans. */
public final class SafePlanFactory {

    private SafePlanFactory() {
    }

    public static TeamPlan waitAll(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        Map<AgentId, List<AgentAction>> plans = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            plans.put(agent.id(), List.of(new WaitAction(state.stepBudget())));
        }
        return new TeamPlan(plans);
    }
}