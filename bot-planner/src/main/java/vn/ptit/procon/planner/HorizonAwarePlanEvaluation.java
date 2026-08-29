package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;

/** M13 terminal objective: M12.1 first, bounded future PATROL readiness second. */
public record HorizonAwarePlanEvaluation(
        SemiCommitmentAwarePlanEvaluation semiCommitment,
        TeamFutureReadiness futureReadiness) {

    private static final Comparator<HorizonAwarePlanEvaluation> PREFERENCE = Comparator
            .comparingInt((HorizonAwarePlanEvaluation value) ->
                    value.semiCommitment().semiCommitmentRealizableBrandCount()).reversed()
            .thenComparing(Comparator.comparingInt((HorizonAwarePlanEvaluation value) ->
                    value.semiCommitment().adjustedCollectionScore().value()).reversed())
            .thenComparing(Comparator.comparingInt((HorizonAwarePlanEvaluation value) ->
                    value.semiCommitment().semiCommitmentRealizableCollections()).reversed())
            .thenComparing(Comparator.comparingInt((HorizonAwarePlanEvaluation value) ->
                    value.futureReadiness().futureReadyPatrolCount()).reversed())
            .thenComparing(Comparator.comparingInt((HorizonAwarePlanEvaluation value) ->
                    value.futureReadiness().totalReachableOpportunityBrands()).reversed())
            .thenComparing(Comparator.comparingInt((HorizonAwarePlanEvaluation value) ->
                    value.futureReadiness().minimumPatrolReadiness()).reversed())
            .thenComparing(Comparator.comparingInt((HorizonAwarePlanEvaluation value) ->
                    value.futureReadiness().totalReachableOpportunitySpots()).reversed())
            .thenComparing(Comparator.comparingInt((HorizonAwarePlanEvaluation value) ->
                    value.semiCommitment().base().udonTotal()).reversed())
            .thenComparing(Comparator.comparingInt((HorizonAwarePlanEvaluation value) ->
                    value.semiCommitment().base().teamBrandCount()).reversed())
            .thenComparingInt(value -> value.semiCommitment().hardClaimedFirstCollections())
            .thenComparingInt(value -> value.semiCommitment().semiClaimedFirstCollections())
            .thenComparingInt(value -> value.semiCommitment().directIntentBeforeCollections())
            .thenComparingInt(value -> value.semiCommitment().tieCollections())
            .thenComparingInt(value -> value.semiCommitment().followOnIntentBeforeCollections())
            .thenComparing(Comparator.comparingInt((HorizonAwarePlanEvaluation value) ->
                    value.futureReadiness().totalProjectedPatrolFuel()).reversed())
            .thenComparingInt(value -> value.semiCommitment().base().movementSteps())
            .thenComparing(value -> value.semiCommitment().base().deterministicSignature());

    public HorizonAwarePlanEvaluation {
        Objects.requireNonNull(semiCommitment, "M12.1 evaluation must not be null");
        Objects.requireNonNull(futureReadiness, "Future readiness must not be null");
    }

    public boolean betterThan(HorizonAwarePlanEvaluation other) {
        return PREFERENCE.compare(this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public static Comparator<HorizonAwarePlanEvaluation> preference() {
        return PREFERENCE;
    }
}