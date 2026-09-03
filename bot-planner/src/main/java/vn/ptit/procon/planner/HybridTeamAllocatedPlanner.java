package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/** M18 planner: bounded team opportunity ownership over the frozen hybrid evaluator. */
public final class HybridTeamAllocatedPlanner implements DayPlanner {

    private final AnytimeTeamPlanner delegate;

    public HybridTeamAllocatedPlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            SemiCommitmentAdjustmentWeights semiCommitmentAdjustmentWeights,
            StratifiedSearchConfig stratifiedSearchConfig,
            boolean contentionDiagnostics) {
        this.delegate = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_TEAM_ALLOCATED_HYBRID,
                RiskAdjustmentWeights.defaults(),
                Objects.requireNonNull(opponentIntentConfig, "Intent configuration must not be null"),
                Objects.requireNonNull(intentAdjustmentWeights, "Intent weights must not be null"),
                CommitmentAdjustmentWeights.defaults(),
                Objects.requireNonNull(semiCommitmentAdjustmentWeights,
                        "Semi-commitment weights must not be null"),
                DiverseSearchConfig.defaults(),
                Objects.requireNonNull(stratifiedSearchConfig, "Stratified configuration must not be null"),
                contentionDiagnostics);
    }

    @Override
    public TeamPlan plan(DayState state) {
        return planWithStats(state).plan();
    }

    public AnytimePlanResult planWithStats(DayState state) {
        return delegate.planWithStats(Objects.requireNonNull(state, "Day state must not be null"));
    }
}
