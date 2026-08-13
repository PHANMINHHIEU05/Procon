package vn.ptit.procon.domain.action;

/** Steps and agent-specific fuel consumed by an action. */
public record ActionCost(int steps, int fuel) {

    public ActionCost {
        if (steps <= 0) {
            throw new IllegalArgumentException("Action step cost must be positive: " + steps);
        }
        if (fuel < 0) {
            throw new IllegalArgumentException("Action fuel cost must be non-negative: " + fuel);
        }
    }
}