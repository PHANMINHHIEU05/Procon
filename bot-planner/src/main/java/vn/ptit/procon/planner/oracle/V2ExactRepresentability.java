package vn.ptit.procon.planner.oracle;

/** Conservative audit of whether the frozen V2 trace exposed an exact skeleton's targets. */
public record V2ExactRepresentability(int totalOptimalTransitions, int representableTransitions,
        int missingTransitions, boolean optimalTargetsPresentInV2CandidateUniverse,
        boolean optimalTransitionsRepresentable, boolean optimalSkeletonRepresentable,
        String firstMissingTransition, String reason) {
    public V2ExactRepresentability {
        if (totalOptimalTransitions < 0 || representableTransitions < 0 || missingTransitions < 0
                || firstMissingTransition == null || reason == null) throw new IllegalArgumentException("Invalid V2 audit");
    }
}
