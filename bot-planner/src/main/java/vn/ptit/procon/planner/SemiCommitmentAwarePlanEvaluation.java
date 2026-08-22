package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/**
 * Complete-plan M12.1 objective over raw simulation and the bounded semi-reservation forecast.
 *
 * <p>A new record rather than an extension of {@link CommitmentAwarePlanEvaluation}, so M12 keeps
 * ranking plans by exactly the thirteen keys it shipped with and both modes stay available for A/B.
 * The ordering here is the M12 one with {@code semiClaimedFirstCollections} inserted directly after
 * the hard losses: a bounded reservation is the second-worst thing that can happen to a projected
 * collection, worse than a direct conflict the spot still has capacity for.</p>
 *
 * <p>{@code commitmentRealizableCollections} and {@code oldForecastRealizableCollections} ride along
 * for the three-way calibration only and never participate in the ordering.</p>
 */
public record SemiCommitmentAwarePlanEvaluation(
        PlanEvaluation base,
        SemiCommitmentAdjustedCollectionScore adjustedCollectionScore,
        int semiCommitmentRealizableBrandCount,
        int semiCommitmentRealizableCollections,
        int commitmentRealizableCollections,
        int oldForecastRealizableCollections,
        int hardClaimedFirstCollections,
        int semiClaimedFirstCollections,
        int directIntentBeforeCollections,
        int followOnIntentBeforeCollections,
        int tieCollections,
        int unforecastedCollections) {

    private static final Comparator<SemiCommitmentAwarePlanEvaluation> PREFERENCE = Comparator
            .comparingInt((SemiCommitmentAwarePlanEvaluation eval) ->
                    eval.semiCommitmentRealizableBrandCount)
            .reversed()
            .thenComparing(Comparator.comparingInt(
                    (SemiCommitmentAwarePlanEvaluation eval) ->
                            eval.adjustedCollectionScore.value())
                    .reversed())
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwarePlanEvaluation::semiCommitmentRealizableCollections)
                    .reversed())
            .thenComparing(Comparator.comparingInt(
                    (SemiCommitmentAwarePlanEvaluation eval) -> eval.base.udonTotal()).reversed())
            .thenComparing(Comparator.comparingInt(
                    (SemiCommitmentAwarePlanEvaluation eval) -> eval.base.teamBrandCount())
                    .reversed())
            .thenComparingInt(SemiCommitmentAwarePlanEvaluation::hardClaimedFirstCollections)
            .thenComparingInt(SemiCommitmentAwarePlanEvaluation::semiClaimedFirstCollections)
            .thenComparingInt(SemiCommitmentAwarePlanEvaluation::directIntentBeforeCollections)
            .thenComparingInt(SemiCommitmentAwarePlanEvaluation::tieCollections)
            .thenComparingInt(SemiCommitmentAwarePlanEvaluation::followOnIntentBeforeCollections)
            .thenComparing(Comparator.comparingInt(
                    (SemiCommitmentAwarePlanEvaluation eval) -> eval.base.activePatrolCount())
                    .reversed())
            .thenComparing(Comparator.comparingInt(
                    (SemiCommitmentAwarePlanEvaluation eval) -> eval.base.remainingFuelTotal())
                    .reversed())
            .thenComparingInt(eval -> eval.base.movementSteps())
            .thenComparing(eval -> eval.base.deterministicSignature());

    public SemiCommitmentAwarePlanEvaluation {
        Objects.requireNonNull(base, "Base plan evaluation must not be null");
        Objects.requireNonNull(
                adjustedCollectionScore, "Semi-commitment-adjusted score must not be null");
        if (semiCommitmentRealizableBrandCount < 0 || semiCommitmentRealizableCollections < 0
                || commitmentRealizableCollections < 0 || oldForecastRealizableCollections < 0
                || hardClaimedFirstCollections < 0 || semiClaimedFirstCollections < 0
                || directIntentBeforeCollections < 0 || followOnIntentBeforeCollections < 0
                || tieCollections < 0 || unforecastedCollections < 0) {
            throw new IllegalArgumentException(
                    "Semi-commitment-aware evaluation metrics must be non-negative");
        }
        if (semiCommitmentRealizableBrandCount > base.teamBrandCount()) {
            throw new IllegalArgumentException(
                    "Semi-commitment-realizable brands cannot exceed local simulator team brands");
        }
        if (semiCommitmentRealizableCollections > base.udonTotal()) {
            throw new IllegalArgumentException(
                    "Semi-commitment-realizable collections cannot exceed raw simulator collections");
        }
        if (commitmentRealizableCollections > base.udonTotal()) {
            throw new IllegalArgumentException(
                    "M12 commitment-realizable collections cannot exceed raw simulator collections");
        }
        if (semiCommitmentRealizableCollections > commitmentRealizableCollections) {
            throw new IllegalArgumentException(
                    "M12.1 semi-commitment-realizable collections cannot exceed the M12"
                            + " commitment-realizable count for the same plan and forecast");
        }
        if (oldForecastRealizableCollections > semiCommitmentRealizableCollections) {
            throw new IllegalArgumentException(
                    "M10 forecast-realizable collections cannot exceed the M12.1"
                            + " semi-commitment-realizable count for the same plan and forecast");
        }
        if (hardClaimedFirstCollections + semiClaimedFirstCollections > base.udonTotal()) {
            throw new IllegalArgumentException(
                    "Hard-claimed and semi-claimed collections cannot exceed raw simulator"
                            + " collections");
        }
    }

    /** Local simulator brand coverage retained for diagnostics and tie-breaking. */
    public int localTeamBrandCount() {
        return base.teamBrandCount();
    }

    public boolean betterThan(SemiCommitmentAwarePlanEvaluation other) {
        return PREFERENCE.compare(
                this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<SemiCommitmentAwarePlanEvaluation> preference() {
        return PREFERENCE;
    }
}
