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
        return solve(state, config, policy, seedPlans, V3SupportRootContext.noRefuel());
    }

    /**
     * PART 27: the SAME Phase 1 oracle, the SAME explicit caps, re-run under one FIXED existing R3 mobile
     * support trajectory.
     *
     * <p>With {@link V3SupportRootContext#noRefuel()} — which every overload above passes — the three
     * decisions a support root touches (which entry-route cache the DFS reads, what makes a path feasible,
     * and how a plan is materialised) all take their historical branch, so the tanker-free oracle answer is
     * unchanged. With a mobile root the entry geometry is the capacity-lifted cache and the feasibility test
     * becomes {@link SupportAwareTrajectoryScheduler}, i.e. the tanker's real arrivals rather than a fuel
     * comparison against the tank the PATROL happens to start with.
     */
    public V3RepresentationResult solve(DayState state, V3RepresentationConfig config,
            V3EdgeRetentionPolicy policy, List<TeamPlan> seedPlans, V3SupportRootContext root) {
        if (config.compositionSearch()) return composed(state, config, policy, seedPlans, root);
        long started = System.nanoTime();
        long graphStarted = System.nanoTime();
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder().build(state, policy);
        long graphMillis = millis(graphStarted);
        long pathStarted = System.nanoTime();
        List<AgentState> patrols = state.agents().stream().filter(a -> a.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(a -> a.id().value())).toList();
        Map<AgentId, Map<Position, Route>> entries = graph.entryRoutes(root.present());
        SupportAwareTrajectoryScheduler scheduler = root.present()
                ? SupportAwareTrajectoryScheduler.of(state, root.trajectory()) : null;
        Map<AgentId, List<PathCandidate>> paths = new LinkedHashMap<>();
        int generatedPaths = 0;
        for (AgentState patrol : patrols) {
            List<PathCandidate> generated = new ArrayList<>();
            dfs(graph, entries, patrol, new ArrayList<>(), generated, config.maxPathLength());
            generatedPaths += generated.size();
            generated.sort(PATH_ORDER);
            List<PathCandidate> feasible = generated.stream()
                    .filter(path -> pathFeasible(state, graph, entries, scheduler, patrol, path)).toList();
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
            Optional<TeamPlan> plan = materialize(state, graph, entries, root, scheduler, patrols, team.paths());
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

    /**
     * PART 24: the SAME oracle question, asked through the Phase 2.6 enumeration organisation.
     *
     * <p>All four caps are the caller's, unchanged: {@code maxPathLength} bounds route length,
     * {@code maxPathsPerPatrol} bounds both portfolio work and portfolio retention,
     * {@code maxTeamCandidates} bounds composition expansions and frontier width, and
     * {@code maxMaterializedPlans} bounds materialisation. Two behavioural differences beyond the enumeration
     * shape, both required by the mandate rather than chosen: a partial team is already chronology-checked
     * against same-stock competition (PART 9), and an unmaterialisable candidate is SKIPPED instead of
     * aborting the whole materialisation loop.
     */
    private V3RepresentationResult composed(DayState state, V3RepresentationConfig config,
            V3EdgeRetentionPolicy policy, List<TeamPlan> seedPlans, V3SupportRootContext root) {
        long graphStarted = System.nanoTime();
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder().build(state, policy);
        long graphMillis = millis(graphStarted);
        long pathStarted = System.nanoTime();
        List<AgentState> patrols = state.agents().stream().filter(a -> a.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(a -> a.id().value())).toList();
        SupportAwareTrajectoryScheduler scheduler = SupportAwareTrajectoryScheduler.of(state, root.trajectory());
        StrategicTrajectoryCache cache = new StrategicTrajectoryCache(state);
        StrategicChronologyReplay chronology = new StrategicChronologyReplay();
        Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios = StrategicRoutePortfolio.build(state, graph,
                patrols, root, scheduler, cache, config.maxPathLength(), config.maxPathsPerPatrol(),
                config.maxPathsPerPatrol());
        long pathMillis = millis(pathStarted);
        long oracleStarted = System.nanoTime();
        StrategicSearchConfig bounded = new StrategicSearchConfig(config.maxTeamCandidates(),
                config.maxTeamCandidates(), config.maxPathsPerPatrol() + 1, 16, config.maxPathLength(),
                config.maxMaterializedPlans());
        List<StrategicTeamComposition.ComposedTeam> completed = StrategicTeamComposition.composeUnderRoot(state,
                patrols, chronology, cache, bounded, root, scheduler, portfolios);
        List<StrategicTeamComposition.ComposedTeam> ranked = StrategicTeamComposition.diverse(completed,
                config.maxMaterializedPlans());
        FrozenObjectiveEvaluator evaluator = new FrozenObjectiveEvaluator(state);
        StrategicOracleEvaluation winner = evaluator.evaluate(SafePlanFactory.waitAll(state)).orElseThrow();
        List<RouteSkeleton> skeletons = new ArrayList<>();
        int materialized = 0, valid = 0;
        for (StrategicTeamComposition.ComposedTeam team : ranked) {
            if (materialized >= config.maxMaterializedPlans()) break;
            Optional<TeamPlan> plan = StrategicTeamSearch.materialize(state, graph, patrols, team.state(), root,
                    scheduler);
            if (plan.isEmpty()) continue;
            materialized++;
            Optional<StrategicOracleEvaluation> evaluation = evaluator.evaluate(plan.orElseThrow());
            if (evaluation.isEmpty()) continue;
            valid++;
            if (better(evaluation.orElseThrow(), winner)) winner = evaluation.orElseThrow();
            skeletons.addAll(skeletons(graph, team));
        }
        for (TeamPlan seed : seedPlans) {
            Optional<StrategicOracleEvaluation> evaluation = evaluator.evaluate(seed);
            if (evaluation.isPresent() && better(evaluation.orElseThrow(), winner)) winner = evaluation.orElseThrow();
        }
        return composedResult(graph, skeletons, winner, portfolios, completed.size(), materialized, valid,
                graphMillis, pathMillis, millis(oracleStarted));
    }

    /** The per-PATROL skeletons a composed team names, straight from its already-cached target order. */
    private static List<RouteSkeleton> skeletons(StrategicOpportunityGraph graph,
            StrategicTeamComposition.ComposedTeam team) {
        List<RouteSkeleton> result = new ArrayList<>();
        for (StrategicRouteCandidate route : team.team().assigned()) {
            List<Position> positions = route.targets();
            List<Integer> regions = positions.stream().map(position -> regionOf(graph, position)).toList();
            List<StrategicTransition> transitions = new ArrayList<>();
            for (int index = 1; index < positions.size(); index++) {
                transitions.add(StrategicTransition.edge(positions.get(index - 1), positions.get(index)));
            }
            result.add(new RouteSkeleton(route.patrolId(), positions, regions, positions.size(),
                    route.brands().size(), transitions));
        }
        return result;
    }

    private V3RepresentationResult composedResult(StrategicOpportunityGraph graph, List<RouteSkeleton> skeletons,
            StrategicOracleEvaluation winner, Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios,
            int teamCandidates, int materialized, int valid, long graphMillis, long pathMillis,
            long oracleMillis) {
        int represented = (int) skeletons.stream().flatMap(s -> s.orderedOpportunities().stream()).distinct().count();
        int exactEdgeCount = countEdges(graph, skeletons);
        V3RepresentationDiagnostics diagnostics = new V3RepresentationDiagnostics(graph.opportunities().size(),
                graph.possibleDirectedEdges(), graph.retainedStrategicEdges(), graph.averageOutgoingEdges(),
                graph.maxOutgoingEdges(), (int) graph.localEdges(), (int) graph.crossRegionEdges(),
                (int) graph.longHopEdges(),
                portfolios.values().stream().mapToInt(StrategicRoutePortfolio.Portfolio::generated).sum(),
                portfolios.values().stream().mapToInt(StrategicRoutePortfolio.Portfolio::retained).sum(),
                teamCandidates, materialized, valid, represented, exactEdgeCount, exactEdgeCount, 0, 0, 0,
                graphMillis, pathMillis, pathMillis, 0, oracleMillis, 0);
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

    private static void dfs(StrategicOpportunityGraph graph, Map<AgentId, Map<Position, Route>> entries,
            AgentState patrol, List<StrategicOpportunity> path, List<PathCandidate> result, int maxLength) {
        PathCandidate candidate = pathCandidate(path);
        if (!path.isEmpty()) result.add(candidate);
        if (path.size() >= maxLength) return;
        StrategicOpportunity last = path.isEmpty() ? null : path.getLast();
        List<StrategicOpportunityGraph.OpportunityEdge> next = last == null
                ? graph.opportunities().stream().map(value -> entries.getOrDefault(patrol.id(), Map.of())
                        .containsKey(value.position()) ? new StrategicOpportunityGraph.OpportunityEdge(value, value,
                                entries.get(patrol.id()).get(value.position()), true, false, 0) : null)
                        .filter(java.util.Objects::nonNull).toList()
                : graph.outgoing().getOrDefault(last.position(), List.of());
        for (StrategicOpportunityGraph.OpportunityEdge edge : next) {
            StrategicOpportunity target = last == null ? edge.from() : edge.to();
            if (path.contains(target)) continue;
            path.add(target);
            dfs(graph, entries, patrol, path, result, maxLength);
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

    /**
     * Separates {@code ROUTE GEOMETRY EXISTS} from {@code ROUTE IS CURRENTLY FUEL/TIME FEASIBLE}.
     *
     * <p>Under a mobile root the fuel question is answered by the scheduler against the tanker's real
     * timeline, so a PATROL that starts with an empty tank is no longer excluded before it has had a chance
     * to be refuelled. The step budget still applies either way.
     */
    private static boolean pathFeasible(DayState state, StrategicOpportunityGraph graph,
            Map<AgentId, Map<Position, Route>> entries, SupportAwareTrajectoryScheduler scheduler,
            AgentState patrol, PathCandidate path) {
        if (path.opportunities().isEmpty()) return true;
        List<Route> routes = routes(graph, entries, patrol, path);
        if (routes == null) return false;
        int used = routes.stream().mapToInt(Route::stepsUsed).sum();
        if (used > state.stepBudget()) return false;
        if (scheduler != null) {
            return V3SupportPlanMaterializer.schedule(state, scheduler, patrol, routes) != null;
        }
        int fuel = ((FiniteFuel) patrol.fuel()).amount();
        for (Route route : routes) {
            fuel -= route.fuelUsed();
            if (fuel < 0) return false;
        }
        return true;
    }

    /** The exact Route chain a path names, entry hop first, or {@code null} when the graph has no such hop. */
    private static List<Route> routes(StrategicOpportunityGraph graph,
            Map<AgentId, Map<Position, Route>> entries, AgentState patrol, PathCandidate path) {
        List<Route> routes = new ArrayList<>();
        Position cursor = patrol.position();
        for (int i = 0; i < path.opportunities().size(); i++) {
            Position goal = path.opportunities().get(i).position();
            Route route = i == 0 ? entries.getOrDefault(patrol.id(), Map.of()).get(goal)
                    : graph.opportunityRoutes().getOrDefault(cursor, Map.of()).get(goal);
            if (route == null) return null;
            routes.add(route);
            cursor = goal;
        }
        return List.copyOf(routes);
    }

    private static Optional<TeamPlan> materialize(DayState state, StrategicOpportunityGraph graph,
            Map<AgentId, Map<Position, Route>> entries, V3SupportRootContext root,
            SupportAwareTrajectoryScheduler scheduler, List<AgentState> patrols, List<PathCandidate> selected) {
        if (scheduler == null) return materialize(state, graph, patrols, selected);
        Map<AgentId, List<Route>> routesByPatrol = new LinkedHashMap<>();
        for (AgentState patrol : patrols) {
            PathCandidate path = selected.get(patrols.indexOf(patrol));
            List<Route> routes = path.opportunities().isEmpty() ? List.of()
                    : routes(graph, entries, patrol, path);
            if (routes == null) return Optional.empty();
            routesByPatrol.put(patrol.id(), routes);
        }
        return V3SupportPlanMaterializer.materialize(state, root, scheduler, routesByPatrol);
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
