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
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.planner.HybridCalibratedMarginEvaluation;

/** Benchmark-only bounded oracle over strategic chains; never used by runtime. */
public final class StrategicOracle {
    private static final Comparator<OpportunityChain> CHAIN_ORDER = Comparator
            .comparingInt(OpportunityChain::totalRawCollectionPotential).reversed()
            .thenComparingInt(value -> value.distinctBrands().size()).reversed()
            .thenComparingInt(OpportunityChain::travelDuration)
            .thenComparingInt(OpportunityChain::fuelCost)
            .thenComparing(OpportunityChain::chainId);

    public StrategicOracleResult solve(DayState state) { return solve(state, V3Phase0Config.defaults()); }

    public StrategicOracleResult solve(DayState state, V3Phase0Config config) {
        return solve(state, config, List.of());
    }

    /** Adds already-valid NO_REFUEL/R3 plans as execution-feasibility seeds without changing the graph search. */
    public StrategicOracleResult solve(DayState state, V3Phase0Config config, List<TeamPlan> seedPlans) {
        long started = System.nanoTime();
        long graphStart = System.nanoTime();
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder().build(state);
        long graphMillis = millis(graphStart);
        long chainStart = System.nanoTime();
        List<OpportunityChain> generated = generateChains(graph, config);
        int generatedCount = generated.size();
        List<OpportunityChain> chains = retainNonDominated(generated, config.maxChainsPerPatrol() * Math.max(1, patrolCount(state)), config);
        long chainMillis = millis(chainStart);
        long allocationStart = System.nanoTime();
        List<TeamAllocation> allocations = enumerateAllocations(state, graph, chains, config);
        long allocationMillis = millis(allocationStart);
        FrozenObjectiveEvaluator evaluator = new FrozenObjectiveEvaluator(state);
        StrategicOracleEvaluation noRefuel = evaluator.evaluate(SafePlanFactory.waitAll(state)).orElseThrow();
        StrategicOracleEvaluation winner = noRefuel;
        List<StrategicOracleEvaluation> seedEvaluations = seedPlans.stream()
                .map(evaluator::evaluate).flatMap(Optional::stream).toList();
        for (StrategicOracleEvaluation seed : seedEvaluations) if (better(seed, winner)) winner = seed;
        int materialized = 0;
        int valid = 0;
        long materialStart = System.nanoTime();
        long evaluationMillis = 0;
        boolean capReached = allocations.size() >= config.maxTeamAllocations();
        for (TeamAllocation allocation : allocations) {
            if (materialized >= config.maxMaterializedPlans()) { capReached = true; break; }
            materialized++;
            Optional<TeamPlan> plan = materialize(state, graph, allocation);
            if (plan.isEmpty()) continue;
            long evalStart = System.nanoTime();
            Optional<StrategicOracleEvaluation> evaluation = evaluator.evaluate(plan.orElseThrow());
            evaluationMillis += System.nanoTime() - evalStart;
            if (evaluation.isEmpty()) continue;
            valid++;
            StrategicOracleEvaluation candidate = evaluation.orElseThrow();
            if (better(candidate, winner)) winner = candidate;
        }
        long materialMillis = millis(materialStart);
        Set<Position> represented = new LinkedHashSet<>();
        chains.forEach(chain -> chain.opportunities().forEach(value -> represented.add(value.position())));
        int opportunityCount = graph.opportunities().size();
        double coverage = opportunityCount == 0 ? 1.0 : ((double) represented.size() / opportunityCount);
        String reason = better(winner, noRefuel)
                ? "BETTER_PATROL_ASSIGNMENT" : capReached
                        ? "ORACLE_SEARCH_FAILED_TO_FIND_EXTRA_PLAN" : "STRATEGIC_PLAN_NOT_ACTUALLY_BETTER";
        V3Phase0Diagnostics diagnostics = new V3Phase0Diagnostics(opportunityCount, opportunityCount, 0,
                graph.regions().size(), generatedCount, chains.size(), generatedCount - chains.size(), represented.size(),
                opportunityCount - represented.size(), coverage, allocations.size(), materialized, valid, capReached,
                graph.graphBuildPathfindingExecutions(), 0, 0, graphMillis, chainMillis, allocationMillis,
                materialMillis, evaluationMillis / 1_000_000L, millis(started));
        return new StrategicOracleResult(graph, chains, allocations, winner, diagnostics, reason);
    }

    private static List<OpportunityChain> generateChains(StrategicOpportunityGraph graph, V3Phase0Config config) {
        List<OpportunityChain> result = new ArrayList<>();
        int id = 0;
        for (StrategicOpportunity start : graph.opportunities()) {
            List<StrategicOpportunity> path = new ArrayList<>();
            path.add(start);
            dfs(graph, config, path, result, id++);
        }
        result.sort(CHAIN_ORDER);
        return List.copyOf(result);
    }

