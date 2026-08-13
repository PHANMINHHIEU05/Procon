package vn.ptit.procon.domain.action;

/** A command to remain in place while consuming a positive number of steps. */
public record WaitAction(int steps) implements AgentAction {

    public WaitAction {
        if (steps <= 0) {
            throw new IllegalArgumentException("WAIT steps must be positive: " + steps);
        }
    }
}