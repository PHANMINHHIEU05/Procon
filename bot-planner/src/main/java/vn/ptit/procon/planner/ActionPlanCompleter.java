package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.WaitAction;

/** Appends an explicit WAIT so a candidate consumes exactly the full day. */
final class ActionPlanCompleter {

    private ActionPlanCompleter() {
    }

    static List<AgentAction> complete(
            List<? extends AgentAction> actions, int usedSteps, int daySteps) {
        Objects.requireNonNull(actions, "Actions must not be null");
        if (usedSteps < 0 || daySteps <= 0) {
            throw new IllegalArgumentException("Step counts must be non-negative with a positive day budget");
        }
        int remaining = daySteps - usedSteps;
        if (remaining < 0) {
            throw new IllegalArgumentException(
                    "Candidate consumes " + usedSteps + " steps but day budget is " + daySteps);
        }

        List<AgentAction> completed = new ArrayList<>(actions);
        if (remaining > 0) {
            completed.add(new WaitAction(remaining));
        }
        return List.copyOf(completed);
    }
}
