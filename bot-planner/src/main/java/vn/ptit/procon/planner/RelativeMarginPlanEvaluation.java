package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/** M14 terminal objective: fixed-baseline opponent denial plus unchanged M12.1/M13.1 values. */
public record RelativeMarginPlanEvaluation(
        SemiCommitmentAwarePlanEvaluation semiCommitment,
        OpponentResidualClaimEvaluation opponentClaims,
        TeamNextDayHarvestCapacity nextDayHarvestCapacity) {

    private static final Comparator<RelativeMarginPlanEvaluation> PREFERENCE = (left, right) -> {
        int compared = Integer.compare(right.ownSemiBrands(), left.ownSemiBrands());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(right.projectedStrongRelativeSwing(), left.projectedStrongRelativeSwing());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(right.ownSemiCollections(), left.ownSemiCollections());
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
                right.followOnDeniedOpponentCollections(), left.followOnDeniedOpponentCollections());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(right.semiScore(), left.semiScore());
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

    public RelativeMarginPlanEvaluation {
        Objects.requireNonNull(semiCommitment, "M12.1 evaluation must not be null");
        Objects.requireNonNull(opponentClaims, "Opponent residual evaluation must not be null");
        Objects.requireNonNull(nextDayHarvestCapacity, "M13.1 capacity must not be null");
    }

    public int ownSemiBrands() {
        return semiCommitment.semiCommitmentRealizableBrandCount();
    }

    public int ownSemiCollections() {
        return semiCommitment.semiCommitmentRealizableCollections();
    }

    public int strongDeniedOpponentCollections() {
        return opponentClaims.strongDeniedOpponentCollections();
    }

    public int followOnDeniedOpponentCollections() {
        return opponentClaims.deniedFollowOnIntent();
    }

    public int projectedStrongRelativeSwing() {
        return Math.addExact(ownSemiCollections(), strongDeniedOpponentCollections());
    }

    public int opponentBaselineStrongRealizable() {
        return opponentClaims.baseline().strongRealizable();
    }

    public int opponentResidualStrongRealizable() {
        return opponentClaims.residualStrongRealizable();
    }

    public int semiScore() {
        return semiCommitment.adjustedCollectionScore().value();
    }

    public boolean betterThan(RelativeMarginPlanEvaluation other) {
        return PREFERENCE.compare(this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<RelativeMarginPlanEvaluation> preference() {
        return PREFERENCE;
    }
}