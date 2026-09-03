package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.HybridCalibratedMarginEvaluation;
import vn.ptit.procon.planner.Route;

/**
 * Benchmark-only Phase 1 oracle.  Its authority is the retained graph, not the old
 * chain list.  Every candidate is materialized through the frozen evaluator.
 */
public final class V3RepresentationOracle {
    private static final Comparator<PathCandidate> PATH_ORDER = Comparator
            .comparingInt(PathCandidate::rawPotential).reversed()
            .thenComparingInt(value -> value.brands().size()).reversed()
            .thenComparingInt(PathCandidate::duration)
            .thenComparing(PathCandidate::signature);

    public V3RepresentationResult solve(DayState state) {
        return solve(state, V3RepresentationConfig.defaults(), List.of());
    }

    public V3RepresentationResult solve(DayState state, V3RepresentationConfig config) {
        return solve(state, config, List.of());
    }

    /** Seeds are legal benchmark incumbents (for example a frozen R3 support plan), never exact plans. */
    public V3RepresentationResult solve(DayState state, V3RepresentationConfig config, List<TeamPlan> seedPlans) {
        return solve(state, config, V3EdgeRetentionPolicy.DIVERSE_GRAPH, seedPlans);
    }

    public V3RepresentationResult solve(DayState state, V3RepresentationConfig config,
            V3EdgeRetentionPolicy policy, List<TeamPlan> seedPlans) {
        long started = System.nanoTime();
        long graphStarted = System.nanoTime();
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder().build(state, policy);
        long graphMillis = millis(graphStarted);
        long pathStarted = System.nanoTime();
        List<AgentState> patrols = state.agents().stream().filter(a -> a.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(a -> a.id().value())).toList();
        Map<AgentId, List<PathCandidate>> paths = new LinkedHashMap<>();
        int generatedPaths = 0;
        for (AgentState patrol : patrols) {
            List<PathCandidate> generated = new ArrayList<>();
            dfs(graph, patrol, new ArrayList<>(), generated, config.maxPathLength());
            generatedPaths += generated.size();
            generated.sort(PATH_ORDER);
            List<PathCandidate> feasible = generated.stream()
                    .filter(path -> pathFeasible(state, graph, patrol, path)).toList();
            paths.put(patrol.id(), List.copyOf(feasible.stream().limit(config.maxPathsPerPatrol()).toList()));
        }
        long pathMillis = millis(pathStarted);
        long oracleStarted = System.nanoTime();
        FrozenObjectiveEvaluator evaluator = new FrozenObjectiveEvaluator(state);
        StrategicOracleEvaluation winner = evaluator.evaluate(SafePlanFactory.waitAll(state)).orElseThrow();
        List<RouteSkeleton> skeletons = new ArrayList<>();
        int[] teamCount = {0};
        int[] materialized = {0};
        int[] valid = {0};
        List<TeamCandidate> teamCandidates = buildTeamBeam(state, patrols, paths, config, teamCount);
        for (TeamCandidate team : teamCandidates) {
            Optional<TeamPlan> plan = materialize(state, graph, patrols, team.paths());
            if (plan.isEmpty() || materialized[0] >= config.maxMaterializedPlans()) break;
            materialized[0]++;
            Optional<StrategicOracleEvaluation> evaluation = evaluator.evaluate(plan.orElseThrow());
            if (evaluation.isEmpty()) continue;
            valid[0]++;
            StrategicOracleEvaluation candidate = evaluation.orElseThrow();
            if (better(candidate, winner)) winner = candidate;
            skeletons.addAll(toSkeletons(patrols, team.paths(), graph));
        }
        // Evaluate legal seed plans as ordinary benchmark incumbents after graph search.
        for (TeamPlan seed : seedPlans) {
            Optional<StrategicOracleEvaluation> evaluation = evaluator.evaluate(seed);
            if (evaluation.isPresent() && better(evaluation.orElseThrow(), winner)) winner = evaluation.orElseThrow();
        }
        int represented = (int) skeletons.stream().flatMap(s -> s.orderedOpportunities().stream()).distinct().count();
        int exactEdgeCount = countEdges(graph, skeletons);
        int exactCovered = exactEdgeCount;
        V3RepresentationDiagnostics diagnostics = new V3RepresentationDiagnostics(graph.opportunities().size(),
                graph.possibleDirectedEdges(), graph.retainedStrategicEdges(), graph.averageOutgoingEdges(),
                graph.maxOutgoingEdges(), (int) graph.localEdges(), (int) graph.crossRegionEdges(), (int) graph.longHopEdges(),
                generatedPaths, paths.values().stream().mapToInt(List::size).sum(), teamCount[0], materialized[0], valid[0],
                represented, exactEdgeCount, exactCovered, 0, 0, 0, graphMillis, pathMillis, pathMillis, 0,
                millis(oracleStarted), 0);
        return new V3RepresentationResult(graph, skeletons, winner, diagnostics,
                winner.ownSemiCollections() > 0 ? "GRAPH_PATH_SEARCH" : "NO_COLLECTING_PATH_FOUND");
    }

