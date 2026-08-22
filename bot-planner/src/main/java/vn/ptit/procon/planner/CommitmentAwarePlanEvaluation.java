package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/**
 * Complete-plan M12 objective over raw simulation and the commitment-classified forecast.
 *
 * <p>Separate from {@link IntentAwarePlanEvaluation} on purpose: the M10 ordering stays exactly as
 * the shipped modes rely on it, while this record ranks plans by what survives <em>hard</em>
 * forecast depletion. Future intent has become an ordering signal (keys 7 to 9) rather than a veto,
 * which is the whole point of M12.</p>
 *
 * <p>{@code oldForecastRealizableCollections} rides along for calibration only and never
 * participates in the ordering.</p>
 */
public record CommitmentAwarePlanEvaluation(
        PlanEvaluation base,
        CommitmentAdjustedCollectionScore adjustedCollectionScore,
        int commitmentRealizableBrandCount,
        int commitmentRealizableCollections,
        int oldForecastRealizableCollections,
        int hardClaimedFirstCollections,
        int directIntentBeforeCollections,
        int followOnIntentBeforeCollections,
        int tieCollections,
        int unforecastedCollections) {

    private static final Comparator<CommitmentAwarePlanEvaluation> PREFERENCE = Comparator
            .comparingInt(
                    (CommitmentAwarePlanEvaluation eval) -> eval.commitmentRealizableBrandCount)
            .reversed()
            .thenComparing(Comparator.comparingInt(
                    (CommitmentAwarePlanEvaluation eval) -> eval.adjustedCollectionScore.value())
                    .reversed())
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwarePlanEvaluation::commitmentRealizableCollections).reversed())
            .thenComparing(Comparator.comparingInt(
                    (CommitmentAwarePlanEvaluation eval) -> eval.base.udonTotal()).reversed())
            .thenComparing(Comparator.comparingInt(
                    (CommitmentAwarePlanEvaluation eval) -> eval.base.teamBrandCount()).reversed())
            .thenComparingInt(CommitmentAwarePlanEvaluation::hardClaimedFirstCollections)
            .thenComparingInt(CommitmentAwarePlanEvaluation::directIntentBeforeCollections)
            .thenComparingInt(CommitmentAwarePlanEvaluation::tieCollections)
            .thenComparingInt(CommitmentAwarePlanEvaluation::followOnIntentBeforeCollections)
            .thenComparing(Comparator.comparingInt(
                    (CommitmentAwarePlanEvaluation eval) -> eval.base.activePatrolCount()).reversed())
            .thenComparing(Comparator.comparingInt(
                    (CommitmentAwarePlanEvaluation eval) -> eval.base.remainingFuelTotal()).reversed())
            .thenComparingInt(eval -> eval.base.movementSteps())
            .thenComparing(eval -> eval.base.deterministicSignature());

    public CommitmentAwarePlanEvaluation {
        Objects.requireNonNull(base, "Base plan evaluation must not be null");
        Objects.requireNonNull(adjustedCollectionScore, "Commitment-adjusted score must not be null");
        if (commitmentRealizableBrandCount < 0 || commitmentRealizableCollections < 0
                || oldForecastRealizableCollections < 0 || hardClaimedFirstCollections < 0
                || directIntentBeforeCollections < 0 || followOnIntentBeforeCollections < 0
                || tieCollections < 0 || unforecastedCollections < 0) {
            throw new IllegalArgumentException(
                    "Commitment-aware evaluation metrics must be non-negative");
        }
        if (commitmentRealizableBrandCount > base.teamBrandCount()) {
            throw new IllegalArgumentException(
                    "Commitment-realizable brands cannot exceed local simulator team brands");
        }
        if (commitmentRealizableCollections > base.udonTotal()) {
            throw new IllegalArgumentException(
                    "Commitment-realizable collections cannot exceed raw simulator collections");
        }
        if (oldForecastRealizableCollections > commitmentRealizableCollections) {
            throw new IllegalArgumentException(
                    "M10 forecast-realizable collections cannot exceed the M12 commitment-realizable"
                            + " count for the same plan and forecast");
        }
    }

    /** Local simulator brand coverage retained for diagnostics and tie-breaking. */
    public int localTeamBrandCount() {
        return base.teamBrandCount();
    }

    public boolean betterThan(CommitmentAwarePlanEvaluation other) {
        return PREFERENCE.compare(
                this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<CommitmentAwarePlanEvaluation> preference() {
        return PREFERENCE;
    }
}
