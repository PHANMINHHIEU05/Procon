package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/**
 * M11 bounded planner: the M10 corrected intent-aware complete-plan objective evaluated over a
 * diversity-aware search.
 *
 * <p>Plan evaluation is byte-for-byte the M10 corrected {@link IntentAwarePlanEvaluation}
 * ordering. Only plan discovery differs: the same expansion budget is spread across
 * strategically different opening branches instead of closely related variations of one opening.</p>
 */
public final class DiverseIntentAwareAnytimePlanner implements DayPlanner {

    private final AnytimeTeamPlanner engine;

    public DiverseIntentAwareAnytimePlanner() {
        this(AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), DiverseSearchConfig.defaults(), false);
    }

    public DiverseIntentAwareAnytimePlanner(AnytimePlannerConfig config) {
        this(config, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                DiverseSearchConfig.defaults(), false);
    }

    public DiverseIntentAwareAnytimePlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights) {
        this(config, intentConfig, weights, DiverseSearchConfig.defaults(), false);
    }

    public DiverseIntentAwareAnytimePlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights,
            boolean diagnostics) {
        this(config, intentConfig, weights, DiverseSearchConfig.defaults(), diagnostics);
    }

    public DiverseIntentAwareAnytimePlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights,
            DiverseSearchConfig diverseConfig,
            boolean diagnostics) {
        this.engine = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.ANYTIME_DIVERSE_INTENT_AWARE,
                RiskAdjustmentWeights.defaults(),
                Objects.requireNonNull(intentConfig, "Intent configuration must not be null"),
                Objects.requireNonNull(weights, "Intent adjustment weights must not be null"),
                Objects.requireNonNull(diverseConfig, "Diverse search configuration must not be null"),
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
