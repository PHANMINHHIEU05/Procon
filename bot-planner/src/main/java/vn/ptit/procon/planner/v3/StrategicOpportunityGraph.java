package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Map;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.planner.Route;

/** Immutable opportunity graph plus the route cache used by the oracle. */
public record StrategicOpportunityGraph(
        List<StrategicOpportunity> opportunities,
        Map<Position, List<OpportunityEdge>> outgoing,
        List<OpportunityRegion> regions,
        Map<AgentId, Map<Position, Route>> agentRoutes,
        Map<Position, Map<Position, Route>> opportunityRoutes,
        int graphBuildPathfindingExecutions,
        int possibleDirectedEdges,
        V3EdgeRetentionPolicy retentionPolicy) {
    public StrategicOpportunityGraph(List<StrategicOpportunity> opportunities,
            Map<Position, List<OpportunityEdge>> outgoing, List<OpportunityRegion> regions,
            Map<AgentId, Map<Position, Route>> agentRoutes,
            Map<Position, Map<Position, Route>> opportunityRoutes, int graphBuildPathfindingExecutions) {
        this(opportunities, outgoing, regions, agentRoutes, opportunityRoutes,
                graphBuildPathfindingExecutions, outgoing.values().stream().mapToInt(List::size).sum(),
                V3EdgeRetentionPolicy.CURRENT_PHASE0);
    }
    public StrategicOpportunityGraph {
        opportunities = List.copyOf(opportunities);
        regions = List.copyOf(regions);
        outgoing = Map.copyOf(outgoing);
        agentRoutes = Map.copyOf(agentRoutes);
        opportunityRoutes = Map.copyOf(opportunityRoutes);
        if (graphBuildPathfindingExecutions < 0) throw new IllegalArgumentException("Pathfinding count must be non-negative");
        if (possibleDirectedEdges < 0 || retentionPolicy == null) throw new IllegalArgumentException("Graph metrics invalid");
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
}
