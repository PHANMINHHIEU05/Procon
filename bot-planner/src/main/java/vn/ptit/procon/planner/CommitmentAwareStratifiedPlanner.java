package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/**
 * M12 commitment-aware forecast correction over the unchanged M11 stratified search.
 *
 * <p>The search architecture, expansion budget and stage split are exactly the ones
 * {@link StratifiedIntentAwareAnytimePlanner} already used: discovery, strategy qualification and
 * exploitation over the same {@link StratifiedSearchConfig}. What changes is forecast semantics.</p>
 *
 * <p>M10 treated every retained opponent intent target that produced a route claim as if the portion
 * were already gone, so with five opponent collectors holding up to three intent targets each, the
 * projection removed far more of our own collections than the opponent actually took. M12 separates
 * an opponent already standing on a stocked spot ({@link OpponentClaimCommitment#OBSERVED_NOW}) from
 * its first future route claim ({@link OpponentClaimCommitment#DIRECT_INTENT}) and from every later
 * hypothetical continuation ({@link OpponentClaimCommitment#FOLLOW_ON_INTENT}). Only
 * {@code OBSERVED_NOW} arriving strictly before us deletes hard forecast stock; future intent still
 * shapes the score, candidate order and complete-plan objective, but can no longer erase a portion.
 * The result is a projection that stays pessimistic where the evidence is structural and stops being
 * pessimistic where it was only hypothetical.</p>
 */
public final class CommitmentAwareStratifiedPlanner implements DayPlanner {

    private final AnytimeTeamPlanner engine;

    public CommitmentAwareStratifiedPlanner() {
        this(AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), CommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.defaults(), false);
    }

    public CommitmentAwareStratifiedPlanner(AnytimePlannerConfig config) {
        this(config, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                CommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), false);
    }

    public CommitmentAwareStratifiedPlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights) {
        this(config, intentConfig, weights, CommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), false);
    }

    public CommitmentAwareStratifiedPlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights,
            boolean diagnostics) {
        this(config, intentConfig, weights, CommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), diagnostics);
    }

    public CommitmentAwareStratifiedPlanner(
            AnytimePlannerConfig config,
            OpponentIntentConfig intentConfig,
            IntentAdjustmentWeights weights,
            CommitmentAdjustmentWeights commitmentWeights,
            StratifiedSearchConfig stratifiedConfig,
            boolean diagnostics) {
        this.engine = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_COMMITMENT_AWARE,
                RiskAdjustmentWeights.defaults(),
                Objects.requireNonNull(intentConfig, "Intent configuration must not be null"),
                Objects.requireNonNull(weights, "Intent adjustment weights must not be null"),
                Objects.requireNonNull(
                        commitmentWeights, "Commitment adjustment weights must not be null"),
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
