package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/**
 * Explicit simulator-backed lexicographic value of one complete team plan.
 * {@code udonTotal} is the number of portions this plan collects during this
 * simulated day, summed across our PATROL agents. It is not a match-cumulative
 * server score and must not be compared directly with final {@code udon_total}.
 */
public record PlanEvaluation(
        int teamBrandCount,
        int udonTotal,
        int activePatrolCount,
        int remainingFuelTotal,
        int movementSteps,
        String deterministicSignature) {

    private static final Comparator<PlanEvaluation> PREFERENCE = Comparator
            .comparingInt(PlanEvaluation::teamBrandCount).reversed()
            .thenComparing(Comparator.comparingInt(PlanEvaluation::udonTotal).reversed())
            .thenComparing(Comparator.comparingInt(PlanEvaluation::activePatrolCount).reversed())
            .thenComparing(Comparator.comparingInt(PlanEvaluation::remainingFuelTotal).reversed())
            .thenComparingInt(PlanEvaluation::movementSteps)
            .thenComparing(PlanEvaluation::deterministicSignature);

    public PlanEvaluation {
        if (teamBrandCount < 0 || udonTotal < 0 || activePatrolCount < 0
                || remainingFuelTotal < 0 || movementSteps < 0) {
            throw new IllegalArgumentException("Plan evaluation metrics must be non-negative");
        }
        Objects.requireNonNull(deterministicSignature, "Plan signature must not be null");
    }

    public boolean betterThan(PlanEvaluation other) {
        return PREFERENCE.compare(this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<PlanEvaluation> preference() {
        return PREFERENCE;
    }
}
