package vn.ptit.procon.planner;

import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/** M17 planner entry point: the M16.1 evaluator with diverse discovery candidates. */
public final class HybridDiverseCandidatePlanner implements DayPlanner {

    private final AnytimeTeamPlanner delegate;

    public HybridDiverseCandidatePlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            SemiCommitmentAdjustmentWeights semiCommitmentAdjustmentWeights,
            StratifiedSearchConfig stratifiedSearchConfig,
            boolean contentionDiagnostics) {
        this.delegate = new AnytimeTeamPlanner(
                config,
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_HYBRID_DIVERSE_CANDIDATES,
                RiskAdjustmentWeights.defaults(),
                opponentIntentConfig,
                intentAdjustmentWeights,
                CommitmentAdjustmentWeights.defaults(),
                semiCommitmentAdjustmentWeights,
                DiverseSearchConfig.defaults(),
                stratifiedSearchConfig,
                contentionDiagnostics);
    }

    @Override
    public TeamPlan plan(DayState state) {
        return delegate.plan(state);
    }

    public AnytimePlanResult planWithStats(DayState state) {
        return delegate.planWithStats(state);
    }
}
