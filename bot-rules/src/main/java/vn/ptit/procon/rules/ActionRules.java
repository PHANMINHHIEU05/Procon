package vn.ptit.procon.rules;

import java.util.Objects;
import vn.ptit.procon.domain.action.ActionCost;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.movement.MoveCost;

/** Pure primitive action-cost rules without timeline or movement validation. */
public final class ActionRules {

    private ActionRules() {
    }

    public static ActionCost waitCost(WaitAction action) {
        Objects.requireNonNull(action, "WAIT action must not be null");
        return new ActionCost(action.steps(), 0);
    }

    public static ActionCost moveCost(
            MoveAction action, AgentKind kind, MoveCost officialMoveCost) {
        Objects.requireNonNull(action, "Move action must not be null");
        return new ActionCost(
                officialMoveCost.stepCost(), FuelRules.requiredFuel(kind, officialMoveCost));
    }

}