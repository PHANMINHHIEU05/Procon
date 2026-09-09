package vn.ptit.procon.planner.v2;

import java.util.Objects;
import vn.ptit.procon.planner.HybridCalibratedMarginEvaluation;
import vn.ptit.procon.planner.HybridScoringConfig;
import vn.ptit.procon.planner.PlanEvaluation;

/** Terminal result selected by Planner V2's unchanged hybrid comparator. */
public record JointTerminalEvaluation(
        PlanEvaluation base,
        HybridCalibratedMarginEvaluation hybrid,
        HybridScoringConfig scoring) {

    public JointTerminalEvaluation(PlanEvaluation base, HybridCalibratedMarginEvaluation hybrid) {
        this(base, hybrid, HybridScoringConfig.defaults());
    }

    public JointTerminalEvaluation {
        Objects.requireNonNull(base, "Base evaluation must not be null");
        Objects.requireNonNull(hybrid, "Hybrid evaluation must not be null");
        Objects.requireNonNull(scoring, "Hybrid scoring configuration must not be null");
    }

    public boolean betterThan(JointTerminalEvaluation other) {
        JointTerminalEvaluation value = Objects.requireNonNull(other, "Other evaluation must not be null");
        return HybridCalibratedMarginEvaluation.compare(hybrid, value.hybrid, scoring) < 0;
    }

    public int hybridOwnScore() { return hybrid.hybridOwnScore(scoring); }
    public int hybridOpponentScore() { return hybrid.hybridOpponentScore(scoring); }
    public int hybridMarginScore() { return hybrid.hybridMarginScore(scoring); }
}
