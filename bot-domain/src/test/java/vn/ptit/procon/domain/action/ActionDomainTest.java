package vn.ptit.procon.domain.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.map.Direction;

class ActionDomainTest {

    @Test
    void moveActionRequiresDirection() {
        AgentAction action = new MoveAction(Direction.DOWN_RIGHT);

        assertEquals(Direction.DOWN_RIGHT, ((MoveAction) action).direction());
        assertThrows(NullPointerException.class, () -> new MoveAction(null));
    }

    @Test
    void waitActionAcceptsPositiveStepCounts() {
        AgentAction oneStep = new WaitAction(1);
        AgentAction largeWait = new WaitAction(1_000_000);

        assertEquals(1, ((WaitAction) oneStep).steps());
        assertEquals(1_000_000, ((WaitAction) largeWait).steps());
        assertInstanceOf(WaitAction.class, largeWait);
    }

    @Test
    void waitActionRejectsNonPositiveStepCounts() {
        assertThrows(IllegalArgumentException.class, () -> new WaitAction(0));
        assertThrows(IllegalArgumentException.class, () -> new WaitAction(-1));
    }

    @Test
    void actionCostValidatesPrimitiveCosts() {
        assertEquals(new ActionCost(3, 0), new ActionCost(3, 0));
        assertThrows(IllegalArgumentException.class, () -> new ActionCost(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ActionCost(1, -1));
    }
}