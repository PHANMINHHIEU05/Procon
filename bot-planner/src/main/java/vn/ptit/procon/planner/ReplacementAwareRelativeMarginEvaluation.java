package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/**
 * M15 terminal objective: replacement-aware relative margin over the unchanged M12.1 and M13.1 values.
 *
 * <p>A separate record on purpose. It never mutates or reinterprets the M14
 * {@link RelativeMarginPlanEvaluation}, so both objectives stay available side by side for regression
 * and A/B testing.</p>
 *
 * <p>The primary quantity is {@link #projectedReplacementAwareMargin()}, a plain difference of two
 * collection counts with no weights: our own M12.1 semi-realizable collections minus the opponent
 * collections that survive a full-day replacement-aware rollout. It is signed, because a plan can lose
 * ground when the opponent replaces what we took with something better.</p>
 */
public record ReplacementAwareRelativeMarginEvaluation(
        SemiCommitmentAwarePlanEvaluation semiCommitment,
        OpponentFullDayResidualEvaluation opponentFullDay,
        TeamNextDayHarvestCapacity nextDayHarvestCapacity) {

    private static final Comparator<ReplacementAwareRelativeMarginEvaluation> PREFERENCE =
            (left, right) -> {
                // 1. Own M12.1 semi-realizable brand coverage.
                int compared = Integer.compare(right.ownSemiBrands(), left.ownSemiBrands());
                if (compared != 0) {
                    return compared;
                }
                // 2. Replacement-aware relative margin.
                compared = Integer.compare(
                        right.projectedReplacementAwareMargin(), left.projectedReplacementAwareMargin());
                if (compared != 0) {
                    return compared;
                }
                // 3. Own M12.1 semi-realizable collections.
                compared = Integer.compare(right.ownSemiCollections(), left.ownSemiCollections());
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
                // 7. Residual opponent collections; fewer is better.
                compared = Integer.compare(
                        left.residualOpponentCollections(), right.residualOpponentCollections());
                if (compared != 0) {
                    return compared;
                }
                // 8. M12.1 adjusted score.
                compared = Integer.compare(right.semiScore(), left.semiScore());
                if (compared != 0) {
                    return compared;
                }
                // 9. Raw simulator Udon.
                compared = Integer.compare(right.semiCommitment.base().udonTotal(),
                        left.semiCommitment.base().udonTotal());
                if (compared != 0) {
                    return compared;
                }
                // 10. Local simulator brand coverage.
                compared = Integer.compare(right.semiCommitment.base().teamBrandCount(),
                        left.semiCommitment.base().teamBrandCount());
                if (compared != 0) {
                    return compared;
                }
                // 11. Remaining fuel at the end of the day.
                compared = Integer.compare(right.semiCommitment.base().remainingFuelTotal(),
                        left.semiCommitment.base().remainingFuelTotal());
                if (compared != 0) {
                    return compared;
                }
                // 12. Fewer movement steps.
                compared = Integer.compare(left.semiCommitment.base().movementSteps(),
                        right.semiCommitment.base().movementSteps());
                // 13. Deterministic signature.
                return compared != 0 ? compared : left.semiCommitment.base().deterministicSignature()
                        .compareTo(right.semiCommitment.base().deterministicSignature());
            };

    public ReplacementAwareRelativeMarginEvaluation {
        Objects.requireNonNull(semiCommitment, "M12.1 evaluation must not be null");
        Objects.requireNonNull(opponentFullDay, "Full-day opponent residual must not be null");
        Objects.requireNonNull(nextDayHarvestCapacity, "M13.1 capacity must not be null");
    }

    public int ownSemiBrands() {
        return semiCommitment.semiCommitmentRealizableBrandCount();
    }

    public int ownSemiCollections() {
        return semiCommitment.semiCommitmentRealizableCollections();
    }

    public int baselineOpponentCollections() {
        return opponentFullDay.baselineOpponentCollections();
    }

    public int residualOpponentCollections() {
        return opponentFullDay.residualOpponentCollections();
    }

    public int netOpponentCollectionsRemoved() {
        return opponentFullDay.netOpponentCollectionsRemoved();
    }

    public int replacementCollections() {
        return opponentFullDay.replacementCollections();
    }

    /** Signed, unweighted: our realizable collections minus the opponent's surviving collections. */
    public int projectedReplacementAwareMargin() {
        return Math.subtractExact(ownSemiCollections(), residualOpponentCollections());
    }

    public int semiScore() {
        return semiCommitment.adjustedCollectionScore().value();
    }

    public boolean betterThan(ReplacementAwareRelativeMarginEvaluation other) {
        return PREFERENCE.compare(
                this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<ReplacementAwareRelativeMarginEvaluation> preference() {
        return PREFERENCE;
    }
}
