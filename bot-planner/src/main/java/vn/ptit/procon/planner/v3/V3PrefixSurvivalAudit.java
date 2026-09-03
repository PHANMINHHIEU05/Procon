package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * PART 8 and PART 9 of Phase 2.4, computed from a recorded search only.
 *
 * <p>Depth here counts committed strategic collections, not {@link StrategicSearchNode#depth()}: a STOP
 * transition advances the node depth without adding a collection, so node depth cannot be compared
 * against the V2 skeleton.  A state is V2-compatible at depth {@code d} when every patrol route is a
 * prefix of that patrol's V2 collection sequence and the routes hold {@code d} collections in total.
 */
public final class V3PrefixSurvivalAudit {

    public V3PrefixSurvivalTrace audit(V2StrategicWitness witness, V3ObservedSearch observed,
            V3BaselineRepresentability representability, StrategicSearchResult result,
            StrategicSearchConfig config) {
        Map<Integer, List<Position>> required = new LinkedHashMap<>();
        witness.patrols().forEach(patrol -> required.put(patrol.patrolId().value(),
                patrol.orderedStrategicCollections()));
        int requiredDepth = required.values().stream().mapToInt(List::size).sum();

        Map<Integer, Set<String>> generated = new LinkedHashMap<>();
        Map<Integer, Set<String>> afterDedup = new LinkedHashMap<>();
        Map<Integer, Set<String>> afterBeam = new LinkedHashMap<>();
        Map<Integer, List<String>> rejectedReasons = new LinkedHashMap<>();

        afterBeam.put(0, observed.uniqueRoots().stream().map(node -> key(node.state()))
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        generated.put(0, afterBeam.get(0));
        afterDedup.put(0, afterBeam.get(0));

        for (V3ObservedSearch.Accepted accepted : observed.accepted()) {
            if (!compatible(required, accepted.child())) continue;
            int depth = commitments(accepted.child());
            generated.computeIfAbsent(depth, key -> new LinkedHashSet<>()).add(key(accepted.child()));
            afterDedup.computeIfAbsent(depth, key -> new LinkedHashSet<>()).add(key(accepted.child()));
        }
        for (V3ObservedSearch.Rejection rejection : observed.rejections()) {
            if (rejection.parent() == null) continue;
            Map<Integer, List<Position>> projected = project(rejection);
            if (!prefixOf(required, projected)) continue;
            int depth = projected.values().stream().mapToInt(List::size).sum();
            if (rejection.reason().equals("DEDUP")) {
                generated.computeIfAbsent(depth, key -> new LinkedHashSet<>()).add(render(projected));
            } else {
                rejectedReasons.computeIfAbsent(depth, key -> new ArrayList<>()).add(rejection.reason());
            }
        }
        observed.beamByIteration().values().forEach(retained -> retained.stream()
                .filter(node -> compatible(required, node.state()))
                .forEach(node -> afterBeam.computeIfAbsent(commitments(node.state()),
                        key -> new LinkedHashSet<>()).add(key(node.state()))));

        List<V3PrefixSurvivalTrace.DepthAudit> depths = new ArrayList<>();
        int deepestSurviving = -1;
        int firstDivergence = -1;
        for (int depth = 0; depth <= requiredDepth; depth++) {
            int gen = generated.getOrDefault(depth, Set.of()).size();
            int dedup = afterDedup.getOrDefault(depth, Set.of()).size();
            int beam = afterBeam.getOrDefault(depth, Set.of()).size();
            List<String> reasons = rejectedReasons.getOrDefault(depth, List.of());
            boolean survived = beam > 0;
            depths.add(new V3PrefixSurvivalTrace.DepthAudit(depth, decision(required, depth), gen, dedup,
                    dedup, beam, survived, reasons.size(), dominant(reasons)));
            if (survived) deepestSurviving = depth;
            else if (firstDivergence < 0) firstDivergence = depth;
        }
        V3FirstDivergence classification = classify(representability, depths, firstDivergence, requiredDepth,
                observed, result, config, required);
        return new V3PrefixSurvivalTrace(depths, deepestSurviving, requiredDepth,
                firstDivergence < 0 ? requiredDepth + 1 : firstDivergence, classification,
                detail(representability, depths, firstDivergence, required), firstDivergence < 0);
    }

    private static int commitments(StrategicSearchState state) {
        return state.patrols().stream().mapToInt(patrol -> patrol.route().size()).sum();
    }

    private static boolean compatible(Map<Integer, List<Position>> required, StrategicSearchState state) {
        for (StrategicSearchState.PatrolState patrol : state.patrols()) {
            List<Position> target = required.getOrDefault(patrol.patrolId().value(), List.of());
            List<Position> route = patrol.route();
            if (route.size() > target.size()) return false;
            for (int index = 0; index < route.size(); index++) {
                if (!route.get(index).equals(target.get(index))) return false;
            }
        }
        return true;
    }

    private static boolean prefixOf(Map<Integer, List<Position>> required, Map<Integer, List<Position>> routes) {
        for (Map.Entry<Integer, List<Position>> entry : routes.entrySet()) {
            List<Position> target = required.getOrDefault(entry.getKey(), List.of());
            if (entry.getValue().size() > target.size()) return false;
            for (int index = 0; index < entry.getValue().size(); index++) {
                if (!entry.getValue().get(index).equals(target.get(index))) return false;
            }
        }
        return true;
    }

    private static Map<Integer, List<Position>> project(V3ObservedSearch.Rejection rejection) {
        Map<Integer, List<Position>> routes = new LinkedHashMap<>();
        for (StrategicSearchState.PatrolState patrol : rejection.parent().state().patrols()) {
            List<Position> route = new ArrayList<>(patrol.route());
            if (patrol.patrolId().equals(rejection.patrolId())) route.add(rejection.to());
            routes.put(patrol.patrolId().value(), route);
        }
        return routes;
    }

    private static String key(StrategicSearchState state) {
        return state.patrols().stream()
                .map(patrol -> patrol.patrolId().value() + ":" + patrol.route().stream()
                        .map(position -> Integer.toString(position.value())).collect(Collectors.joining(",")))
                .collect(Collectors.joining("/"));
    }

    private static String render(Map<Integer, List<Position>> routes) {
        return routes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue().stream()
                        .map(position -> Integer.toString(position.value())).collect(Collectors.joining(",")))
                .collect(Collectors.joining("/"));
    }

    private static String decision(Map<Integer, List<Position>> required, int depth) {
        if (depth == 0) return "ROOT";
        List<String> options = new ArrayList<>();
        required.forEach((patrolId, targets) -> {
            for (int index = 0; index < Math.min(depth, targets.size()); index++) {
                options.add("p" + patrolId + "#" + index + "->" + targets.get(index).value());
            }
        });
        return options.isEmpty() ? "NONE" : String.join(" | ", options);
    }

    private static String dominant(List<String> reasons) {
        return reasons.stream().collect(Collectors.groupingBy(value -> value, Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).findFirst().orElse("NONE");
    }

    private static V3FirstDivergence classify(V3BaselineRepresentability representability,
            List<V3PrefixSurvivalTrace.DepthAudit> depths, int firstDivergence, int requiredDepth,
            V3ObservedSearch observed, StrategicSearchResult result, StrategicSearchConfig config,
            Map<Integer, List<Position>> required) {
        if (firstDivergence < 0) {
            if (observed.terminals().stream().noneMatch(V3ObservedSearch.Terminal::materialized)) {
                return V3FirstDivergence.MATERIALIZATION;
            }
            boolean winnerIsV2 = compatible(required, result.winningNode().state())
                    && commitments(result.winningNode().state()) == requiredDepth;
            return winnerIsV2 ? V3FirstDivergence.NO_DIVERGENCE : V3FirstDivergence.TERMINAL_EVALUATION;
        }
        if (firstDivergence == 0) {
            return representability.supportRoot().rootGeneratedByV3() ? V3FirstDivergence.ALLOCATION_RETENTION
                    : V3FirstDivergence.ALLOCATION_GENERATION;
        }
        if (!representability.fullyRepresentable()) {
            return switch (representability.firstMissingReason()) {
                case SUPPORT_ROOT_MISSING -> V3FirstDivergence.SUPPORT_ROOT;
                case POST_SUPPORT_STATE_MISMATCH -> V3FirstDivergence.POST_SUPPORT_STATE;
                case NODE_MISSING, EDGE_MISSING, EDGE_PORTFOLIO_PRUNED, TRAJECTORY_ROUTE_MISMATCH ->
                        V3FirstDivergence.REPRESENTATION_EDGE;
                case REPRESENTED, OTHER_PROVEN -> V3FirstDivergence.OTHER_PROVEN;
            };
        }
        V3PrefixSurvivalTrace.DepthAudit audit = depths.get(firstDivergence);
        if (audit.generated() > 0) return V3FirstDivergence.BEAM;
        if (audit.retainedAfterDedup() == 0 && audit.rejectedCandidates() > 0) {
            return switch (audit.dominantRejectionReason()) {
                case "FUEL", "TIME" -> V3FirstDivergence.POST_SUPPORT_STATE;
                case "TRAJECTORY_UNAVAILABLE" -> V3FirstDivergence.REPRESENTATION_EDGE;
                case "VISITED_ROUTE" -> V3FirstDivergence.PARTIAL_ORDERING;
                case "DEDUP" -> V3FirstDivergence.DEDUP;
                default -> V3FirstDivergence.OTHER_PROVEN;
            };
        }
        if (observed.expandedNodes().size() >= config.maxStrategicExpandedStates()) {
            return V3FirstDivergence.EXPANDED_CAP;
        }
        return V3FirstDivergence.CHILD_GENERATION;
    }

    private static String detail(V3BaselineRepresentability representability,
            List<V3PrefixSurvivalTrace.DepthAudit> depths, int firstDivergence,
            Map<Integer, List<Position>> required) {
        if (firstDivergence < 0) return "FULL_V2_PREFIX_SURVIVED";
        V3PrefixSurvivalTrace.DepthAudit audit = depths.get(firstDivergence);
        return "depth=" + firstDivergence + " required=" + audit.requiredDecision()
                + " generated=" + audit.generated() + " retainedAfterBeam=" + audit.retainedAfterBeam()
                + " rejected=" + audit.rejectedCandidates() + "/" + audit.dominantRejectionReason()
                + " representability=" + representability.firstMissingReason()
                + "@" + representability.firstMissingTransition();
    }

    /** Ordered patrol ids the V2 witness needs to move, for report rendering. */
    public static List<Integer> movingPatrols(V2StrategicWitness witness) {
        return witness.patrols().stream().filter(patrol -> !patrol.orderedStrategicCollections().isEmpty())
                .map(V2StrategicWitness.PatrolSkeleton::patrolId).map(AgentId::value)
                .sorted(Comparator.naturalOrder()).toList();
    }
}
