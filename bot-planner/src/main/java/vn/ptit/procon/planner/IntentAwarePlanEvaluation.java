package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/**
 * Complete-plan M10 objective over raw simulation and intent stock forecast.
 *
 * <p>Forecast-realizable team brand coverage is the primary key. It ranks above
 * {@code base.teamBrandCount()}, which stays as a lower-priority diagnostic and
 * tie-break: a locally projected fourth brand whose only collection source is
 * forecast claimed before our arrival must not outrank a plan that keeps all four
 * brands realizable.</p>
 */
public record IntentAwarePlanEvaluation(
        PlanEvaluation base,
        IntentAdjustedCollectionScore adjustedCollectionScore,
        int forecastRealizableBrandCount,
        int forecastRealizableCollections,
        int likelyClaimedFirstCollections,
        int tieCollections,
        int unforecastedCollections) {

    private static final Comparator<IntentAwarePlanEvaluation> PREFERENCE = Comparator
            .comparingInt(
                    (IntentAwarePlanEvaluation eval) -> eval.forecastRealizableBrandCount).reversed()
            .thenComparing(Comparator.comparingInt(
                    (IntentAwarePlanEvaluation eval) -> eval.adjustedCollectionScore.value()).reversed())
            .thenComparing(Comparator.comparingInt(
                    IntentAwarePlanEvaluation::forecastRealizableCollections).reversed())
            .thenComparing(Comparator.comparingInt(
                    (IntentAwarePlanEvaluation eval) -> eval.base.udonTotal()).reversed())
            .thenComparing(Comparator.comparingInt(
                    (IntentAwarePlanEvaluation eval) -> eval.base.teamBrandCount()).reversed())
            .thenComparingInt(IntentAwarePlanEvaluation::likelyClaimedFirstCollections)
            .thenComparingInt(IntentAwarePlanEvaluation::tieCollections)
            .thenComparing(Comparator.comparingInt(
                    (IntentAwarePlanEvaluation eval) -> eval.base.activePatrolCount()).reversed())
            .thenComparing(Comparator.comparingInt(
                    (IntentAwarePlanEvaluation eval) -> eval.base.remainingFuelTotal()).reversed())
            .thenComparingInt(eval -> eval.base.movementSteps())
            .thenComparing(eval -> eval.base.deterministicSignature());

    public IntentAwarePlanEvaluation {
        Objects.requireNonNull(base, "Base plan evaluation must not be null");
        Objects.requireNonNull(adjustedCollectionScore, "Intent-adjusted score must not be null");
        if (forecastRealizableBrandCount < 0 || forecastRealizableCollections < 0
                || likelyClaimedFirstCollections < 0
                || tieCollections < 0 || unforecastedCollections < 0) {
            throw new IllegalArgumentException("Intent-aware evaluation metrics must be non-negative");
        }
        if (forecastRealizableBrandCount > base.teamBrandCount()) {
            throw new IllegalArgumentException(
                    "Forecast-realizable brands cannot exceed local simulator team brands");
        }
        if (forecastRealizableCollections > base.udonTotal()) {
            throw new IllegalArgumentException(
                    "Forecast-realizable collections cannot exceed raw simulator collections");
        }
    }

    /** Local simulator brand coverage retained for diagnostics and tie-breaking. */
    public int localTeamBrandCount() {
        return base.teamBrandCount();
    }

    public boolean betterThan(IntentAwarePlanEvaluation other) {
        return PREFERENCE.compare(this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<IntentAwarePlanEvaluation> preference() {
        return PREFERENCE;
    }
}
