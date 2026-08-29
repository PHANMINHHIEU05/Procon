package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/**
 * M16 terminal objective: coupled competitive margin over the unchanged M12.1 and M13.1 values.
 *
 * <p>A separate record on purpose. It never mutates or reinterprets the M14
 * {@link RelativeMarginPlanEvaluation} or the M15 {@link ReplacementAwareRelativeMarginEvaluation}, so
 * all three objectives stay available side by side for regression and A/B testing.</p>
 *
 * <p>Unlike M15, the authoritative own quantities here are NOT the M12.1 semi-realizable counts. They
 * are the collections our fixed action routes really achieve on the shared coupled stock timeline, so
 * an own planned arrival the opponent beat to the spot contributes nothing and removes nothing. M12.1
 * survives only as a risk signal and a diagnostic, at ordering key 7.</p>
 *
 * <p>Key 1 is coupled own team brand coverage, ahead of the margin. Losing a daily brand costs a whole
 * daily bonus, so no margin trade may ever buy it back; a plan that really realizes four brands beats a
 * three-brand plan whatever the margin says.</p>
 */
public record CoupledCompetitiveMarginEvaluation(
        SemiCommitmentAwarePlanEvaluation semiCommitment,
        CoupledCompetitiveRolloutResult coupled,
        TeamNextDayHarvestCapacity nextDayHarvestCapacity) {

    private static final Comparator<CoupledCompetitiveMarginEvaluation> PREFERENCE =
            (left, right) -> {
                // 1. Coupled own team brand coverage. Brand safety outranks every margin trade.
                int compared = Integer.compare(right.coupledOwnBrands(), left.coupledOwnBrands());
                if (compared != 0) {
                    return compared;
                }
                // 2. Coupled competitive margin.
                compared = Integer.compare(
                        right.projectedCoupledMargin(), left.projectedCoupledMargin());
                if (compared != 0) {
                    return compared;
                }
                // 3. Coupled own collections. At an equal margin the plan that really collects more
                //    is preferred over one that merely denies more, because our own collections are
                //    observed on our fixed routes while opponent denial rests on a forecast.
                compared = Integer.compare(
                        right.coupledOwnCollections(), left.coupledOwnCollections());
                if (compared != 0) {
                    return compared;
                }
                // 4-6. Unchanged M13.1 horizon keys, only while a future day still exists.
                if (left.nextDayHarvestCapacity.remainingFutureDays() > 0
                        || right.nextDayHarvestCapacity.remainingFutureDays() > 0) {
                    compared = -TeamNextDayHarvestCapacity.compareStructural(
                            left.nextDayHarvestCapacity, right.nextDayHarvestCapacity);
                    if (compared != 0) {
                        return compared;
                    }
                }
                // 7. M12.1 adjusted score, kept as an unchanged risk signal.
                compared = Integer.compare(right.semiScore(), left.semiScore());
                if (compared != 0) {
                    return compared;
                }
                // 8. Raw simulator own Udon.
                compared = Integer.compare(right.semiCommitment.base().udonTotal(),
                        left.semiCommitment.base().udonTotal());
                if (compared != 0) {
                    return compared;
                }
                // 9. Remaining PATROL fuel at the end of the day.
                compared = Integer.compare(right.semiCommitment.base().remainingFuelTotal(),
                        left.semiCommitment.base().remainingFuelTotal());
                if (compared != 0) {
                    return compared;
                }
                // 10. Fewer movement steps.
                compared = Integer.compare(left.semiCommitment.base().movementSteps(),
                        right.semiCommitment.base().movementSteps());
                // 11. Deterministic signature.
                return compared != 0 ? compared : left.semiCommitment.base().deterministicSignature()
                        .compareTo(right.semiCommitment.base().deterministicSignature());
            };

    public CoupledCompetitiveMarginEvaluation {
        Objects.requireNonNull(semiCommitment, "M12.1 evaluation must not be null");
        Objects.requireNonNull(coupled, "Coupled competitive rollout result must not be null");
        Objects.requireNonNull(nextDayHarvestCapacity, "M13.1 capacity must not be null");
    }

    /** Distinct brands our team really collects on the coupled timeline. */
    public int coupledOwnBrands() {
        return coupled.coupledOwnBrands();
    }

    public int coupledOwnCollections() {
        return coupled.coupledOwnCollections();
    }

    public int plannedOwnOpportunityEvents() {
        return coupled.plannedOwnOpportunityEvents().size();
    }

    public int opponentBaselineCollections() {
        return coupled.opponentBaselineCollections();
    }

    public int coupledOpponentCollections() {
        return coupled.coupledOpponentCollections();
    }

    public int opponentCollectionsRemovedVsBaseline() {
        return coupled.opponentCollectionsRemovedVsBaseline();
    }

    public int ownPlannedEventsInvalidatedByOpponent() {
        return coupled.ownPlannedEventsInvalidatedByOpponent();
    }

    public int opponentReplacementCollections() {
        return coupled.opponentReplacementCollections();
    }

    /** Signed, unweighted: our coupled collections minus the opponent's coupled collections. */
    public int projectedCoupledMargin() {
        return coupled.projectedCoupledMargin();
    }

    /** Unchanged M12.1 semi-realizable counts, retained as a risk signal and for M15 comparison. */
    public int ownSemiBrands() {
        return semiCommitment.semiCommitmentRealizableBrandCount();
    }

    public int ownSemiCollections() {
        return semiCommitment.semiCommitmentRealizableCollections();
    }

    public int semiScore() {
        return semiCommitment.adjustedCollectionScore().value();
    }

    public boolean betterThan(CoupledCompetitiveMarginEvaluation other) {
        return PREFERENCE.compare(
                this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<CoupledCompetitiveMarginEvaluation> preference() {
        return PREFERENCE;
    }
}
