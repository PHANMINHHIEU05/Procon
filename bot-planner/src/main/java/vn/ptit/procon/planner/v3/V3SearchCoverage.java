package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything Phase 2.4 asks about what the bounded V3 search actually touched: trajectory cache
 * coverage, graph expansion coverage, child generation and rejection, allocation and stock effects,
 * the raw terminal distribution, prediction fidelity and per-allocation load.
 *
 * <p>All counters come from a real observed search run, never from an estimate.
 */
public record V3SearchCoverage(int strategicEdgesAvailable, int strategicEdgesRequested,
        int uniqueStrategicEdgesRequested, int trajectoryCacheEntries, int trajectoryCacheRequests,
        int trajectoryCacheHits, int trajectoryCacheMisses, int uniqueFromPositions, int uniqueToPositions,
        int uniqueAgentContexts, double percentageOfGraphEdgesEverExplored, int retainedGraphEdges,
        int edgesEligibleFromVisitedStates, int edgesActuallyExpanded, double graphCoverageDuringSearch,
        int legalStrategicEdges, int childrenBeforeCap, int childrenAfterCap, int rejectedByFuel,
        int rejectedByTime, int rejectedByStock, int rejectedByAllocation, int rejectedBySupport,
        int rejectedByVisited, int rejectedByTrajectory, int rejectedByDedup, int rejectedByOther,
        int quotaTruncations, int edgesRejectedOnlyBecauseOutsideAssignedRegion,
        int allocationPreferredCandidates, int candidatesWithZeroStock, int candidatesAlreadyClaimed,
        int terminalCount, int ownMin, int ownMedian, int ownP90, int ownMax, int hybridMin,
        int hybridMedian, int hybridMax, int terminalsWithOwnAtLeast1, int terminalsWithOwnAtLeast5,
        int terminalsWithOwnAtLeast10, int terminalsWithOwnAtLeast14, int predictedOwnOfWinner,
        int materializedOwnOfWinner, int terminalsWithPredictionMismatch, int maxPredictedOwn,
        List<AllocationLoad> allocationLoads, Map<Integer, Integer> graphEntryPointsByPatrol,
        Map<Integer, Integer> committedTargetsByPatrol, List<String> distinctTerminalRouteVectors) {

    public V3SearchCoverage {
        allocationLoads = List.copyOf(allocationLoads);
        graphEntryPointsByPatrol = Map.copyOf(graphEntryPointsByPatrol);
        committedTargetsByPatrol = Map.copyOf(committedTargetsByPatrol);
        distinctTerminalRouteVectors = List.copyOf(distinctTerminalRouteVectors);
    }

    public boolean predictionMatchesMaterialization() { return predictedOwnOfWinner == materializedOwnOfWinner; }

    /** PART 28: low latency is never accepted as evidence of a healthy search. */
    public record AllocationLoad(String signature, String supportClass, int statesGenerated,
            int statesUnique, int statesExpanded, int terminals, int bestOwn) {
        public AllocationLoad {
            Objects.requireNonNull(signature);
            Objects.requireNonNull(supportClass);
        }
    }
}
