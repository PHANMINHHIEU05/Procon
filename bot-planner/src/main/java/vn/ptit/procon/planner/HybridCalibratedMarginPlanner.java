package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/** M16.1 planner using the existing coupled rollout with a calibrated 3:1 terminal objective. */
public final class HybridCalibratedMarginPlanner implements DayPlanner {
    private final AnytimeTeamPlanner engine;

    public HybridCalibratedMarginPlanner() {
        this(AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.defaults(), false);
    }

    public HybridCalibratedMarginPlanner(AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig, IntentAdjustmentWeights intentWeights,
            SemiCommitmentAdjustmentWeights semiWeights, StratifiedSearchConfig stratifiedConfig,
            boolean diagnostics) {
        engine = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_HYBRID_CALIBRATED_MARGIN,
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
    public TeamPlan plan(DayState state) { return planWithStats(state).plan(); }

    public AnytimePlanResult planWithStats(DayState state) {
        return engine.planWithStats(Objects.requireNonNull(state, "Day state must not be null"));
    }
}
