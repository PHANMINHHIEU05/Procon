package vn.ptit.procon.planner.v2;

import java.util.List;

/** One audited support family. Negative score fields mean that no full evaluation was available. */
public record R3RootFamilySummary(
        int serviceCount,
        boolean available,
        int rootCandidates,
        int rootsInitiallyAdmitted,
        int rootsSurvivingAfterMerge,
        int rawTerminalCandidates,
        int uniquePhysicalTerminals,
        int bestStageAOwnSemiBrands,
        int bestStageAOwnSemiCollections,
        int stageBEligiblePhysicalPlans,
        int stageBEvaluatedForFamily,
        int bestFullyEvaluatedOwnSemiBrands,
        int bestFullyEvaluatedOwnSemiCollections,
        int bestFullyEvaluatedCoupledOwnCollections,
        int bestFullyEvaluatedBaselineOpponentCollections,
        int bestFullyEvaluatedCoupledOpponentCollections,
        int bestFullyEvaluatedHybridMarginScore4,
        String bestPhysicalSignature,
        List<Integer> supportedPatrols,
        String supportSkeletonSignature) {

    public R3RootFamilySummary {
        supportedPatrols = List.copyOf(supportedPatrols);
        bestPhysicalSignature = bestPhysicalSignature == null ? "UNAVAILABLE" : bestPhysicalSignature;
        supportSkeletonSignature = supportSkeletonSignature == null ? "UNAVAILABLE" : supportSkeletonSignature;
    }

    public double physicalDedupRatio() {
        return rawTerminalCandidates == 0 ? 0.0 : 1.0 - (double) uniquePhysicalTerminals / rawTerminalCandidates;
    }
}
