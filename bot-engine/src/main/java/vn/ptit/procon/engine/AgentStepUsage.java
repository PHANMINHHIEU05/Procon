package vn.ptit.procon.engine;

/** Explicit steps consumed by one complete submitted agent plan. */
public record AgentStepUsage(int explicitSteps) {

    public AgentStepUsage {
        if (explicitSteps < 0) {
            throw new IllegalArgumentException("Explicit step usage must be non-negative");
        }
    }

    public int totalSteps() {
        return explicitSteps;
    }
}
