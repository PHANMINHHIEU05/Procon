package vn.ptit.procon.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.ActionCost;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.movement.MoveCost;

class ActionRulesTest {

    @Test
    void waitConsumesItsStepsAndNoFuel() {
        assertEquals(new ActionCost(7, 0), ActionRules.waitCost(new WaitAction(7)));
    }

    @Test
    void moveUsesPatrolFuelCostForPatrol() {
        assertEquals(
                new ActionCost(4, 2),
                ActionRules.moveCost(
                        new MoveAction(Direction.RIGHT),
                        AgentKind.PATROL,
                        new MoveCost(4, 2)));
    }

    @Test
    void moveUsesZeroFuelForRefuel() {
        assertEquals(
                new ActionCost(4, 0),
                ActionRules.moveCost(
                        new MoveAction(Direction.RIGHT),
                        AgentKind.REFUEL,
                        new MoveCost(4, 2)));
    }
}