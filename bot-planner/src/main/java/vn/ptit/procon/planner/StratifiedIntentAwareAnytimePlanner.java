package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/**
 * M11 strategy-depth correction: the M10 corrected intent-aware complete-plan objective evaluated
 * over a search that gives a bounded portfolio of opening strategies real depth.
 *
 * <p>Plan evaluation is byte-for-byte the M10 corrected {@link IntentAwarePlanEvaluation}
 * ordering, and the expansion budget is exactly the one M11 already used. The correction is purely
 * in scheduling: instead of touching many strategies once while one dominant strategy absorbs most
 * expansions, the same budget is split into discovery, strategy qualification and exploitation, so
 * a strategy that only looks better two or three expansions deep can still be found.</p>
 */
public final class StratifiedIntentAwareAnytimePlanner implements DayPlanner {

    private final AnytimeTeamPlanner engine;

    public StratifiedIntentAwareAnytimePlanner() {
        this(AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), StratifiedSearchConfig.defaults(), false);
    }

    public StratifiedIntentAwareAnytimePlanner(AnytimePlannerConfig config) {
        this(config, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), false);
    }

    public StratifiedIntentAwareAnytimePlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights) {
        this(config, intentConfig, weights,
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), false);
    }

    public StratifiedIntentAwareAnytimePlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights,
            boolean diagnostics) {
        this(config, intentConfig, weights,
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), diagnostics);
    }

    public StratifiedIntentAwareAnytimePlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights,
            StratifiedSearchConfig stratifiedConfig,
            boolean diagnostics) {
        this.engine = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_INTENT_AWARE,
                RiskAdjustmentWeights.defaults(),
                Objects.requireNonNull(intentConfig, "Intent configuration must not be null"),
                Objects.requireNonNull(weights, "Intent adjustment weights must not be null"),
                DiverseSearchConfig.defaults(),
                Objects.requireNonNull(
                        stratifiedConfig, "Stratified search configuration must not be null"),
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