    private static List<TeamCandidate> buildTeamBeam(DayState state, List<AgentState> patrols,
            Map<AgentId, List<PathCandidate>> paths, V3RepresentationConfig config, int[] attempts) {
        List<TeamCandidate> beam = List.of(new TeamCandidate(List.of(), 0, 0, 0, ""));
        for (AgentState patrol : patrols) {
            List<TeamCandidate> next = new ArrayList<>();
            List<PathCandidate> choices = new ArrayList<>(paths.getOrDefault(patrol.id(), List.of()));
            choices.add(new PathCandidate(List.of(), 0, 0, Set.of(), ""));
            for (TeamCandidate partial : beam) for (PathCandidate path : choices) {
                attempts[0]++;
                List<PathCandidate> selected = new ArrayList<>(partial.paths());
                selected.add(path);
                next.add(teamCandidate(state, selected));
            }
            next.sort(Comparator.comparingInt(TeamCandidate::coveredStock).reversed()
                    .thenComparingInt(TeamCandidate::brands).reversed()
                    .thenComparingInt(TeamCandidate::pathLength).reversed()
                    .thenComparing(TeamCandidate::signature));
            beam = List.copyOf(next.stream().limit(config.maxTeamCandidates()).toList());
        }
        return beam;
    }

    private static TeamCandidate teamCandidate(DayState state, List<PathCandidate> paths) {
        Set<Position> positions = new LinkedHashSet<>();
        Set<vn.ptit.procon.domain.udon.BrandId> brands = new LinkedHashSet<>();
        for (PathCandidate path : paths) {
            path.opportunities().forEach(value -> positions.add(value.position()));
            brands.addAll(path.brands());
        }
        int stock = positions.stream().mapToInt(position -> state.spotStock().getOrDefault(position, 0)).sum();
        String signature = paths.stream().map(PathCandidate::signature).reduce((a, b) -> a + "/" + b).orElse("");
        return new TeamCandidate(List.copyOf(paths), stock, brands.size(),
                paths.stream().mapToInt(p -> p.opportunities().size()).sum(), signature);
    }

    private static void dfs(StrategicOpportunityGraph graph, AgentState patrol, List<StrategicOpportunity> path,
            List<PathCandidate> result, int maxLength) {
        PathCandidate candidate = pathCandidate(path);
        if (!path.isEmpty()) result.add(candidate);
        if (path.size() >= maxLength) return;
        StrategicOpportunity last = path.isEmpty() ? null : path.getLast();
        List<StrategicOpportunityGraph.OpportunityEdge> next = last == null
                ? graph.opportunities().stream().map(value -> graph.agentRoutes().getOrDefault(patrol.id(), Map.of())
                        .containsKey(value.position()) ? new StrategicOpportunityGraph.OpportunityEdge(value, value,
                                graph.agentRoutes().get(patrol.id()).get(value.position()), true, false, 0) : null)
                        .filter(java.util.Objects::nonNull).toList()
                : graph.outgoing().getOrDefault(last.position(), List.of());
        for (StrategicOpportunityGraph.OpportunityEdge edge : next) {
            StrategicOpportunity target = last == null ? edge.from() : edge.to();
            if (path.contains(target)) continue;
            path.add(target);
            dfs(graph, patrol, path, result, maxLength);
            path.removeLast();
        }
    }

    private static PathCandidate pathCandidate(List<StrategicOpportunity> path) {
        Set<vn.ptit.procon.domain.udon.BrandId> brands = new LinkedHashSet<>();
        path.forEach(value -> brands.add(value.brand()));
        return new PathCandidate(List.copyOf(path), path.stream().mapToInt(StrategicOpportunity::currentStock).sum(),
                Math.max(0, path.size() - 1), brands, path.stream().map(value -> Integer.toString(value.position().value()))
                        .reduce((a, b) -> a + ">" + b).orElse(""));
    }

