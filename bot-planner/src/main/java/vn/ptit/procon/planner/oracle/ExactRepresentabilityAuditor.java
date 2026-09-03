package vn.ptit.procon.planner.oracle;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.v2.JointTeamBeamConfig;
import vn.ptit.procon.planner.v2.JointTeamBeamPlanner;
import vn.ptit.procon.planner.v2.V2CollectionAuditMode;
import vn.ptit.procon.planner.v2.V2CollectionStateTrace;
import vn.ptit.procon.planner.v2.V2SearchPolicy;
import vn.ptit.procon.planner.v3.StrategicOpportunityGraph;
import vn.ptit.procon.planner.v3.StrategicOracleResult;

/** Benchmark-only representation audit; it never participates in selection or pruning. */
public final class ExactRepresentabilityAuditor {
    public V2ExactRepresentability auditV2(DayState state, OptimalCollectionSkeleton skeleton) {
        var result = new JointTeamBeamPlanner(new JointTeamBeamConfig(48, 64, 24, 4, 16,
                V2SearchPolicy.R1_CONTROL, V2CollectionAuditMode.TRACE)).planWithStats(state);
        Set<Integer> exposed = result.collectionAudit().states().stream()
                .flatMap(trace -> parseMenu(trace).stream()).collect(Collectors.toSet());
        List<Integer> optimal = skeleton.agents().stream().flatMap(agent -> agent.visits().stream())
                .map(OptimalCollectionSkeleton.Visit::position).distinct().toList();
        int present = (int) optimal.stream().filter(exposed::contains).count();
        int missing = optimal.size() - present;
        String first = optimal.stream().filter(value -> !exposed.contains(value)).findFirst()
                .map(value -> "target=" + value).orElse("NONE");
        boolean all = missing == 0;
        return new V2ExactRepresentability(optimal.size(), present, missing, all, all, all, first,
                all ? "NONE_OR_SEARCH_ONLY" : "TARGET_NEVER_GENERATED");
    }

    public V3ExactRepresentability auditV3(StrategicOracleResult v3, OptimalCollectionSkeleton skeleton) {
        StrategicOpportunityGraph graph = v3.graph();
        Set<Integer> graphPositions = graph.opportunities().stream().map(value -> value.position().value()).collect(Collectors.toSet());
        List<Integer> optimal = skeleton.agents().stream().flatMap(agent -> agent.visits().stream())
                .map(OptimalCollectionSkeleton.Visit::position).distinct().toList();
        int inGraph = (int) optimal.stream().filter(graphPositions::contains).count();
        Set<String> graphEdges = new HashSet<>();
        graph.outgoing().values().stream().flatMap(List::stream)
                .forEach(edge -> graphEdges.add(edge.from().position().value() + ">" + edge.to().position().value()));
        Set<String> chainEdges = v3.chains().stream().flatMap(chain -> java.util.stream.IntStream.range(1, chain.opportunities().size())
                .mapToObj(i -> chain.opportunities().get(i - 1).position().value() + ">" + chain.opportunities().get(i).position().value()))
                .collect(Collectors.toSet());
        List<String> transitions = skeleton.agents().stream().flatMap(agent -> java.util.stream.IntStream.range(1, agent.visits().size())
                .mapToObj(i -> agent.visits().get(i - 1).position() + ">" + agent.visits().get(i).position())).toList();
        int edges = (int) transitions.stream().filter(graphEdges::contains).count();
        int chain = (int) transitions.stream().filter(chainEdges::contains).count();
        boolean region = edges == transitions.size();
        boolean allocation = inGraph == optimal.size() && v3.chains().stream().anyMatch(value ->
                value.opportunities().stream().map(item -> item.position().value()).anyMatch(optimal::contains));
        String missing = transitions.stream().filter(value -> !chainEdges.contains(value)).findFirst()
                .orElseGet(() -> optimal.stream().filter(value -> !graphPositions.contains(value)).map(value -> "target=" + value).findFirst().orElse("NONE"));
        boolean represented = inGraph == optimal.size() && chain == transitions.size() && allocation;
        return new V3ExactRepresentability(inGraph, edges, chain, region, allocation, represented, missing,
                represented ? "NONE_OR_SEARCH_ONLY" : "CHAIN_OR_GRAPH_ELEMENT_MISSING");
    }

    private static Set<Integer> parseMenu(V2CollectionStateTrace trace) {
        Set<Integer> result = new HashSet<>();
        for (String agent : trace.targetMenu().split("\\|")) {
            int colon = agent.indexOf(':');
            if (colon < 0) continue;
            for (String token : agent.substring(colon + 1).split(",")) {
                try { result.add(Integer.parseInt(token)); } catch (NumberFormatException ignored) { }
            }
        }
        return result;
    }
}
