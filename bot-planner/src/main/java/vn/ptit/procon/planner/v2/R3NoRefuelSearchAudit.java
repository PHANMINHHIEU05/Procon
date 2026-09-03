package vn.ptit.procon.planner.v2;

/** Evidence that the zero-service root went through the ordinary joint beam. */
public record R3NoRefuelSearchAudit(
        int rootSeedCollectionCount,
        int expandablePatrolCount,
        int reachableOpportunitiesAtRoot,
        int generatedChildrenAtFirstExpansion,
        int rawTerminalCandidates,
        int uniquePhysicalTerminals,
        int bestSemiBrands,
        int bestSemiCollections,
        int bestHybridMarginScore4,
        boolean waitOnlyTerminalExists,
        boolean waitOnlyWasBest) {

    static R3NoRefuelSearchAudit empty() {
        return new R3NoRefuelSearchAudit(0, 0, 0, 0, 0, 0, -1, -1, -1, false, false);
    }
}
