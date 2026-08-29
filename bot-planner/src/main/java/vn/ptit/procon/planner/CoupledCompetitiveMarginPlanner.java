package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/**
 * M16 coupled competitive objective over unchanged M11 search and M13.1 horizon.
 *
 * <p>An isolated mode: {@link ReplacementAwareRelativeMarginPlanner} and every earlier mode keep their
 * exact previous behaviour, so all of them remain available for regression and A/B testing. The only
 * thing that changes is the terminal plan-conditioned competitive evaluation, which now resolves both
 * sides on one shared chronological stock timeline instead of freezing our own collections.</p>
 */
public final class CoupledCompetitiveMarginPlanner implements DayPlanner {

    private final AnytimeTeamPlanner engine;

    public CoupledCompetitiveMarginPlanner() {
        this(AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.defaults(), false);
    }

    public CoupledCompetitiveMarginPlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights intentWeights,
            SemiCommitmentAdjustmentWeights semiWeights,
            StratifiedSearchConfig stratifiedConfig,
            boolean diagnostics) {
        engine = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_COUPLED_COMPETITIVE_MARGIN,
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
