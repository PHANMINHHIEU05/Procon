package vn.ptit.procon.planner.v3;

/** Stable counters emitted by Phase 2 benchmarks. */
public record StrategicSearchDiagnostics(int allocationsGenerated, int allocationsRetained,
        int statesGenerated, int statesUnique, int statesExpanded, int statesDeduped, int statesDominated,
        int graphEdgeExpansions, int chainMacroExpansions, int crossRegionExpansions, int stopExpansions,
        int terminalSkeletons, int materializedPlans, int validPlans, int coupledEvaluations,
        int seedOwn, int seedHybrid4, int bestOwn, int bestHybrid4, int representationOracleOwn,
        int representationOracleHybrid4, double recoveryRatio, long searchMillis, long materializationMillis,
        long coupledEvaluationMillis, boolean deadlineExceeded, int searchPathfindingExecutions,
        int uniqueTeamAllocations, int uniqueFirstTargetVectors, int uniqueRegionAssignments,
        int uniqueSupportClasses, int uniquePhysicalPrefixes, String timeToFirstImprovement,
        int childrenGenerated, int expectedPerStateChildCap, int minChildrenPerExpandedState,
        int medianChildrenPerExpandedState, int maxChildrenPerExpandedState,
        int statesIncorrectlyAffectedByGlobalCap, int totalChildrenBeforeRetention,
        int totalChildrenAfterPerStateCap, int maxFrontierSize, int trajectoryCacheEntries,
        int trajectoryCacheRequests, int trajectoryCacheHits, int trajectoryCacheMisses,
        int chronologyReplays, int chronologyEventsProcessed, long normalizationMillis,
        boolean trajectoryState, boolean perStateChildQuota) {
    public StrategicSearchDiagnostics(int allocationsGenerated, int allocationsRetained,
            int statesGenerated, int statesUnique, int statesExpanded, int statesDeduped, int statesDominated,
            int graphEdgeExpansions, int chainMacroExpansions, int crossRegionExpansions, int stopExpansions,
            int terminalSkeletons, int materializedPlans, int validPlans, int coupledEvaluations,
            int seedOwn, int seedHybrid4, int bestOwn, int bestHybrid4, int representationOracleOwn,
            int representationOracleHybrid4, double recoveryRatio, long searchMillis, long materializationMillis,
            long coupledEvaluationMillis, boolean deadlineExceeded, int searchPathfindingExecutions,
            int uniqueTeamAllocations, int uniqueFirstTargetVectors, int uniqueRegionAssignments,
            int uniqueSupportClasses, int uniquePhysicalPrefixes, String timeToFirstImprovement) {
        this(allocationsGenerated, allocationsRetained, statesGenerated, statesUnique, statesExpanded,
                statesDeduped, statesDominated, graphEdgeExpansions, chainMacroExpansions,
                crossRegionExpansions, stopExpansions, terminalSkeletons, materializedPlans, validPlans,
                coupledEvaluations, seedOwn, seedHybrid4, bestOwn, bestHybrid4, representationOracleOwn,
                representationOracleHybrid4, recoveryRatio, searchMillis, materializationMillis,
                coupledEvaluationMillis, deadlineExceeded, searchPathfindingExecutions, uniqueTeamAllocations,
                uniqueFirstTargetVectors, uniqueRegionAssignments, uniqueSupportClasses, uniquePhysicalPrefixes,
                timeToFirstImprovement, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, true, true);
    }
    public int strategicSearchPathfindingExecutions() { return searchPathfindingExecutions; }
    public boolean deadlineBudgetExceeded() { return deadlineExceeded; }
}
