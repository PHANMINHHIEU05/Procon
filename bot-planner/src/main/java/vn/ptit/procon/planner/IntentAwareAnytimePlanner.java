package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/** M10 bounded planner using capacity-constrained opponent intent and stock forecast. */
public final class IntentAwareAnytimePlanner implements DayPlanner {

    private final AnytimeTeamPlanner engine;

    public IntentAwareAnytimePlanner() {
        this(AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), false);
    }

    public IntentAwareAnytimePlanner(AnytimePlannerConfig config) {
        this(config, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(), false);
    }

    public IntentAwareAnytimePlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights) {
        this(config, intentConfig, weights, false);
    }

    public IntentAwareAnytimePlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights,
            boolean diagnostics) {
        this.engine = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.ANYTIME_INTENT_AWARE,
                RiskAdjustmentWeights.defaults(),
                Objects.requireNonNull(intentConfig, "Intent configuration must not be null"),
                Objects.requireNonNull(weights, "Intent adjustment weights must not be null"),
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