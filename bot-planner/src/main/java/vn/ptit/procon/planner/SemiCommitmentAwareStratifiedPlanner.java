package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/**
 * M12.1 semi-committed direct intent over the unchanged M11 stratified search.
 *
 * <p>Search architecture, expansion budget and stage split are byte for byte the ones
 * {@link CommitmentAwareStratifiedPlanner} and {@link StratifiedIntentAwareAnytimePlanner} already
 * use: discovery, strategy qualification and exploitation over the same {@link StratifiedSearchConfig}.
 * The opponent forecast itself is also unchanged — the same {@link OpponentIntentForecaster} routes and
 * the same {@link OpponentCommitmentForecast} claim classes. Only the reading of that forecast
 * differs.</p>
 *
 * <p>M10 charged every forecast claim against stock, so five opponent collectors holding up to three
 * intent targets each erased far more of our own projection than the opponent actually took. M12 swung
 * the other way: it let only {@link OpponentClaimCommitment#OBSERVED_NOW} delete a portion, so every
 * future claim became score pressure alone and the projection drifted back toward the raw simulator.
 * M12.1 keeps M12's hard depletion exactly and adds one bounded middle layer on top: when any
 * {@link OpponentClaimCommitment#DIRECT_INTENT} claim arrives strictly before us, <em>one</em> further
 * portion of that spot is reserved — one, whether one opponent or five are forecast to arrive there,
 * and only if a portion actually survived the hard depletion. {@code FOLLOW_ON_INTENT} still reserves
 * nothing.</p>
 *
 * <p>That per-spot cap is what keeps the model capacity-aware rather than collector-count-aware, and it
 * is the whole structural difference from the binary model. M12 remains available unchanged for A/B.</p>
 */
public final class SemiCommitmentAwareStratifiedPlanner implements DayPlanner {

    private final AnytimeTeamPlanner engine;

    public SemiCommitmentAwareStratifiedPlanner() {
        this(AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.defaults(), false);
    }

    public SemiCommitmentAwareStratifiedPlanner(AnytimePlannerConfig config) {
        this(config, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), false);
    }

    public SemiCommitmentAwareStratifiedPlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights) {
        this(config, intentConfig, weights, SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), false);
    }

    public SemiCommitmentAwareStratifiedPlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights,
            boolean diagnostics) {
        this(config, intentConfig, weights, SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), diagnostics);
    }

    public SemiCommitmentAwareStratifiedPlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights,
            SemiCommitmentAdjustmentWeights semiCommitmentWeights,
            StratifiedSearchConfig stratifiedConfig,
            boolean diagnostics) {
        this.engine = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_SEMI_COMMITMENT_AWARE,
                RiskAdjustmentWeights.defaults(),
                Objects.requireNonNull(intentConfig, "Intent configuration must not be null"),
                Objects.requireNonNull(weights, "Intent adjustment weights must not be null"),
                CommitmentAdjustmentWeights.defaults(),
                Objects.requireNonNull(
                        semiCommitmentWeights,
                        "Semi-commitment adjustment weights must not be null"),
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
