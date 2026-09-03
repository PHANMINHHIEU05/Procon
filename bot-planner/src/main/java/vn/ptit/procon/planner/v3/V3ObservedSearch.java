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
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/**
 * Records one real bounded V3 search through {@link StrategicSearchObserver} and turns the recording
 * into the Phase 2.4 coverage counters.
 *
 * <p>Nothing here re-implements the search: the numbers are what the search itself reported while it
 * ran, which is why they can be used as causal evidence.
 */
public final class V3ObservedSearch implements StrategicSearchObserver {

    private final List<StrategicAllocation> roots = new ArrayList<>();
    private final List<StrategicSearchNode> uniqueRoots = new ArrayList<>();
    private final List<StrategicSearchNode> expandedNodes = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private final List<Rejection> rejections = new ArrayList<>();
    private final List<Accepted> accepted = new ArrayList<>();
    private final Map<Integer, List<StrategicSearchNode>> beamByIteration = new LinkedHashMap<>();
    private final Map<Integer, List<StrategicSearchNode>> candidateSetByIteration = new LinkedHashMap<>();
    private final List<Terminal> terminals = new ArrayList<>();
    private StrategicSearchNode currentNode;
    private int stopChildren;
    private int uniqueStopChildren;
    private int quotaTruncations;
    private int quotaSkippedCandidates;

    /** Runs an observed search; the observer cannot change the outcome. */
    public static Observation run(DayState state, StrategicSearchConfig config, List<TeamPlan> seeds) {
        V3ObservedSearch observer = new V3ObservedSearch();
        StrategicSearchResult result = new StrategicTeamSearch()
                .solve(state, config, seeds, 0, 0, observer);
        return new Observation(result, observer);
    }

    public record Observation(StrategicSearchResult result, V3ObservedSearch observed) { }

    @Override
    public void onRoot(StrategicAllocation allocation, StrategicSearchNode node, boolean unique) {
        roots.add(allocation);
        if (unique) uniqueRoots.add(node);
    }

    @Override
    public void onExpanded(StrategicSearchNode node) {
        currentNode = node;
        expandedNodes.add(node);
    }

    @Override
    public void onEdgeCandidate(int depth, AgentId patrolId, Position from, Position to,
            boolean allocationPreferred) {
        candidates.add(new Candidate(depth, patrolId, from, to, allocationPreferred, currentNode));
    }

    @Override
    public void onChildRejected(int depth, AgentId patrolId, Position from, Position to, String reason) {
        rejections.add(new Rejection(depth, patrolId, from, to, reason, currentNode));
    }

    @Override
    public void onChildAccepted(int depth, AgentId patrolId, Position from, Position to,
            StrategicSearchState child) {
        accepted.add(new Accepted(depth, patrolId, from, to, child));
    }

    @Override
    public void onStopChild(int depth, AgentId patrolId, boolean unique) {
        stopChildren++;
        if (unique) uniqueStopChildren++;
    }

    @Override
    public void onQuotaReached(int depth, AgentId patrolId, int skippedCandidates) {
        quotaTruncations++;
        if (skippedCandidates > 0) quotaSkippedCandidates += skippedCandidates;
    }

    @Override
    public void onBeamRetained(int iteration, List<StrategicSearchNode> candidateNodes,
            List<StrategicSearchNode> retained) {
        candidateSetByIteration.put(iteration, List.copyOf(candidateNodes));
        beamByIteration.put(iteration, List.copyOf(retained));
    }

    @Override
    public void onTerminal(StrategicSearchNode node, boolean materialized, boolean valid,
            StrategicOracleEvaluation evaluation) {
        terminals.add(new Terminal(node, materialized, valid, evaluation));
    }

    public List<StrategicSearchNode> expandedNodes() { return List.copyOf(expandedNodes); }
    public List<StrategicSearchNode> uniqueRoots() { return List.copyOf(uniqueRoots); }
    public List<StrategicAllocation> roots() { return List.copyOf(roots); }
    public List<Candidate> candidates() { return List.copyOf(candidates); }
    public List<Rejection> rejections() { return List.copyOf(rejections); }
    public List<Accepted> accepted() { return List.copyOf(accepted); }
    public List<Terminal> terminals() { return List.copyOf(terminals); }
    public Map<Integer, List<StrategicSearchNode>> beamByIteration() { return Map.copyOf(beamByIteration); }
    public Map<Integer, List<StrategicSearchNode>> candidateSetByIteration() {
        return Map.copyOf(candidateSetByIteration);
    }

