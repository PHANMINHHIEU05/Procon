package vn.ptit.procon.rules;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentFuel;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.agent.UnlimitedFuel;
import vn.ptit.procon.domain.movement.MoveCost;

/** Pure fuel feasibility and consumption rules. */
public final class FuelRules {

    private FuelRules() {
    }

    public static int requiredFuel(AgentKind kind, MoveCost moveCost) {
        Objects.requireNonNull(kind, "Agent kind must not be null");
        Objects.requireNonNull(moveCost, "Move cost must not be null");
        return kind == AgentKind.PATROL ? moveCost.patrolFuelCost() : 0;
    }

    public static boolean canAfford(AgentFuel currentFuel, MoveCost moveCost) {
        Objects.requireNonNull(currentFuel, "Current fuel must not be null");
        Objects.requireNonNull(moveCost, "Move cost must not be null");
        return switch (currentFuel) {
            case FiniteFuel finite -> finite.amount() >= moveCost.patrolFuelCost();
            case UnlimitedFuel ignored -> true;
        };
    }

    public static AgentFuel remainingFuelAfterMove(AgentFuel currentFuel, MoveCost moveCost) {
        Objects.requireNonNull(currentFuel, "Current fuel must not be null");
        Objects.requireNonNull(moveCost, "Move cost must not be null");
        return switch (currentFuel) {
            case UnlimitedFuel ignored -> UnlimitedFuel.INSTANCE;
            case FiniteFuel finite -> {
                if (finite.amount() < moveCost.patrolFuelCost()) {
                    throw new IllegalArgumentException("PATROL agent cannot afford the move");
                }
                yield new FiniteFuel(finite.amount() - moveCost.patrolFuelCost());
            }
        };
    }
}