    private static void dfs(StrategicOpportunityGraph graph, V3Phase0Config config,
            List<StrategicOpportunity> path, List<OpportunityChain> result, int id) {
        result.add(chain(graph, path, id, result.size()));
        if (path.size() >= config.maxChainLength()) return;
        StrategicOpportunity last = path.getLast();
        for (StrategicOpportunityGraph.OpportunityEdge edge : graph.outgoing().getOrDefault(last.position(), List.of())) {
            if (path.contains(edge.to())) continue;
            path.add(edge.to());
            dfs(graph, config, path, result, id);
            path.removeLast();
            if (result.size() >= config.maxChainsPerPatrol() * Math.max(1, graph.opportunities().size()) * 4) return;
        }
    }

    private static OpportunityChain chain(StrategicOpportunityGraph graph, List<StrategicOpportunity> path, int id, int ordinal) {
        int duration = 0, fuel = 0;
        for (int i = 1; i < path.size(); i++) {
            Route route = graph.opportunityRoutes().getOrDefault(path.get(i - 1).position(), Map.of()).get(path.get(i).position());
            if (route != null) { duration += route.stepsUsed(); fuel += route.fuelUsed(); }
        }
        Set<vn.ptit.procon.domain.udon.BrandId> brands = new LinkedHashSet<>();
        List<Integer> regions = new ArrayList<>();
        for (StrategicOpportunity value : path) {
            brands.add(value.brand());
            regions.add(regionOf(graph, value.position()));
        }
        return new OpportunityChain("C" + id + "_" + ordinal, path, path.stream().mapToInt(StrategicOpportunity::currentStock).sum(),
                brands, duration, fuel, regions, path.getFirst().position(), path.getLast().position(),
                path.stream().map(StrategicOpportunity::opponentPressure).toList());
    }

    private static int regionOf(StrategicOpportunityGraph graph, Position position) {
        return graph.regions().stream().filter(region -> region.members().stream().anyMatch(value -> value.position().equals(position)))
                .mapToInt(OpportunityRegion::regionId).findFirst().orElse(-1);
    }

    private static List<OpportunityChain> retainNonDominated(List<OpportunityChain> generated, int limit,
            V3Phase0Config config) {
        List<OpportunityChain> result = new ArrayList<>();
        for (OpportunityChain candidate : generated) {
            if (result.stream().anyMatch(existing -> dominates(existing, candidate))) continue;
            result.removeIf(existing -> dominates(candidate, existing));
            result.add(candidate);
            result.sort(CHAIN_ORDER);
            if (result.size() > limit) result.removeLast();
        }
        return List.copyOf(result);
    }

    private static boolean dominates(OpportunityChain left, OpportunityChain right) {
        Set<Position> leftPositions = left.opportunities().stream().map(StrategicOpportunity::position).collect(java.util.stream.Collectors.toSet());
        Set<Position> rightPositions = right.opportunities().stream().map(StrategicOpportunity::position).collect(java.util.stream.Collectors.toSet());
        return leftPositions.containsAll(rightPositions)
                && left.totalRawCollectionPotential() >= right.totalRawCollectionPotential()
                && left.distinctBrands().size() >= right.distinctBrands().size()
                && left.travelDuration() <= right.travelDuration() && left.fuelCost() <= right.fuelCost()
                && (left.totalRawCollectionPotential() > right.totalRawCollectionPotential()
                    || left.distinctBrands().size() > right.distinctBrands().size()
                    || left.travelDuration() < right.travelDuration() || left.fuelCost() < right.fuelCost());
    }

    private static List<TeamAllocation> enumerateAllocations(DayState state, StrategicOpportunityGraph graph,
            List<OpportunityChain> chains, V3Phase0Config config) {
        List<AgentState> patrols = state.agents().stream().filter(value -> value.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(value -> value.id().value())).toList();
        Map<Integer, List<OpportunityChain>> choices = new LinkedHashMap<>();
        for (AgentState patrol : patrols) {
            List<OpportunityChain> available = chains.stream().filter(chain -> graph.agentRoutes().getOrDefault(patrol.id(), Map.of())
                    .containsKey(chain.startPosition())).sorted(CHAIN_ORDER).limit(config.maxChainsPerPatrol()).toList();
            choices.put(patrol.id().value(), available);
        }
        List<TeamAllocation> result = new ArrayList<>();
        enumerate(patrols, 0, choices, new ArrayList<>(), result, config.maxTeamAllocations(), graph);
        return List.copyOf(result);
    }

