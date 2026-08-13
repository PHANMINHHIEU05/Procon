package vn.ptit.procon.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.agent.UnlimitedFuel;
import vn.ptit.procon.domain.movement.MoveCost;

class FuelRulesTest {

    private static final MoveCost MOVE_COST = new MoveCost(3, 2);

    @Test
    void patrolFuelFeasibilityHandlesExactMoreAndInsufficientFuel() {
        assertTrue(FuelRules.canAfford(new FiniteFuel(2), MOVE_COST));
        assertTrue(FuelRules.canAfford(new FiniteFuel(5), MOVE_COST));
        assertFalse(FuelRules.canAfford(new FiniteFuel(1), MOVE_COST));
    }

    @Test
    void zeroFuelIsValidButCannotPayPositiveFuelMovement() {
        assertFalse(FuelRules.canAfford(new FiniteFuel(0), MOVE_COST));
        assertThrows(
                IllegalArgumentException.class,
                () -> FuelRules.remainingFuelAfterMove(new FiniteFuel(0), MOVE_COST));
    }

    @Test
    void patrolRemainingFuelIsCalculatedExactly() {
        assertEquals(new FiniteFuel(0), FuelRules.remainingFuelAfterMove(new FiniteFuel(2), MOVE_COST));
        assertEquals(new FiniteFuel(3), FuelRules.remainingFuelAfterMove(new FiniteFuel(5), MOVE_COST));
    }

    @Test
    void refuelAlwaysHasZeroFuelConsumptionAndUnlimitedRemainingFuel() {
        assertEquals(0, FuelRules.requiredFuel(AgentKind.REFUEL, MOVE_COST));
        assertTrue(FuelRules.canAfford(UnlimitedFuel.INSTANCE, MOVE_COST));
        assertSame(
                UnlimitedFuel.INSTANCE,
                FuelRules.remainingFuelAfterMove(UnlimitedFuel.INSTANCE, MOVE_COST));
        assertInstanceOf(
                UnlimitedFuel.class,
                FuelRules.remainingFuelAfterMove(UnlimitedFuel.INSTANCE, MOVE_COST));
    }

    @Test
    void negativeCurrentFuelIsRejectedByTheFuelDomain() {
        assertThrows(IllegalArgumentException.class, () -> new FiniteFuel(-1));
    }
}