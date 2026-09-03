package vn.ptit.procon.planner.v3;

/** Work and coverage counters for one benchmark-only oracle run. */
public record V3Phase0Diagnostics(int collectibleOpportunityCount, int opportunitiesAssignedToRegions,
        int unassignedOpportunities, int regionCount, int generatedChains, int retainedChains,
        int dominatedChainsRemoved, int opportunitiesRepresentedInAnyChain, int opportunitiesNeverRepresented,
        double coverageRatio, int allocationsConsidered, int allocationsMaterialized, int allocationsValid,
        boolean oracleCapReached, int graphBuildPathfindingExecutions, int oracleSearchPathfindingExecutions,
        int materializationPathfindingExecutions, long graphBuildMillis, long chainGenerationMillis,
        long allocationEnumerationMillis, long materializationMillis, long evaluationMillis, long totalOracleMillis) {
    public V3Phase0Diagnostics {
        if (collectibleOpportunityCount < 0 || opportunitiesAssignedToRegions < 0 || unassignedOpportunities < 0
                || regionCount < 0 || generatedChains < 0 || retainedChains < 0 || dominatedChainsRemoved < 0
                || opportunitiesRepresentedInAnyChain < 0 || opportunitiesNeverRepresented < 0
                || allocationsConsidered < 0 || allocationsMaterialized < 0 || allocationsValid < 0
                || graphBuildPathfindingExecutions < 0 || oracleSearchPathfindingExecutions != 0
                || materializationPathfindingExecutions < 0) throw new IllegalArgumentException("V3 diagnostics invalid");
    }
}
