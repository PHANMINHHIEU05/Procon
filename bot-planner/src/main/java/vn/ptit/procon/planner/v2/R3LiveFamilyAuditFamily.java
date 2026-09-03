package vn.ptit.procon.planner.v2;

import java.util.List;

/** One bounded, observational family-head evaluation for an opt-in R3 live shadow audit. */
public record R3LiveFamilyAuditFamily(
        int serviceCount,
        boolean available,
        Integer bestStageAOwnSemiBrands,
        Integer bestStageAOwnSemiCollections,
        boolean shadowEvaluated,
        Integer shadowOwnSemiBrands,
        Integer shadowOwnSemiCollections,
        Integer shadowCoupledOwnCollections,
        Integer shadowBaselineOpponentCollections,
        Integer shadowCoupledOpponentCollections,
        Integer shadowHybridMarginScore4,
        List<Integer> supportedPatrols,
        String supportSkeletonSignature,
        String physicalSignature,
        boolean shadowAuditSkippedForDeadline) {

    public R3LiveFamilyAuditFamily {
        supportedPatrols = List.copyOf(supportedPatrols);
        supportSkeletonSignature = supportSkeletonSignature == null ? "UNAVAILABLE" : supportSkeletonSignature;
        physicalSignature = physicalSignature == null ? "UNAVAILABLE" : physicalSignature;
    }

    static R3LiveFamilyAuditFamily unavailable(int serviceCount) {
        return new R3LiveFamilyAuditFamily(serviceCount, false, null, null, false,
                null, null, null, null, null, null, List.of(), "UNAVAILABLE", "UNAVAILABLE", false);
    }
}
