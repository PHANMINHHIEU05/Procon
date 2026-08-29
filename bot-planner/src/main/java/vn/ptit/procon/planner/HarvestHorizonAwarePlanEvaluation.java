package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/** M13.1 terminal objective with current collection count above structural next-day capacity. */
public record HarvestHorizonAwarePlanEvaluation(
        SemiCommitmentAwarePlanEvaluation semiCommitment,
        TeamNextDayHarvestCapacity nextDayHarvestCapacity) {

    private static final Comparator<HarvestHorizonAwarePlanEvaluation> PREFERENCE = (left, right) -> {
        int compared = Integer.compare(
                right.semiCommitment.semiCommitmentRealizableBrandCount(),
                left.semiCommitment.semiCommitmentRealizableBrandCount());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(
                right.semiCommitment.semiCommitmentRealizableCollections(),
                left.semiCommitment.semiCommitmentRealizableCollections());
        if (compared != 0) {
            return compared;
        }
        if (left.nextDayHarvestCapacity.remainingFutureDays() > 0
                || right.nextDayHarvestCapacity.remainingFutureDays() > 0) {
            compared = -TeamNextDayHarvestCapacity.compareStructural(
                    left.nextDayHarvestCapacity, right.nextDayHarvestCapacity);
            if (compared != 0) {
                return compared;
            }
        }
        compared = Integer.compare(
                right.semiCommitment.adjustedCollectionScore().value(),
                left.semiCommitment.adjustedCollectionScore().value());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(right.semiCommitment.base().udonTotal(),
                left.semiCommitment.base().udonTotal());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(right.semiCommitment.base().teamBrandCount(),
                left.semiCommitment.base().teamBrandCount());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(left.semiCommitment.hardClaimedFirstCollections(),
                right.semiCommitment.hardClaimedFirstCollections());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(left.semiCommitment.semiClaimedFirstCollections(),
                right.semiCommitment.semiClaimedFirstCollections());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(left.semiCommitment.directIntentBeforeCollections(),
                right.semiCommitment.directIntentBeforeCollections());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(left.semiCommitment.tieCollections(),
                right.semiCommitment.tieCollections());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(left.semiCommitment.followOnIntentBeforeCollections(),
                right.semiCommitment.followOnIntentBeforeCollections());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(right.semiCommitment.base().remainingFuelTotal(),
                left.semiCommitment.base().remainingFuelTotal());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(left.semiCommitment.base().movementSteps(),
                right.semiCommitment.base().movementSteps());
        return compared != 0 ? compared : left.semiCommitment.base().deterministicSignature()
                .compareTo(right.semiCommitment.base().deterministicSignature());
    };

    public HarvestHorizonAwarePlanEvaluation {
        Objects.requireNonNull(semiCommitment, "M12.1 evaluation must not be null");
        Objects.requireNonNull(nextDayHarvestCapacity, "Next-day capacity must not be null");
    }

    public boolean betterThan(HarvestHorizonAwarePlanEvaluation other) {
        return PREFERENCE.compare(this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<HarvestHorizonAwarePlanEvaluation> preference() {
        return PREFERENCE;
    }
}