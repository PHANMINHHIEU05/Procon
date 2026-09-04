package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.planner.Route;

/** Small cache-only route transition helper used by StrategicTeamSearch. */
public final class StrategicRouteSkeletonSearch {
    public List<StrategicOpportunityGraph.OpportunityEdge> candidates(StrategicOpportunityGraph graph,
            StrategicSearchState.PatrolState patrol, StrategicAllocation allocation) {
        return candidates(graph, patrol, allocation, false);
    }

    /**
     * PART 9: {@code supportAware} selects the capacity-lifted entry-route cache for the first hop.
     * It changes which geometry is offered, never whether that geometry is legal.
     */
    public List<StrategicOpportunityGraph.OpportunityEdge> candidates(StrategicOpportunityGraph graph,
            StrategicSearchState.PatrolState patrol, StrategicAllocation allocation, boolean supportAware) {
        List<StrategicOpportunityGraph.OpportunityEdge> result = new ArrayList<>();
        if (patrol.route().isEmpty()) {
            java.util.Map<Position, Route> entries = graph.entryRoutes(supportAware)
                    .getOrDefault(patrol.patrolId(), java.util.Map.of());
            graph.opportunities().stream().filter(o -> entries.containsKey(o.position()))
                    .map(o -> new StrategicOpportunityGraph.OpportunityEdge(o, o,
                            entries.get(o.position()), false, true, 0))
                    .forEach(result::add);
        } else result.addAll(graph.outgoing().getOrDefault(patrol.position(), List.of()));
        Set<Integer> preferred = new LinkedHashSet<>();
        if (patrol.route().isEmpty() && patrol.patrolId().value() < allocation.primaryTargets().size())
            preferred.addAll(allocation.primaryTargets().get(patrol.patrolId().value()));
        result.sort(Comparator.comparingInt((StrategicOpportunityGraph.OpportunityEdge e) -> preferred.contains(e.to().position().value()) ? 0 : 1)
                .thenComparing(Comparator.comparingInt((StrategicOpportunityGraph.OpportunityEdge e) -> e.to().currentStock()).reversed())
                .thenComparingInt(e -> e.route().stepsUsed())
                .thenComparingInt(e -> e.to().position().value()));
        return List.copyOf(result);
    }
}
