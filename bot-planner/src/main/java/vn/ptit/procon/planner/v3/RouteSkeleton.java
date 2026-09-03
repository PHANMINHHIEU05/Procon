package vn.ptit.procon.planner.v3;

import java.util.List;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** Strategic route intent, deliberately not a movement action sequence. */
public record RouteSkeleton(AgentId patrolId, List<Position> orderedOpportunities,
        List<Integer> regionTransitions, int expectedCollections, int expectedBrands,
        List<StrategicTransition> transitions) {
    public RouteSkeleton(AgentId patrolId, List<Position> orderedOpportunities,
            List<Integer> regionTransitions, int expectedCollections, int expectedBrands) {
        this(patrolId, orderedOpportunities, regionTransitions, expectedCollections, expectedBrands,
                inferTransitions(orderedOpportunities));
    }

    public RouteSkeleton {
        orderedOpportunities = List.copyOf(orderedOpportunities);
        regionTransitions = List.copyOf(regionTransitions);
        transitions = List.copyOf(transitions);
    }

    private static List<StrategicTransition> inferTransitions(List<Position> positions) {
        List<StrategicTransition> result = new java.util.ArrayList<>();
        for (int i = 1; i < positions.size(); i++) result.add(StrategicTransition.edge(positions.get(i - 1), positions.get(i)));
        return result;
    }
}
