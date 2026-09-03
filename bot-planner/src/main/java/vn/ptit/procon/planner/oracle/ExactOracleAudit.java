package vn.ptit.procon.planner.oracle;

/** Phase 0.6 audit counters kept separate from the stable oracle result tuple. */
public record ExactOracleAudit(
        int seededV2Own, int seededV2Hybrid4, int seededV3Own, int seededV3Hybrid4,
        int initialOracleOwnIncumbent, int initialOracleHybridIncumbent, String incumbentSource,
        boolean incumbentPlanValid, int supportRootsGenerated, int supportRootsReplayed,
        int supportRootsAccepted, int continuationStatesExpanded, int continuationBestOwn,
        int continuationBestHybrid4, int statesWithStopBranch, int stopBranchesGenerated,
        int uniqueTerminalSkeletons, int fullyStoppedLeaves, int partiallyStoppedThenCompletedLeaves,
        int trivialUpperBound, int tightUpperBound, int statesBeforeCanonicalization,
        int statesAfterCanonicalization, int canonicalMergeCount, int agentSymmetryMerges,
        int stockOrderingMerges, int branchOrderHintedTransitions, boolean hybridUpperBoundAvailable,
        int hybridUpperBound4, String branchOrderPolicy, boolean tightBoundCertified,
        String boundSourceUsedForProof) {
    /** Compatibility constructor for the Phase 0.6 audit shape. */
    public ExactOracleAudit(int seededV2Own, int seededV2Hybrid4, int seededV3Own, int seededV3Hybrid4,
            int initialOracleOwnIncumbent, int initialOracleHybridIncumbent, String incumbentSource,
            boolean incumbentPlanValid, int supportRootsGenerated, int supportRootsReplayed,
            int supportRootsAccepted, int continuationStatesExpanded, int continuationBestOwn,
            int continuationBestHybrid4, int statesWithStopBranch, int stopBranchesGenerated,
            int uniqueTerminalSkeletons, int fullyStoppedLeaves, int partiallyStoppedThenCompletedLeaves,
            int trivialUpperBound, int tightUpperBound, int statesBeforeCanonicalization,
            int statesAfterCanonicalization, int canonicalMergeCount, int agentSymmetryMerges,
            int stockOrderingMerges, int branchOrderHintedTransitions, boolean hybridUpperBoundAvailable,
            int hybridUpperBound4, String branchOrderPolicy) {
        this(seededV2Own, seededV2Hybrid4, seededV3Own, seededV3Hybrid4,
                initialOracleOwnIncumbent, initialOracleHybridIncumbent, incumbentSource,
                incumbentPlanValid, supportRootsGenerated, supportRootsReplayed, supportRootsAccepted,
                continuationStatesExpanded, continuationBestOwn, continuationBestHybrid4,
                statesWithStopBranch, stopBranchesGenerated, uniqueTerminalSkeletons,
                fullyStoppedLeaves, partiallyStoppedThenCompletedLeaves, trivialUpperBound,
                tightUpperBound, statesBeforeCanonicalization, statesAfterCanonicalization,
                canonicalMergeCount, agentSymmetryMerges, stockOrderingMerges,
                branchOrderHintedTransitions, hybridUpperBoundAvailable, hybridUpperBound4,
                branchOrderPolicy, false, "TRIVIAL_REMAINING_STOCK");
    }

    public ExactOracleAudit {
        if (incumbentSource == null || branchOrderPolicy == null || seededV2Own < 0 || seededV3Own < 0
                || initialOracleOwnIncumbent < 0 || supportRootsGenerated < 0 || supportRootsReplayed < 0
                || supportRootsAccepted < 0 || continuationStatesExpanded < 0 || continuationBestOwn < 0
                || statesWithStopBranch < 0 || stopBranchesGenerated < 0 || uniqueTerminalSkeletons < 0
                || fullyStoppedLeaves < 0 || partiallyStoppedThenCompletedLeaves < 0 || trivialUpperBound < 0
                || tightUpperBound < 0 || statesBeforeCanonicalization < 0 || statesAfterCanonicalization < 0
                || canonicalMergeCount < 0 || agentSymmetryMerges < 0 || stockOrderingMerges < 0
                || branchOrderHintedTransitions < 0 || boundSourceUsedForProof == null) {
            throw new IllegalArgumentException("Invalid oracle audit");
        }
    }

    /** The only upper bound eligible to close an own-collection proof. */
    public int certifiedUpperBoundOwn() { return trivialUpperBound; }

    /** Route-capacity estimate retained for diagnostics; it is not a proof bound in Phase 0.7. */
    public int heuristicUpperBoundOwn() { return tightUpperBound; }
}
