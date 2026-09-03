package vn.ptit.procon.planner.v2;

/** Per-strategic-decision accounting for one bounded V2 search. */
public record JointBeamDepthStats(
        int strategicDecisionDepth,
        int statesConsidered,
        int statesExpanded,
        int candidateAttempts,
        int infeasibleRoutes,
        int topKTruncations,
        int generatedChildren,
        int collectChildrenGenerated,
        int stopChildrenGenerated,
        int uniqueChildren,
        int duplicatesRejected,
        int dominanceRejected,
        int terminalCandidates,
        int bestSecuredCollections,
        int bestPotentialCollections,
        int minPotentialCollections,
        int maxPotentialCollections,
        int distinctPotentialValues) {
}
