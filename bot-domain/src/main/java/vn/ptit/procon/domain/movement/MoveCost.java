package vn.ptit.procon.domain.movement;

/** Official base cost of leaving a traversable source cell. */
public record MoveCost(int stepCost, int patrolFuelCost) {

    public MoveCost {
        if (stepCost <= 0) {
            throw new IllegalArgumentException("Move step cost must be positive: " + stepCost);
        }
        if (patrolFuelCost < 0) {
            throw new IllegalArgumentException(
                    "PATROL fuel cost must be non-negative: " + patrolFuelCost);
        }
    }
}