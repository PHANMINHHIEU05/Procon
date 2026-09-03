package vn.ptit.procon.planner.v3;

import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * Benchmark-only observation hook for the bounded V3 search.
 *
 * <p>Every method is a no-op by default and the search never reads anything back from an observer, so
 * observing a run cannot change its outcome.  This exists because the Phase 2.4 prefix-survival,
 * child-generation and graph-coverage audits must report what the real search did rather than what a
 * re-implementation of it would do.
 */
public interface StrategicSearchObserver {

    StrategicSearchObserver NONE = new StrategicSearchObserver() { };

    default void onRoot(StrategicAllocation allocation, StrategicSearchNode node, boolean unique) { }

    default void onFrontier(int iteration, int frontierSize, int beamWidth) { }

    default void onExpanded(StrategicSearchNode node) { }

    default void onEdgeCandidate(int depth, AgentId patrolId, Position from, Position to,
            boolean allocationPreferred) { }

    default void onChildRejected(int depth, AgentId patrolId, Position from, Position to, String reason) { }

    default void onChildAccepted(int depth, AgentId patrolId, Position from, Position to,
            StrategicSearchState child) { }

    default void onStopChild(int depth, AgentId patrolId, boolean unique) { }

    default void onQuotaReached(int depth, AgentId patrolId, int skippedCandidates) { }

    default void onBeamRetained(int iteration, java.util.List<StrategicSearchNode> candidates,
            java.util.List<StrategicSearchNode> retained) { }

    default void onTerminal(StrategicSearchNode node, boolean materialized, boolean valid,
            StrategicOracleEvaluation evaluation) { }
}
