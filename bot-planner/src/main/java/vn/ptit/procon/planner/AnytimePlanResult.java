package vn.ptit.procon.planner;

import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.engine.TeamPlan;

/** Complete anytime planning result with its simulator-backed value and work statistics. */
public record AnytimePlanResult(
        TeamPlan plan,
        PlanEvaluation evaluation,
        AnytimeSearchStats stats,
        Optional<RiskAdjustedPlanEvaluation> riskAdjustedEvaluation) {

    public AnytimePlanResult {
        Objects.requireNonNull(plan, "Plan must not be null");
        Objects.requireNonNull(evaluation, "Plan evaluation must not be null");
        Objects.requireNonNull(stats, "Search statistics must not be null");
        Objects.requireNonNull(riskAdjustedEvaluation, "Risk-adjusted evaluation must not be null");
    }

    public AnytimePlanResult(
            TeamPlan plan,
            PlanEvaluation evaluation,
            AnytimeSearchStats stats) {
        this(plan, evaluation, stats, Optional.empty());
    }
}