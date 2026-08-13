package vn.ptit.procon.engine;

/** Explicit plan steps and automatically padded WAIT steps for one agent. */
public record AgentStepUsage(int explicitSteps, int automaticWaitSteps) {

    public AgentStepUsage {
        if (explicitSteps < 0 || automaticWaitSteps < 0) {
            throw new IllegalArgumentException("Step usage values must be non-negative");
        }
    }

    public int totalSteps() {
        return explicitSteps + automaticWaitSteps;
    }
}