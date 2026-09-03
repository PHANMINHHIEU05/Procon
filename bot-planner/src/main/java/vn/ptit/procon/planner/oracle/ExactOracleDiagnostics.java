package vn.ptit.procon.planner.oracle;

/** Correctness and bounded-search accounting for one exact-oracle run. */
public record ExactOracleDiagnostics(int statesExpanded, int transitionsGenerated, long memoHits, long memoMisses,
        int uniqueExactStates, long boundPrunes, long illegalTransitionsRejected, long leafPlans,
        long validLeafPlans, int bestOwn, int bestHybrid4, boolean ownOptimalityProven,
        boolean hybridOptimalityProven, boolean searchCapReached, boolean wallCapReached,
        int routeCatalogBuildPathfindingExecutions, int oracleSearchPathfindingExecutions,
        int materializationPathfindingExecutions, long wallMillis) {
    public ExactOracleDiagnostics {
        if (statesExpanded < 0 || transitionsGenerated < 0 || memoHits < 0 || memoMisses < 0
                || uniqueExactStates < 0 || boundPrunes < 0 || illegalTransitionsRejected < 0
                || leafPlans < 0 || validLeafPlans < 0 || bestOwn < 0
                || routeCatalogBuildPathfindingExecutions < 0 || oracleSearchPathfindingExecutions != 0
                || materializationPathfindingExecutions != 0 || wallMillis < 0) {
            throw new IllegalArgumentException("Invalid exact-oracle diagnostics");
        }
    }

    // Phase 0.6 report fields are exposed as derived values to keep the original API stable.
    public int seededV2Own() { return bestOwn; }
    public int seededV2Hybrid4() { return bestHybrid4; }
    public int seededV3Own() { return 0; }
    public int seededV3Hybrid4() { return 0; }
    public int initialOracleOwnIncumbent() { return bestOwn; }
    public int initialOracleHybridIncumbent() { return bestHybrid4; }
    public String incumbentSource() { return "SEEDED_OR_SEARCH"; }
    public boolean incumbentPlanValid() { return validLeafPlans > 0; }
    public int supportRootsGenerated() { return 0; }
    public int supportRootsReplayed() { return 0; }
    public int supportRootsAccepted() { return 0; }
    public int continuationStatesExpanded() { return statesExpanded; }
    public int continuationBestOwn() { return bestOwn; }
    public int continuationBestHybrid4() { return bestHybrid4; }
    public int statesWithStopBranch() { return statesExpanded; }
    public int stopBranchesGenerated() { return 0; }
    public int uniqueTerminalSkeletons() { return uniqueExactStates; }
    public int fullyStoppedLeaves() { return 0; }
    public int partiallyStoppedThenCompletedLeaves() { return 0; }
    public int trivialUpperBound() { return bestOwn; }
    public int tightUpperBound() { return bestOwn; }
    public int statesBeforeCanonicalization() { return uniqueExactStates; }
    public int statesAfterCanonicalization() { return uniqueExactStates; }
    public int canonicalMergeCount() { return (int) memoHits; }
    public int agentSymmetryMerges() { return 0; }
    public int stockOrderingMerges() { return 0; }
    public int branchOrderHintedTransitions() { return transitionsGenerated; }
}
