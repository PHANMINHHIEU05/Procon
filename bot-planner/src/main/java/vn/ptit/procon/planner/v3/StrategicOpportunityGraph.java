package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Map;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.planner.Route;

/**
 * Immutable opportunity graph plus the route cache used by the oracle.
 *
 * <p>PART 9/10: there are TWO entry-route caches, and the difference between them is the whole point.
 * {@link #agentRoutes()} is keyed on the PATROL's REAL initial fuel, so a PATROL that starts the day with
 * an empty tank has no entries at all. {@link #supportAwareAgentRoutes()} is the same Dijkstra run from
 * the same cell with a capacity-lifted synthetic PATROL, so it answers only {@code ROUTE GEOMETRY EXISTS}.
 * Because the route finder's ordering is invariant in the initial fuel, the lifted cache is a strict
 * superset that agrees with the real cache wherever the real cache has an entry. Whether such a route may
 * actually be flown is decided later, and separately, by {@link SupportAwareTrajectoryScheduler}.
 */
public record StrategicOpportunityGraph(
        List<StrategicOpportunity> opportunities,
        Map<Position, List<OpportunityEdge>> outgoing,
        List<OpportunityRegion> regions,
        Map<AgentId, Map<Position, Route>> agentRoutes,
        Map<Position, Map<Position, Route>> opportunityRoutes,
        int graphBuildPathfindingExecutions,
        int possibleDirectedEdges,
        V3EdgeRetentionPolicy retentionPolicy,
        Map<AgentId, Map<Position, Route>> supportAwareAgentRoutes) {
    public StrategicOpportunityGraph(List<StrategicOpportunity> opportunities,
            Map<Position, List<OpportunityEdge>> outgoing, List<OpportunityRegion> regions,
            Map<AgentId, Map<Position, Route>> agentRoutes,
            Map<Position, Map<Position, Route>> opportunityRoutes, int graphBuildPathfindingExecutions) {
        this(opportunities, outgoing, regions, agentRoutes, opportunityRoutes,
                graphBuildPathfindingExecutions, outgoing.values().stream().mapToInt(List::size).sum(),
                V3EdgeRetentionPolicy.CURRENT_PHASE0);
    }
    public StrategicOpportunityGraph(List<StrategicOpportunity> opportunities,
            Map<Position, List<OpportunityEdge>> outgoing, List<OpportunityRegion> regions,
            Map<AgentId, Map<Position, Route>> agentRoutes,
            Map<Position, Map<Position, Route>> opportunityRoutes, int graphBuildPathfindingExecutions,
            int possibleDirectedEdges, V3EdgeRetentionPolicy retentionPolicy) {
        this(opportunities, outgoing, regions, agentRoutes, opportunityRoutes,
                graphBuildPathfindingExecutions, possibleDirectedEdges, retentionPolicy, agentRoutes);
    }
    public StrategicOpportunityGraph {
        opportunities = List.copyOf(opportunities);
        regions = List.copyOf(regions);
        outgoing = Map.copyOf(outgoing);
        agentRoutes = Map.copyOf(agentRoutes);
        opportunityRoutes = Map.copyOf(opportunityRoutes);
        supportAwareAgentRoutes = Map.copyOf(supportAwareAgentRoutes);
        if (graphBuildPathfindingExecutions < 0) throw new IllegalArgumentException("Pathfinding count must be non-negative");
        if (possibleDirectedEdges < 0 || retentionPolicy == null) throw new IllegalArgumentException("Graph metrics invalid");
    }

    /**
     * The entry-route cache a search should read.
     *
     * <p>PART 8: with no support root the historical, fuel-filtered cache is used unchanged, so NO_REFUEL
     * behaviour cannot move. PART 9: with a support root the lifted geometry is used instead.
     */
    public Map<AgentId, Map<Position, Route>> entryRoutes(boolean supportAware) {
        return supportAware ? supportAwareAgentRoutes : agentRoutes;
    }

    public record OpportunityEdge(StrategicOpportunity from, StrategicOpportunity to, Route route,
            boolean sameBrand, boolean differentBrand, int estimatedRemainingBudgetImpact,
            StrategicEdgeCategory category) {
        public OpportunityEdge(StrategicOpportunity from, StrategicOpportunity to, Route route,
                boolean sameBrand, boolean differentBrand, int estimatedRemainingBudgetImpact) {
            this(from, to, route, sameBrand, differentBrand, estimatedRemainingBudgetImpact,
                    differentBrand ? StrategicEdgeCategory.MISSING_BRAND : StrategicEdgeCategory.LOCAL);
        }
        public OpportunityEdge {
            if (from == null || to == null || route == null || category == null) throw new IllegalArgumentException("Edge fields must not be null");
        }
    }

    public List<OpportunityEdge> allEdges() {
        return outgoing.values().stream().flatMap(List::stream).toList();
    }

    public int retainedStrategicEdges() { return allEdges().size(); }

    public double averageOutgoingEdges() {
        return opportunities.isEmpty() ? 0.0 : (double) retainedStrategicEdges() / opportunities.size();
    }

    public int maxOutgoingEdges() {
        return outgoing.values().stream().mapToInt(List::size).max().orElse(0);
    }

    public long localEdges() { return allEdges().stream().filter(e -> e.category() == StrategicEdgeCategory.LOCAL).count(); }
    public long crossRegionEdges() { return allEdges().stream().filter(e -> e.category() == StrategicEdgeCategory.DIFFERENT_REGION
            || e.category() == StrategicEdgeCategory.LONG_HOP_FRESH_REGION).count(); }
    public long longHopEdges() { return allEdges().stream().filter(e -> e.category() == StrategicEdgeCategory.LONG_HOP_FRESH_REGION).count(); }

    /** PART 23/30: how many opportunities each PATROL can enter the graph at, per entry-route cache. */
    public Map<AgentId, Integer> entryPointsByPatrol(boolean supportAware) {
        Map<AgentId, Integer> result = new java.util.LinkedHashMap<>();
        entryRoutes(supportAware).forEach((id, byGoal) -> result.put(id, byGoal.size()));
        return Map.copyOf(result);
    }
}
