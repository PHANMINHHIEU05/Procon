package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Small cache-only route transition helper used by StrategicTeamSearch. */
public final class StrategicRouteSkeletonSearch {
    public List<StrategicOpportunityGraph.OpportunityEdge> candidates(StrategicOpportunityGraph graph,
            StrategicSearchState.PatrolState patrol, StrategicAllocation allocation) {
        List<StrategicOpportunityGraph.OpportunityEdge> result = new ArrayList<>();
        if (patrol.route().isEmpty()) {
            graph.opportunities().stream().filter(o -> graph.agentRoutes().getOrDefault(patrol.patrolId(), java.util.Map.of()).containsKey(o.position()))
                    .map(o -> new StrategicOpportunityGraph.OpportunityEdge(o, o,
                            graph.agentRoutes().get(patrol.patrolId()).get(o.position()), false, true, 0))
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
