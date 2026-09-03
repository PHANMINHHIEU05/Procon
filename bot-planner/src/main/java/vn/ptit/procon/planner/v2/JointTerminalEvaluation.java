package vn.ptit.procon.planner.v2;

import java.util.Objects;
import vn.ptit.procon.planner.HybridCalibratedMarginEvaluation;
import vn.ptit.procon.planner.PlanEvaluation;

/** Terminal result selected by Planner V2's unchanged hybrid comparator. */
public record JointTerminalEvaluation(
        PlanEvaluation base,
        HybridCalibratedMarginEvaluation hybrid) {

    public JointTerminalEvaluation {
        Objects.requireNonNull(base, "Base evaluation must not be null");
        Objects.requireNonNull(hybrid, "Hybrid evaluation must not be null");
    }

    public boolean betterThan(JointTerminalEvaluation other) {
        return hybrid.betterThan(Objects.requireNonNull(other, "Other evaluation must not be null").hybrid);
    }
}
