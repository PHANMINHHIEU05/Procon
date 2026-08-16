package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/**
 * Contention-aware wrapper for incumbent comparison preserving primary scoreboard priorities.
 * Contention metrics act as tie-breakers ONLY after team brands and simulator-predicted Udon.
 */
public record ArrivalAwarePlanEvaluation(
        PlanEvaluation base,
        int arrivalSafeCollections,
        int arrivalTiedCollections,
        int arrivalAtRiskCollections,
        int stronglyContestedCollections) {

    private static final Comparator<ArrivalAwarePlanEvaluation> PREFERENCE = Comparator
            .comparingInt((ArrivalAwarePlanEvaluation eval) -> eval.base.teamBrandCount()).reversed()
            .thenComparing(Comparator.comparingInt((ArrivalAwarePlanEvaluation eval) -> eval.base.udonTotal()).reversed())
            .thenComparingInt(ArrivalAwarePlanEvaluation::arrivalAtRiskCollections)
            .thenComparing(Comparator.comparingInt(ArrivalAwarePlanEvaluation::arrivalSafeCollections).reversed())
            .thenComparingInt(ArrivalAwarePlanEvaluation::stronglyContestedCollections)
            .thenComparing(Comparator.comparingInt((ArrivalAwarePlanEvaluation eval) -> eval.base.activePatrolCount()).reversed())
            .thenComparing(Comparator.comparingInt((ArrivalAwarePlanEvaluation eval) -> eval.base.remainingFuelTotal()).reversed())
            .thenComparingInt(eval -> eval.base.movementSteps())
            .thenComparing(eval -> eval.base.deterministicSignature());

    public ArrivalAwarePlanEvaluation {
        Objects.requireNonNull(base, "Base plan evaluation must not be null");
        if (arrivalSafeCollections < 0 || arrivalTiedCollections < 0
                || arrivalAtRiskCollections < 0 || stronglyContestedCollections < 0) {
            throw new IllegalArgumentException("Arrival aware plan evaluation metrics must be non-negative");
        }
    }

    public boolean betterThan(ArrivalAwarePlanEvaluation other) {
        return PREFERENCE.compare(this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<ArrivalAwarePlanEvaluation> preference() {
        return PREFERENCE;
    }
}
