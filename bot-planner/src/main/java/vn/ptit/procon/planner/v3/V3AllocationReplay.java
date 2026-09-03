package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PART 6 and PART 7 of Phase 2.4: whether the allocation the V2 witness semantically needs exists in
 * V3's generated portfolio, and how every retained allocation trades V2 coverage against search load.
 *
 * <p>V3 allocations are a sort preference inside {@link StrategicRouteSkeletonSearch}, never a hard
 * constraint, so a missing allocation is reported as a missing *bias*, not as a legality barrier.  The
 * support dimension is different: V3's allocation vocabulary has no refuel-root axis at all, which is
 * recorded verbatim in {@link #generatedSupportClasses()}.
 */
public record V3AllocationReplay(Map<Integer, List<Integer>> requiredPrimaryTargetVector,
        Map<Integer, List<String>> requiredPrimaryRegionVector,
        Map<Integer, List<String>> requiredSecondaryRegionVector, String sharedRegionPattern,
        String requiredSupportClass, List<String> generatedSupportClasses, boolean supportClassRepresented,
        boolean allocationGenerated, boolean allocationRetained, int allocationRank,
        boolean exactAllocationGenerated, String bestCoveringSignature, double bestCoverageRatio,
        int allocationsGenerated, int allocationsRetained, String missingAllocationClassification,
        List<AllocationScore> scores) {

    public V3AllocationReplay {
        requiredPrimaryTargetVector = Map.copyOf(requiredPrimaryTargetVector);
        requiredPrimaryRegionVector = Map.copyOf(requiredPrimaryRegionVector);
        requiredSecondaryRegionVector = Map.copyOf(requiredSecondaryRegionVector);
        generatedSupportClasses = List.copyOf(generatedSupportClasses);
        scores = List.copyOf(scores);
        Objects.requireNonNull(sharedRegionPattern);
        Objects.requireNonNull(requiredSupportClass);
        Objects.requireNonNull(bestCoveringSignature);
        Objects.requireNonNull(missingAllocationClassification);
    }

    /** PART 7: allocation coverage against what that allocation actually bought inside the search. */
    public record AllocationScore(String signature, String supportClass, int v2TargetsCovered,
            int v2TargetsRequired, double coverageRatio, int statesGenerated, int statesExpanded,
            int terminals, int bestOwn) {

        public AllocationScore {
            Objects.requireNonNull(signature);
            Objects.requireNonNull(supportClass);
        }
    }
}
