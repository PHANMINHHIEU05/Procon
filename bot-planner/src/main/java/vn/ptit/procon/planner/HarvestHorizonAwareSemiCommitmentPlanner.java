package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/** M13.1 next-day multi-spot harvest capacity over the unchanged M11 stratified search. */
public final class HarvestHorizonAwareSemiCommitmentPlanner implements DayPlanner {

    private final AnytimeTeamPlanner engine;

    public HarvestHorizonAwareSemiCommitmentPlanner() {
        this(AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.defaults(), false);
    }

    public HarvestHorizonAwareSemiCommitmentPlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights intentWeights,
            SemiCommitmentAdjustmentWeights semiWeights,
            StratifiedSearchConfig stratifiedConfig,
            boolean diagnostics) {
        this.engine = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_SEMI_COMMITMENT_HARVEST_HORIZON_AWARE,
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
        return engine.planWithStats(Objects.requireNonNull(state, "Day state must not be null"));
    }
}