    public record Candidate(int depth, AgentId patrolId, Position from, Position to, boolean preferred,
            StrategicSearchNode parent) { }

    public record Rejection(int depth, AgentId patrolId, Position from, Position to, String reason,
            StrategicSearchNode parent) { }

    public record Accepted(int depth, AgentId patrolId, Position from, Position to, StrategicSearchState child) { }

    public record Terminal(StrategicSearchNode node, boolean materialized, boolean valid,
            StrategicOracleEvaluation evaluation) { }

    /** Builds every mandated coverage counter from this recording. */
    public V3SearchCoverage coverage(StrategicSearchResult result) {
        StrategicOpportunityGraph graph = result.graph();
        int entryRoutes = graph.agentRoutes().values().stream().mapToInt(Map::size).sum();
        int available = graph.retainedStrategicEdges() + entryRoutes;
        Set<String> requestedEdges = candidates.stream().map(V3ObservedSearch::edgeKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> expandedEdges = accepted.stream().map(V3ObservedSearch::edgeKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Position, StrategicOpportunity> byPosition = graph.opportunities().stream()
                .collect(Collectors.toMap(StrategicOpportunity::position, value -> value, (a, b) -> a,
                        LinkedHashMap::new));
        int zeroStock = (int) candidates.stream()
                .filter(candidate -> byPosition.containsKey(candidate.to())
                        && byPosition.get(candidate.to()).currentStock() <= 0).count();
        int alreadyClaimed = (int) candidates.stream().filter(candidate -> candidate.parent() != null
                && candidate.parent().state().claimedOpportunities().contains(candidate.to())).count();
        List<Integer> own = terminals.stream().filter(Terminal::valid)
                .map(terminal -> terminal.evaluation().ownSemiCollections()).sorted().toList();
        List<Integer> hybrid = terminals.stream().filter(Terminal::valid)
                .map(terminal -> terminal.evaluation().hybridMarginScore4()).sorted().toList();
        return new V3SearchCoverage(available, candidates.size(), requestedEdges.size(),
                result.diagnostics().trajectoryCacheEntries(), result.diagnostics().trajectoryCacheRequests(),
                result.diagnostics().trajectoryCacheHits(), result.diagnostics().trajectoryCacheMisses(),
                (int) candidates.stream().map(Candidate::from).distinct().count(),
                (int) candidates.stream().map(Candidate::to).distinct().count(),
                (int) candidates.stream().map(Candidate::patrolId).distinct().count(),
                available == 0 ? 0.0 : 100.0 * requestedEdges.size() / available,
                graph.retainedStrategicEdges(), candidates.size() + quotaSkippedCandidates, accepted.size(),
                graph.retainedStrategicEdges() == 0 ? 0.0
                        : 100.0 * expandedEdges.size() / graph.retainedStrategicEdges(),
                accepted.size() + count("DEDUP"), candidates.size() + stopChildren,
                accepted.size() + uniqueStopChildren, count("FUEL"), count("TIME"), 0, 0, 0,
                count("VISITED_ROUTE"), count("TRAJECTORY_UNAVAILABLE"), count("DEDUP"), other(),
                quotaTruncations, 0, (int) candidates.stream().filter(Candidate::preferred).count(),
                zeroStock, alreadyClaimed, own.size(), first(own), percentile(own, 50), percentile(own, 90),
                last(own), first(hybrid), percentile(hybrid, 50), last(hybrid),
                atLeast(own, 1), atLeast(own, 5), atLeast(own, 10), atLeast(own, 14),
                result.winningNode().state().collectionEstimate(), result.rawWinner().ownSemiCollections(),
                (int) terminals.stream().filter(Terminal::valid)
                        .filter(terminal -> terminal.node().state().collectionEstimate()
                                != terminal.evaluation().ownSemiCollections()).count(),
                expandedNodes.stream().mapToInt(node -> node.state().collectionEstimate()).max().orElse(0),
                allocationLoads(), entryPoints(graph), committedTargets(), routeVectors());
    }

    private static String edgeKey(Candidate candidate) {
        return candidate.patrolId().value() + ":" + candidate.from().value() + "->" + candidate.to().value();
    }

    private static String edgeKey(Accepted value) {
        return value.patrolId().value() + ":" + value.from().value() + "->" + value.to().value();
    }

    private int count(String reason) {
        return (int) rejections.stream().filter(value -> value.reason().equals(reason)).count();
    }

    private int other() {
        Set<String> known = Set.of("FUEL", "TIME", "VISITED_ROUTE", "TRAJECTORY_UNAVAILABLE", "DEDUP");
        return (int) rejections.stream().filter(value -> !known.contains(value.reason())).count();
    }

    private static int first(List<Integer> values) { return values.isEmpty() ? 0 : values.get(0); }

    private static int last(List<Integer> values) {
        return values.isEmpty() ? 0 : values.get(values.size() - 1);
    }

    private static int percentile(List<Integer> sorted, int percent) {
        if (sorted.isEmpty()) return 0;
        int index = Math.min(sorted.size() - 1, Math.max(0, (percent * sorted.size() - 1) / 100));
        return sorted.get(index);
    }

    private static int atLeast(List<Integer> values, int threshold) {
        return (int) values.stream().filter(value -> value >= threshold).count();
    }

    private List<V3SearchCoverage.AllocationLoad> allocationLoads() {
        Map<String, String> supportClass = new LinkedHashMap<>();
        roots.forEach(allocation -> supportClass.put(allocation.signature(), allocation.supportClass()));
        Map<String, int[]> counters = new LinkedHashMap<>();
        roots.forEach(allocation -> counters.computeIfAbsent(allocation.signature(), key -> new int[5]));
        accepted.forEach(value -> counters.computeIfAbsent(value.child().allocation().signature(),
                key -> new int[5])[0]++);
        accepted.stream().collect(Collectors.groupingBy(value -> value.child().allocation().signature(),
                        LinkedHashMap::new, Collectors.mapping(value -> value.child().exactKey(),
                                Collectors.toCollection(LinkedHashSet::new))))
                .forEach((signature, keys) -> counters.computeIfAbsent(signature, key -> new int[5])[1] = keys.size());
        expandedNodes.forEach(node -> counters.computeIfAbsent(node.state().allocation().signature(),
                key -> new int[5])[2]++);
        terminals.stream().filter(Terminal::valid).forEach(terminal -> {
            int[] value = counters.computeIfAbsent(terminal.node().state().allocation().signature(),
                    key -> new int[5]);
            value[3]++;
            value[4] = Math.max(value[4], terminal.evaluation().ownSemiCollections());
        });
        return counters.entrySet().stream()
                .map(entry -> new V3SearchCoverage.AllocationLoad(entry.getKey(),
                        supportClass.getOrDefault(entry.getKey(), "UNKNOWN"), entry.getValue()[0],
                        entry.getValue()[1], entry.getValue()[2], entry.getValue()[3], entry.getValue()[4]))
                .toList();
    }

    private static Map<Integer, Integer> entryPoints(StrategicOpportunityGraph graph) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        graph.agentRoutes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(AgentId::value)))
                .forEach(entry -> result.put(entry.getKey().value(), entry.getValue().size()));
        return result;
    }

    private Map<Integer, Integer> committedTargets() {
        Map<Integer, Set<Integer>> byPatrol = new LinkedHashMap<>();
        accepted.forEach(value -> byPatrol.computeIfAbsent(value.patrolId().value(),
                key -> new LinkedHashSet<>()).add(value.to().value()));
        Map<Integer, Integer> result = new LinkedHashMap<>();
        byPatrol.keySet().stream().sorted().forEach(key -> result.put(key, byPatrol.get(key).size()));
        return result;
    }

    private List<String> routeVectors() {
        return terminals.stream().filter(Terminal::valid)
                .map(terminal -> terminal.node().state().patrols().stream()
                        .map(patrol -> patrol.patrolId().value() + ":"
                                + patrol.route().stream().map(position -> Integer.toString(position.value()))
                                        .collect(Collectors.joining(",")))
                        .collect(Collectors.joining("/")))
                .distinct().sorted().toList();
    }
}
