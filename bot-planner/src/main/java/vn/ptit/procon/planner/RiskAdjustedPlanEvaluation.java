package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/** Complete-plan M9.3 objective over raw simulation and weighted contention attribution. */
public record RiskAdjustedPlanEvaluation(
        PlanEvaluation base,
        ContentionAdjustedCollectionScore adjustedCollectionScore,
        int arrivalSafeCollections,
        int arrivalTiedCollections,
        int arrivalAtRiskCollections,
        int unobservedCollections,
        int stronglyContestedCollections) {

    private static final Comparator<RiskAdjustedPlanEvaluation> PREFERENCE = Comparator
            .comparingInt((RiskAdjustedPlanEvaluation eval) -> eval.base.teamBrandCount()).reversed()
            .thenComparing(Comparator.comparingInt(
                    (RiskAdjustedPlanEvaluation eval) -> eval.adjustedCollectionScore.value()).reversed())
            .thenComparing(Comparator.comparingInt(
                    (RiskAdjustedPlanEvaluation eval) -> eval.base.udonTotal()).reversed())
            .thenComparingInt(RiskAdjustedPlanEvaluation::arrivalAtRiskCollections)
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedPlanEvaluation::arrivalSafeCollections).reversed())
            .thenComparingInt(RiskAdjustedPlanEvaluation::stronglyContestedCollections)
            .thenComparing(Comparator.comparingInt(
                    (RiskAdjustedPlanEvaluation eval) -> eval.base.activePatrolCount()).reversed())
            .thenComparing(Comparator.comparingInt(
                    (RiskAdjustedPlanEvaluation eval) -> eval.base.remainingFuelTotal()).reversed())
            .thenComparingInt(eval -> eval.base.movementSteps())
            .thenComparing(eval -> eval.base.deterministicSignature());

    public RiskAdjustedPlanEvaluation {
        Objects.requireNonNull(base, "Base plan evaluation must not be null");
        Objects.requireNonNull(adjustedCollectionScore, "Adjusted collection score must not be null");
        if (arrivalSafeCollections < 0 || arrivalTiedCollections < 0
                || arrivalAtRiskCollections < 0 || unobservedCollections < 0
                || stronglyContestedCollections < 0) {
            throw new IllegalArgumentException("Risk-adjusted plan metrics must be non-negative");
        }
    }

    public boolean betterThan(RiskAdjustedPlanEvaluation other) {
        return PREFERENCE.compare(this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<RiskAdjustedPlanEvaluation> preference() {
        return PREFERENCE;
    }
}