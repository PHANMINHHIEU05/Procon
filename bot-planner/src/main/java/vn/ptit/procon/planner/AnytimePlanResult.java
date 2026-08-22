package vn.ptit.procon.planner;

import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.engine.TeamPlan;

/** Complete anytime planning result with its simulator-backed value and work statistics. */
public record AnytimePlanResult(
        TeamPlan plan,
        PlanEvaluation evaluation,
        AnytimeSearchStats stats,
        Optional<RiskAdjustedPlanEvaluation> riskAdjustedEvaluation,
        Optional<IntentAwarePlanEvaluation> intentAwareEvaluation,
        Optional<DiverseSearchStats> diverseSearchStats,
        Optional<StratifiedSearchStats> stratifiedSearchStats,
        Optional<CommitmentAwarePlanEvaluation> commitmentAwareEvaluation,
        Optional<SemiCommitmentAwarePlanEvaluation> semiCommitmentAwareEvaluation) {

    public AnytimePlanResult {
        Objects.requireNonNull(plan, "Plan must not be null");
        Objects.requireNonNull(evaluation, "Plan evaluation must not be null");
        Objects.requireNonNull(stats, "Search statistics must not be null");
        Objects.requireNonNull(riskAdjustedEvaluation, "Risk-adjusted evaluation must not be null");
        Objects.requireNonNull(intentAwareEvaluation, "Intent-aware evaluation must not be null");
        Objects.requireNonNull(diverseSearchStats, "Diverse search statistics must not be null");
        Objects.requireNonNull(stratifiedSearchStats, "Stratified search statistics must not be null");
        Objects.requireNonNull(
                commitmentAwareEvaluation, "Commitment-aware evaluation must not be null");
        Objects.requireNonNull(
                semiCommitmentAwareEvaluation,
                "Semi-commitment-aware evaluation must not be null");
    }

    public AnytimePlanResult(
            TeamPlan plan,
            PlanEvaluation evaluation,
            AnytimeSearchStats stats) {
        this(plan, evaluation, stats, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public AnytimePlanResult(
            TeamPlan plan,
            PlanEvaluation evaluation,
            AnytimeSearchStats stats,
            Optional<RiskAdjustedPlanEvaluation> riskAdjustedEvaluation) {
        this(plan, evaluation, stats, riskAdjustedEvaluation, Optional.empty(), Optional.empty());
    }

    public AnytimePlanResult(
            TeamPlan plan,
            PlanEvaluation evaluation,
            AnytimeSearchStats stats,
            Optional<RiskAdjustedPlanEvaluation> riskAdjustedEvaluation,
            Optional<IntentAwarePlanEvaluation> intentAwareEvaluation) {
        this(plan, evaluation, stats, riskAdjustedEvaluation, intentAwareEvaluation, Optional.empty());
    }

    public AnytimePlanResult(
            TeamPlan plan,
            PlanEvaluation evaluation,
            AnytimeSearchStats stats,
            Optional<RiskAdjustedPlanEvaluation> riskAdjustedEvaluation,
            Optional<IntentAwarePlanEvaluation> intentAwareEvaluation,
            Optional<DiverseSearchStats> diverseSearchStats) {
        this(
                plan,
                evaluation,
                stats,
                riskAdjustedEvaluation,
                intentAwareEvaluation,
                diverseSearchStats,
                Optional.empty());
    }

    public AnytimePlanResult(
            TeamPlan plan,
            PlanEvaluation evaluation,
            AnytimeSearchStats stats,
            Optional<RiskAdjustedPlanEvaluation> riskAdjustedEvaluation,
            Optional<IntentAwarePlanEvaluation> intentAwareEvaluation,
            Optional<DiverseSearchStats> diverseSearchStats,
            Optional<StratifiedSearchStats> stratifiedSearchStats) {
        this(
                plan,
                evaluation,
                stats,
                riskAdjustedEvaluation,
                intentAwareEvaluation,
                diverseSearchStats,
                stratifiedSearchStats,
                Optional.empty());
    }

    public AnytimePlanResult(
            TeamPlan plan,
            PlanEvaluation evaluation,
            AnytimeSearchStats stats,
            Optional<RiskAdjustedPlanEvaluation> riskAdjustedEvaluation,
            Optional<IntentAwarePlanEvaluation> intentAwareEvaluation,
            Optional<DiverseSearchStats> diverseSearchStats,
            Optional<StratifiedSearchStats> stratifiedSearchStats,
            Optional<CommitmentAwarePlanEvaluation> commitmentAwareEvaluation) {
        this(
                plan,
                evaluation,
                stats,
                riskAdjustedEvaluation,
                intentAwareEvaluation,
                diverseSearchStats,
                stratifiedSearchStats,
                commitmentAwareEvaluation,
                Optional.empty());
    }
}
