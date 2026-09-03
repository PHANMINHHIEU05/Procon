package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/** M19 entry point: capacity-aware candidate construction over the frozen hybrid search engine. */
public final class CapacityCompetitivePlanner implements DayPlanner {

    private final AnytimeTeamPlanner delegate;

    public CapacityCompetitivePlanner() {
        this(AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.defaults(), false);
    }

    public CapacityCompetitivePlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights intentWeights,
            SemiCommitmentAdjustmentWeights semiWeights,
            StratifiedSearchConfig stratifiedConfig,
            boolean diagnostics) {
        delegate = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_CAPACITY_COMPETITIVE,
                RiskAdjustmentWeights.defaults(),
                Objects.requireNonNull(intentConfig, "Intent configuration must not be null"),
                Objects.requireNonNull(intentWeights, "Intent weights must not be null"),
                CommitmentAdjustmentWeights.defaults(),
                Objects.requireNonNull(semiWeights, "Semi-commitment weights must not be null"),
                DiverseSearchConfig.defaults(),
                Objects.requireNonNull(stratifiedConfig, "Stratified configuration must not be null"),
                diagnostics);
    }

    @Override
    public TeamPlan plan(DayState state) {
        return planWithStats(state).plan();
    }

    public AnytimePlanResult planWithStats(DayState state) {
        return delegate.planWithStats(Objects.requireNonNull(state, "Day state must not be null"));
    }
}