    private static boolean pathFeasible(DayState state, StrategicOpportunityGraph graph,
            AgentState patrol, PathCandidate path) {
        if (path.opportunities().isEmpty()) return true;
        Route first = graph.agentRoutes().getOrDefault(patrol.id(), Map.of())
                .get(path.opportunities().getFirst().position());
        if (first == null) return false;
        int used = first.stepsUsed();
        int fuel = ((FiniteFuel) patrol.fuel()).amount() - first.fuelUsed();
        Position cursor = first.goal();
        for (int i = 1; i < path.opportunities().size(); i++) {
            Route route = graph.opportunityRoutes().getOrDefault(cursor, Map.of())
                    .get(path.opportunities().get(i).position());
            if (route == null || used + route.stepsUsed() > state.stepBudget() || fuel < route.fuelUsed()) return false;
            used += route.stepsUsed();
            fuel -= route.fuelUsed();
            cursor = route.goal();
        }
        return used <= state.stepBudget() && fuel >= 0;
    }

    private static Optional<TeamPlan> materialize(DayState state, StrategicOpportunityGraph graph,
            List<AgentState> patrols, List<PathCandidate> selected) {
        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
        for (AgentState patrol : patrols) {
            PathCandidate path = selected.get(patrols.indexOf(patrol));
            List<AgentAction> sequence = new ArrayList<>();
            int used = 0;
            int fuel = ((FiniteFuel) patrol.fuel()).amount();
            Position cursor = patrol.position();
            if (!path.opportunities().isEmpty()) {
                Route first = graph.agentRoutes().getOrDefault(patrol.id(), Map.of()).get(path.opportunities().getFirst().position());
                if (first == null || first.fuelUsed() > fuel) return Optional.empty();
                append(sequence, first); cursor = first.goal(); used += first.stepsUsed(); fuel -= first.fuelUsed();
                for (int i = 1; i < path.opportunities().size(); i++) {
                    Route route = graph.opportunityRoutes().getOrDefault(cursor, Map.of()).get(path.opportunities().get(i).position());
                    if (route == null || used + route.stepsUsed() > state.stepBudget() || fuel < route.fuelUsed()) return Optional.empty();
                    append(sequence, route); cursor = route.goal(); used += route.stepsUsed(); fuel -= route.fuelUsed();
                }
            }
            if (used > state.stepBudget()) return Optional.empty();
            if (state.stepBudget() > used) sequence.add(new WaitAction(state.stepBudget() - used));
            actions.put(patrol.id(), List.copyOf(sequence));
        }
        for (AgentState agent : state.agents()) if (agent.kind() != AgentKind.PATROL) actions.put(agent.id(), List.of(new WaitAction(state.stepBudget())));
        try { return Optional.of(new TeamPlan(actions)); } catch (RuntimeException exception) { return Optional.empty(); }
    }

    private static List<RouteSkeleton> toSkeletons(List<AgentState> patrols, List<PathCandidate> paths,
            StrategicOpportunityGraph graph) {
        List<RouteSkeleton> result = new ArrayList<>();
        for (int i = 0; i < patrols.size(); i++) {
            PathCandidate path = paths.get(i);
            List<Position> positions = path.opportunities().stream().map(StrategicOpportunity::position).toList();
            List<Integer> regions = positions.stream().map(position -> regionOf(graph, position)).toList();
            List<StrategicTransition> transitions = new ArrayList<>();
            for (int j = 1; j < positions.size(); j++) transitions.add(StrategicTransition.edge(positions.get(j - 1), positions.get(j)));
            result.add(new RouteSkeleton(patrols.get(i).id(), positions, regions, positions.size(), path.brands().size(), transitions));
        }
        return result;
    }

    private static int regionOf(StrategicOpportunityGraph graph, Position position) {
        return graph.regions().stream().filter(r -> r.members().stream().anyMatch(o -> o.position().equals(position)))
                .mapToInt(OpportunityRegion::regionId).findFirst().orElse(-1);
    }

    private static int countEdges(StrategicOpportunityGraph graph, List<RouteSkeleton> skeletons) {
        return (int) skeletons.stream().flatMap(s -> s.transitions().stream()).filter(t -> t.kind() == StrategicTransition.Kind.EDGE)
                .filter(t -> graph.outgoing().getOrDefault(t.from(), List.of()).stream().anyMatch(e -> e.to().position().equals(t.to()))).count();
    }

    private static void append(List<AgentAction> actions, Route route) { route.directions().stream().map(MoveAction::new).forEach(actions::add); }
    private static boolean better(StrategicOracleEvaluation left, StrategicOracleEvaluation right) {
        return HybridCalibratedMarginEvaluation.compare(left.objective(), right.objective()) < 0;
    }
    private static long millis(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000L); }

    private record PathCandidate(List<StrategicOpportunity> opportunities, int rawPotential, int duration,
            Set<vn.ptit.procon.domain.udon.BrandId> brands, String signature) {}
    private record TeamCandidate(List<PathCandidate> paths, int coveredStock, int brands, int pathLength, String signature) {}
}
