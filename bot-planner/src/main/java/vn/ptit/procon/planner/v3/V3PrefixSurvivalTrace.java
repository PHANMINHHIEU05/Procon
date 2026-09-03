package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;

/**
 * PART 8 of Phase 2.4: how far the V2 strategic prefix survives inside the real bounded V3 search,
 * depth by depth, plus the PART 9 first-divergence classification.
 *
 * <p>A depth is "V2-compatible" when every patrol route in the state is a prefix of that patrol's V2
 * strategic collection sequence and the total number of commitments equals the depth.  Depth 0 is the
 * root set.
 */
public record V3PrefixSurvivalTrace(List<DepthAudit> depths, int deepestSurvivingDepth,
        int requiredDepth, int firstDivergenceDepth, V3FirstDivergence classification,
        String firstDivergenceDetail, boolean fullPrefixSurvived) {

    public V3PrefixSurvivalTrace {
        depths = List.copyOf(depths);
        Objects.requireNonNull(classification);
        Objects.requireNonNull(firstDivergenceDetail);
    }

    /**
     * One search depth.
     *
     * <p>{@code retainedAfterDominance} always equals {@code retainedAfterDedup}: the bounded V3 search
     * has no dominance pruning stage, which the audit records as a fact rather than hiding.
     */
    public record DepthAudit(int depth, String requiredDecision, int generated, int retainedAfterDedup,
            int retainedAfterDominance, int retainedAfterBeam, boolean survived, int rejectedCandidates,
            String dominantRejectionReason) {

        public DepthAudit {
            Objects.requireNonNull(requiredDecision);
            Objects.requireNonNull(dominantRejectionReason);
        }
    }
}
