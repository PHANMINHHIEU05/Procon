package vn.ptit.procon.planner.v3;

/** Auditable work counters for one Phase 1 benchmark run. */
public record V3RepresentationDiagnostics(int opportunityCount, int possibleDirectedEdges,
        int retainedEdges, double averageOutgoingEdges, int maxOutgoingEdges, int localEdges,
        int crossRegionEdges, int longHopEdges, int generatedPaths, int retainedPaths,
        int teamCandidates, int materializedPlans, int validPlans, int nodeCoverage,
        int exactEdgeCount, int exactEdgeCovered, int regionTransitionCount, int regionTransitionsCovered,
        int chainMacroCount, long graphBuildMillis, long regionBuildMillis, long edgePortfolioMillis,
        long chainMacroMillis, long representationOracleMillis, int representationSearchPathfindingExecutions) {
    public V3RepresentationDiagnostics {
        if (opportunityCount < 0 || possibleDirectedEdges < 0 || retainedEdges < 0 || generatedPaths < 0
                || retainedPaths < 0 || teamCandidates < 0 || materializedPlans < 0 || validPlans < 0
                || nodeCoverage < 0 || exactEdgeCount < 0 || exactEdgeCovered < 0 || regionTransitionCount < 0
                || regionTransitionsCovered < 0 || chainMacroCount < 0 || representationSearchPathfindingExecutions != 0) {
            throw new IllegalArgumentException("Representation diagnostics invalid");
        }
    }

    public double exactEdgeCoverage() { return exactEdgeCount == 0 ? 1.0 : (double) exactEdgeCovered / exactEdgeCount; }
    public double regionTransitionCoverage() { return regionTransitionCount == 0 ? 1.0 : (double) regionTransitionsCovered / regionTransitionCount; }
}
