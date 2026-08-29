package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/**
 * M15 full-day replacement-aware opponent objective over unchanged M11 search and M13.1 horizon.
 *
 * <p>An isolated mode: {@link RelativeMarginAwarePlanner} and every earlier mode keep their exact
 * previous behaviour, so all of them remain available for regression and A/B testing.</p>
 */
public final class ReplacementAwareRelativeMarginPlanner implements DayPlanner {

    private final AnytimeTeamPlanner engine;

    public ReplacementAwareRelativeMarginPlanner() {
        this(AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.defaults(), false);
    }

    public ReplacementAwareRelativeMarginPlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights intentWeights,
            SemiCommitmentAdjustmentWeights semiWeights,
            StratifiedSearchConfig stratifiedConfig,
            boolean diagnostics) {
        engine = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_REPLACEMENT_AWARE_RELATIVE_MARGIN,
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
