package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/** Complete-plan M10 objective over raw simulation and intent stock forecast. */
public record IntentAwarePlanEvaluation(
        PlanEvaluation base,
        IntentAdjustedCollectionScore adjustedCollectionScore,
        int forecastRealizableCollections,
        int likelyClaimedFirstCollections,
        int tieCollections,
        int unforecastedCollections) {

    private static final Comparator<IntentAwarePlanEvaluation> PREFERENCE = Comparator
            .comparingInt((IntentAwarePlanEvaluation eval) -> eval.base.teamBrandCount()).reversed()
            .thenComparing(Comparator.comparingInt(
                    (IntentAwarePlanEvaluation eval) -> eval.adjustedCollectionScore.value()).reversed())
            .thenComparing(Comparator.comparingInt(
                    IntentAwarePlanEvaluation::forecastRealizableCollections).reversed())
            .thenComparing(Comparator.comparingInt(
                    (IntentAwarePlanEvaluation eval) -> eval.base.udonTotal()).reversed())
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
        if (forecastRealizableCollections < 0 || likelyClaimedFirstCollections < 0
                || tieCollections < 0 || unforecastedCollections < 0) {
            throw new IllegalArgumentException("Intent-aware evaluation metrics must be non-negative");
        }
    }

    public boolean betterThan(IntentAwarePlanEvaluation other) {
        return PREFERENCE.compare(this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<IntentAwarePlanEvaluation> preference() {
        return PREFERENCE;
    }
}