    private static void enumerate(List<AgentState> patrols, int index, Map<Integer, List<OpportunityChain>> choices,
            List<PatrolAllocation> current, List<TeamAllocation> result, int limit, StrategicOpportunityGraph graph) {
        if (result.size() >= limit) return;
        if (index == patrols.size()) { result.add(teamAllocation(current, graph)); return; }
        AgentState patrol = patrols.get(index);
        enumerate(patrols, index + 1, choices, current, result, limit, graph);
        for (OpportunityChain chain : choices.getOrDefault(patrol.id().value(), List.of())) {
            OpportunityRegion region = graph.regions().stream().filter(r -> r.regionId() == chain.regionIds().getFirst()).findFirst().orElse(null);
            current.add(new PatrolAllocation(patrol.id(), region, chain, null,
                    chain.opportunities().stream().map(value -> value.position().value()).toList(),
                    chain.opportunities().size(), chain.distinctBrands().size(), chain.travelDuration(), chain.fuelCost()));
            enumerate(patrols, index + 1, choices, current, result, limit, graph);
            current.removeLast();
            if (result.size() >= limit) return;
        }
    }

    private static TeamAllocation teamAllocation(List<PatrolAllocation> current, StrategicOpportunityGraph graph) {
        Map<Integer, Integer> visits = new LinkedHashMap<>();
        current.forEach(value -> value.allocatedPositions().forEach(position -> visits.merge(position, 1, Integer::sum)));
        int overlap = visits.values().stream().mapToInt(value -> Math.max(0, value - 1)).sum();
        Set<Integer> assigned = visits.keySet();
        int high = (int) graph.opportunities().stream().filter(value -> value.currentStock() > 0
                && !assigned.contains(value.position().value())).count();
        Set<vn.ptit.procon.domain.udon.BrandId> brands = new LinkedHashSet<>();
        current.forEach(value -> brands.addAll(value.primaryChain().distinctBrands()));
        return new TeamAllocation(current, overlap, high, current.stream().mapToInt(PatrolAllocation::estimatedCollections).sum(), brands.size());
    }

    private static Optional<TeamPlan> materialize(DayState state, StrategicOpportunityGraph graph, TeamAllocation allocation) {
        Map<vn.ptit.procon.domain.agent.AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            if (agent.kind() != AgentKind.PATROL) { actions.put(agent.id(), List.of(new WaitAction(state.stepBudget()))); continue; }
            PatrolAllocation assigned = allocation.patrols().stream().filter(value -> value.patrolId().equals(agent.id())).findFirst().orElse(null);
            List<AgentAction> sequence = new ArrayList<>();
            Position cursor = agent.position();
            int used = 0;
            int fuel = ((FiniteFuel) agent.fuel()).amount();
            if (assigned != null) {
                Route first = graph.agentRoutes().getOrDefault(agent.id(), Map.of()).get(assigned.primaryChain().startPosition());
                if (first == null || first.fuelUsed() > fuel) return Optional.empty();
                append(sequence, first); cursor = first.goal(); used += first.stepsUsed(); fuel -= first.fuelUsed();
                List<StrategicOpportunity> chain = assigned.primaryChain().opportunities();
                for (int i = 1; i < chain.size(); i++) {
                    Route route = graph.opportunityRoutes().getOrDefault(cursor, Map.of()).get(chain.get(i).position());
                    if (route == null || used + route.stepsUsed() > state.stepBudget() || fuel < route.fuelUsed()) return Optional.empty();
                    append(sequence, route); cursor = route.goal(); used += route.stepsUsed(); fuel -= route.fuelUsed();
                }
            }
            if (used > state.stepBudget()) return Optional.empty();
            if (state.stepBudget() - used > 0) sequence.add(new WaitAction(state.stepBudget() - used));
            actions.put(agent.id(), List.copyOf(sequence));
        }
        try { return Optional.of(new TeamPlan(actions)); } catch (RuntimeException exception) { return Optional.empty(); }
    }

    private static void append(List<AgentAction> actions, Route route) { route.directions().stream().map(MoveAction::new).forEach(actions::add); }
    private static boolean better(StrategicOracleEvaluation left, StrategicOracleEvaluation right) {
        int compared = HybridCalibratedMarginEvaluation.compare(left.objective(), right.objective());
        return compared < 0;
    }
    private static int patrolCount(DayState state) { return (int) state.agents().stream().filter(value -> value.kind() == AgentKind.PATROL).count(); }
    private static long millis(long start) { return Math.max(0, (System.nanoTime() - start) / 1_000_000L); }
}
