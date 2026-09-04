package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.HybridCalibratedMarginEvaluation;
import vn.ptit.procon.planner.Route;

/**
 * Benchmark-only Phase 2 search. It searches strategic route skeletons and
 * delegates legality and scoring to the existing validator/simulator stack.
 */
public final class StrategicTeamSearch {
    public StrategicSearchResult solve(DayState state) { return solve(state, StrategicSearchConfig.defaults(), List.of(), 0, 0); }

    public StrategicSearchResult solve(DayState state, StrategicSearchConfig config) {
        return solve(state, config, List.of(), 0, 0);
    }

    public StrategicSearchResult solve(DayState state, StrategicSearchConfig config, List<TeamPlan> seedPlans) {
        return solve(state, config, seedPlans, 0, 0);
    }

    /** Oracle metrics may be supplied by a benchmark without coupling search to the oracle. */
    public StrategicSearchResult solve(DayState state, StrategicSearchConfig config, List<TeamPlan> seedPlans,
            int representationOracleOwn, int representationOracleHybrid4) {
        return solve(state, config, seedPlans, representationOracleOwn, representationOracleHybrid4,
                StrategicSearchObserver.NONE);
    }

    /**
     * Observed search.  The observer is write-only: nothing in the control flow below reads it back, so
     * an observed run and an unobserved run are the same search.
     */
    public StrategicSearchResult solve(DayState state, StrategicSearchConfig config, List<TeamPlan> seedPlans,
            int representationOracleOwn, int representationOracleHybrid4, StrategicSearchObserver observer) {
        return solve(state, config, seedPlans, representationOracleOwn, representationOracleHybrid4, observer,
                V3SupportRootUniverse.noRefuelOnly(state));
    }

    /**
     * The same bounded search, now also handed the EXISTING R3 mobile support-root universe.
     *
     * <p>PART 2/16: V3 generates nothing here. Every mobile root in {@code universe} was already retained by
     * the frozen R3 planner; this method only decides which of them to search under, and the support axis of
     * a search state is the exact root identity rather than a vague class.
     *
     * <p>PART 8: with {@link V3SupportRootUniverse#noRefuelOnly(DayState)} — which every historical overload
     * above passes — the code below takes exactly the branches it took before Phase 2.5: the historical
     * fuel-filtered entry cache, the historical fuel gate, the historical chronology and the historical
     * materialisation.
     */
    public StrategicSearchResult solve(DayState state, StrategicSearchConfig config, List<TeamPlan> seedPlans,
            int representationOracleOwn, int representationOracleHybrid4, StrategicSearchObserver observer,
            V3SupportRootUniverse universe) {
        if (config.compositionSearch()) {
            return new StrategicTeamComposition().solve(state, config, seedPlans, representationOracleOwn,
                    representationOracleHybrid4, observer, universe);
        }
        long started = config.nanoClock().getAsLong();
        long deadline = config.maxPlanningMillis() == 0 ? Long.MAX_VALUE
                : started + config.maxPlanningMillis() * 1_000_000L;
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder().build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        StrategicTrajectoryCache trajectoryCache = new StrategicTrajectoryCache(state);
        StrategicChronologyReplay chronology = new StrategicChronologyReplay();
        List<AgentState> patrols = state.agents().stream().filter(a -> a.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(a -> a.id().value())).toList();
        FrozenObjectiveEvaluator evaluator = new FrozenObjectiveEvaluator(state);
        StrategicOracleEvaluation fallbackEvaluation = evaluator.evaluate(SafePlanFactory.waitAll(state)).orElseThrow();
        StrategicOracleEvaluation seed = evaluateBest(evaluator, seedPlans, SafePlanFactory.waitAll(state));
        StrategicOracleEvaluation rawWinner = fallbackEvaluation;
        StrategicOracleEvaluation winner = better(rawWinner, seed) ? rawWinner : seed;
        Map<String, SupportAwareTrajectoryScheduler> schedulers = new LinkedHashMap<>();
        for (V3SupportRootContext root : universe.roots()) {
            schedulers.put(root.supportRootId(), SupportAwareTrajectoryScheduler.of(state, root.trajectory()));
        }
        StrategicSearchNode winnerNode = initialNode(patrols, state,
                new StrategicAllocation(List.of(), List.of(), "NO_REFUEL", "EMPTY"), universe.noRefuel(),
                schedulers.get(universe.noRefuel().supportRootId()), chronology, config.trajectoryState());
        StrategicSearchNode rawWinnerNode = winnerNode;
        List<StrategicAllocation> allocations = new StrategicAllocationGenerator().generate(state, graph, config);
        List<SupportSeed> seeds = admit(allocations, universe.roots(), config.maxAllocationCandidates());
        int generated = allocations.size();
        int expanded = 0, generatedStates = 0, unique = 0, deduped = 0, dominated = 0;
        int edgeExpansions = 0, chainExpansions = 0, crossExpansions = 0, stopExpansions = 0;
        int childrenGenerated = 0, globalCapAffectedStates = 0, maxFrontierSize = 0;
        List<Integer> childrenPerState = new ArrayList<>();
        int materialized = 0, valid = 0, coupled = 0;
        long materialNanos = 0, coupledNanos = 0;
        Set<String> uniqueAllocations = new LinkedHashSet<>(), firstVectors = new LinkedHashSet<>(), regions = new LinkedHashSet<>(), support = new LinkedHashSet<>(), prefixes = new LinkedHashSet<>();
        boolean deadlineExceeded = false;
        String improvement = "NEVER";
        Map<String, StrategicSearchState> seen = new HashMap<>();
        List<StrategicSearchNode> terminalCandidates = new ArrayList<>();
        List<StrategicTerminalSnapshot> terminalSnapshots = new ArrayList<>();
        List<StrategicSearchNode> frontier = new ArrayList<>();
        Map<String, Integer> allocationsPerSupportRoot = new LinkedHashMap<>();
        Map<String, Integer> expandedPerSupportRoot = new LinkedHashMap<>();
        for (SupportSeed seedPair : seeds) {
            if (expired(config.nanoClock(), deadline)) { deadlineExceeded = true; break; }
            StrategicSearchNode node = initialNode(patrols, state, seedPair.allocation(), seedPair.root(),
                    schedulers.get(seedPair.root().supportRootId()), chronology, config.trajectoryState());
            boolean uniqueRoot = seen.putIfAbsent(node.state().exactKey(), node.state()) == null;
            if (uniqueRoot) { frontier.add(node); unique++; }
            else deduped++;
            observer.onRoot(seedPair.allocation(), node, uniqueRoot);
            uniqueAllocations.add(seedPair.allocation().signature());
            support.add(seedPair.root().signature());
            allocationsPerSupportRoot.merge(seedPair.root().supportRootId(), 1, Integer::sum);
        }
        int iteration = 0;
        while (!frontier.isEmpty() && expanded < config.maxStrategicExpandedStates() && !expired(config.nanoClock(), deadline)) {
            frontier.sort(nodeOrder());
            observer.onFrontier(iteration, frontier.size(), config.strategicBeamWidth());
            List<StrategicSearchNode> next = new ArrayList<>();
            int globalChildrenFromFrontier = 0;
            for (StrategicSearchNode node : frontier.stream().limit(config.strategicBeamWidth()).toList()) {
                if (expanded >= config.maxStrategicExpandedStates() || expired(config.nanoClock(), deadline)) break;
                expanded++;
                terminalCandidates.add(node);
                observer.onExpanded(node);
                V3SupportRootContext root = node.supportRoot();
                SupportAwareTrajectoryScheduler scheduler = schedulers.get(root.supportRootId());
                boolean supportAware = root.present();
                expandedPerSupportRoot.merge(root.supportRootId(), 1, Integer::sum);
                int childrenFromState = 0;
                if (node.depth() >= config.maxRouteDepth()) {
                    childrenPerState.add(0);
                    continue;
                }
                List<StrategicSearchState.PatrolState> states = node.state().patrols();
                List<Integer> order = java.util.stream.IntStream.range(0, states.size()).boxed()
                        .filter(i -> !states.get(i).stopped())
                        .sorted(Comparator.<Integer>comparingInt(i -> states.get(i).elapsed())
                                .thenComparingInt(i -> states.get(i).patrolId().value())).toList();
                for (int index : order) {
                    if (quotaReached(config, childrenFromState, globalChildrenFromFrontier)) {
                        if (!config.perStateChildQuota()) globalCapAffectedStates++;
                        observer.onQuotaReached(node.depth(), states.get(index).patrolId(), -1);
                        break;
                    }
                    StrategicSearchState.PatrolState patrol = states.get(index);
                    List<StrategicOpportunityGraph.OpportunityEdge> candidates = new StrategicRouteSkeletonSearch().candidates(graph, patrol, node.state().allocation(), supportAware);
                    int attempts = 0;
                    for (StrategicOpportunityGraph.OpportunityEdge edge : candidates) {
                        if (attempts++ >= config.maxStrategicChildrenPerState()
                                || quotaReached(config, childrenFromState, globalChildrenFromFrontier)) {
                            observer.onQuotaReached(node.depth(), patrol.patrolId(), candidates.size() - attempts + 1);
                            break;
                        }
                        observer.onEdgeCandidate(node.depth(), patrol.patrolId(), patrol.position(),
                                edge.to().position(), preferred(node.state().allocation(), patrol, edge));
                        if (patrol.route().contains(edge.to().position())) {
                            observer.onChildRejected(node.depth(), patrol.patrolId(), patrol.position(),
                                    edge.to().position(), "VISITED_ROUTE");
                            continue;
                        }
                        int elapsed = patrol.elapsed() + edge.route().stepsUsed();
                        if (elapsed > state.stepBudget()) {
                            observer.onChildRejected(node.depth(), patrol.patrolId(), patrol.position(),
                                    edge.to().position(), "TIME");
                            continue;
                        }
                        // PART 9: geometry existed before this line. Whether it is currently fuel/time
                        // feasible is decided here, and under a mobile root it is decided by the scheduler
                        // against the actual tanker trajectory rather than by a static refuel-cell set.
                        if (supportAware
                                ? !scheduler.feasible(patrol.position(), patrol.elapsed(), patrol.fuel(),
                                        trajectoryCache.effect(patrol.patrolId(), edge.route()))
                                : (!config.trajectoryState() && patrol.fuel() < edge.route().fuelUsed())
                                        || (config.trajectoryState() && !routeFuelFeasible(state, patrol, edge.route()))) {
                            observer.onChildRejected(node.depth(), patrol.patrolId(), patrol.position(),
                                    edge.to().position(), "FUEL");
                            continue;
                        }
                        childrenFromState++;
                        globalChildrenFromFrontier++;
                        childrenGenerated++;
                        StrategicSearchState child = advance(node.state(), index, edge.to(), graph, state,
                                patrols, trajectoryCache, chronology, config.trajectoryState(), root, scheduler);
                        if (child == null) {
                            observer.onChildRejected(node.depth(), patrol.patrolId(), patrol.position(),
                                    edge.to().position(), "TRAJECTORY_UNAVAILABLE");
                            continue;
                        }
                        generatedStates++;
                        if (edge.category() == StrategicEdgeCategory.DIFFERENT_REGION || edge.category() == StrategicEdgeCategory.LONG_HOP_FRESH_REGION) crossExpansions++;
                        else edgeExpansions++;
                        if (seen.putIfAbsent(child.exactKey(), child) != null) {
                            deduped++;
                            observer.onChildRejected(node.depth(), patrol.patrolId(), patrol.position(),
                                    edge.to().position(), "DEDUP");
                            continue;
                        }
                        StrategicSearchNode childNode = new StrategicSearchNode(child, append(node.transitions(), StrategicTransition.edge(patrol.position(), edge.to().position())), node.depth() + 1, node.firstTargetVector().isEmpty() ? Integer.toString(edge.to().position().value()) : node.firstTargetVector(), root);
                        next.add(childNode); unique++;
                        observer.onChildAccepted(node.depth(), patrol.patrolId(), patrol.position(),
                                edge.to().position(), child);
                        firstVectors.add(childNode.firstTargetVector()); prefixes.add(childNode.state().exactKey());
                    }
                    if (!quotaReached(config, childrenFromState, globalChildrenFromFrontier)) {
                        StrategicSearchState stopped = stop(states, index, node.state());
                        childrenFromState++;
                        globalChildrenFromFrontier++;
                        childrenGenerated++;
                        boolean uniqueStop = seen.putIfAbsent(stopped.exactKey(), stopped) == null;
                        if (uniqueStop) {
                            next.add(new StrategicSearchNode(stopped, append(node.transitions(), StrategicTransition.stop(patrol.position())), node.depth() + 1, node.firstTargetVector(), root));
                            unique++; stopExpansions++;
                        } else deduped++;
                        observer.onStopChild(node.depth(), patrol.patrolId(), uniqueStop);
                    }
                }
                childrenPerState.add(childrenFromState);
            }
            next.sort(nodeOrder());
            frontier = new ArrayList<>(next.stream().limit(config.strategicBeamWidth()).toList());
            observer.onBeamRetained(iteration, next, frontier);
            iteration++;
            maxFrontierSize = Math.max(maxFrontierSize, frontier.size());
        }
        if (expired(config.nanoClock(), deadline)) deadlineExceeded = true;
        terminalCandidates.sort(nodeOrder());
        int terminalLimit = config.maxTerminalEvaluations() == 0 ? terminalCandidates.size() : config.maxTerminalEvaluations();
        for (StrategicSearchNode node : terminalCandidates.stream().limit(terminalLimit).toList()) {
            if (expired(config.nanoClock(), deadline)) { deadlineExceeded = true; break; }
            long materialStarted = config.nanoClock().getAsLong();
            Optional<TeamPlan> plan = materialize(state, graph, patrols, node.state(), node.supportRoot(),
                    schedulers.get(node.supportRoot().supportRootId()));
            materialNanos += Math.max(0, config.nanoClock().getAsLong() - materialStarted);
            if (plan.isEmpty()) {
                observer.onTerminal(node, false, false, null);
                continue;
            }
            materialized++;
            long evalStarted = config.nanoClock().getAsLong();
            Optional<StrategicOracleEvaluation> evaluation = evaluator.evaluate(plan.get());
            coupledNanos += Math.max(0, config.nanoClock().getAsLong() - evalStarted);
            observer.onTerminal(node, true, evaluation.isPresent(), evaluation.orElse(null));
            if (evaluation.isPresent()) {
                valid++; coupled++;
                StrategicOracleEvaluation candidate = evaluation.get();
                terminalSnapshots.add(new StrategicTerminalSnapshot(plan.get(), node.state(), candidate));
                if (better(candidate, rawWinner)) {
                    rawWinner = candidate;
                    rawWinnerNode = node;
                    improvement = improvement.equals("NEVER")
                            ? Long.toString((config.nanoClock().getAsLong() - started) / 1_000_000L) : improvement;
                }
            }
        }
        if (better(rawWinner, seed)) {
            winner = rawWinner;
            winnerNode = rawWinnerNode;
        } else {
            winner = seed;
        }
        long totalMillis = Math.max(0, (config.nanoClock().getAsLong() - started) / 1_000_000L);
        double recovery = representationOracleOwn <= 0 ? 1.0 : Math.min(1.0,
                (double) Math.max(0, winner.ownSemiCollections() - seed.ownSemiCollections())
                        / Math.max(1, representationOracleOwn - seed.ownSemiCollections()));
        StrategicSearchDiagnostics diagnostics = new StrategicSearchDiagnostics(generated, seeds.size(), generatedStates,
                unique, expanded, deduped, dominated, edgeExpansions, chainExpansions, crossExpansions, stopExpansions,
                unique, materialized, valid, coupled, seed.ownSemiCollections(), seed.hybridMarginScore4(), winner.ownSemiCollections(),
                winner.hybridMarginScore4(), representationOracleOwn, representationOracleHybrid4, recovery,
                totalMillis, materialNanos / 1_000_000L, coupledNanos / 1_000_000L, deadlineExceeded, 0,
                uniqueAllocations.size(), firstVectors.size(), regions.size(), support.size(), prefixes.size(), improvement,
                childrenGenerated, config.maxStrategicChildrenPerState(), min(childrenPerState), median(childrenPerState),
                max(childrenPerState), globalCapAffectedStates, childrenGenerated, childrenGenerated, maxFrontierSize,
                trajectoryCache.size(), trajectoryCache.requests(), trajectoryCache.hits(), trajectoryCache.misses(),
                chronology.replays(), chronology.eventsProcessed(), 0, config.trajectoryState(), config.perStateChildQuota());
        V3SupportSearchDiagnostics supportDiagnostics = V3SupportSearchDiagnostics.of(universe, schedulers,
                allocationsPerSupportRoot, expandedPerSupportRoot, chronology.supportEventsProcessed(),
                winnerNode.supportRoot(), rawWinnerNode.supportRoot(),
                graph.entryPointsByPatrol(false), graph.entryPointsByPatrol(true));
        return new StrategicSearchResult(graph, winner, seed, diagnostics, winnerNode, rawWinner,
                valid == 0 || !winner.physicalSignature().equals(rawWinner.physicalSignature()), terminalSnapshots,
                supportDiagnostics);
    }

    /**
     * PART 17/18: bounded, deterministic, fair admission of (allocation, support root) seeds.
     *
     * <p>The total number of root seeds is unchanged — it is still {@code maxAllocationCandidates}. What
     * changes is how the slots are spent: every distinct support-root signature in the universe gets ONE
     * viable seed first, and only the slots left over are filled from the existing allocation order. That
     * keeps NO_REFUEL represented (it is the first root in the universe, PART 37) without ever adding a
     * numeric bonus or an extra candidate.
     */
    static List<SupportSeed> admit(List<StrategicAllocation> allocations, List<V3SupportRootContext> roots,
            int cap) {
        if (allocations.isEmpty() || roots.isEmpty()) return List.of();
        List<SupportSeed> seeds = new ArrayList<>();
        Set<String> admitted = new LinkedHashSet<>();
        for (V3SupportRootContext root : roots) {
            if (seeds.size() >= cap) break;
            if (!admitted.add(root.signature())) continue;
            seeds.add(new SupportSeed(allocations.getFirst(), root));
        }
        V3SupportRootContext noRefuel = roots.stream().filter(root -> !root.present()).findFirst()
                .orElse(roots.getFirst());
        for (int index = 1; index < allocations.size() && seeds.size() < cap; index++) {
            seeds.add(new SupportSeed(allocations.get(index), noRefuel));
        }
        return List.copyOf(seeds);
    }

    /** One root of the bounded search: a responsibility proposal plus the FIXED tanker trajectory. */
    record SupportSeed(StrategicAllocation allocation, V3SupportRootContext root) { }

    private static StrategicSearchNode initialNode(List<AgentState> patrols, DayState state,
            StrategicAllocation allocation, V3SupportRootContext root,
            SupportAwareTrajectoryScheduler scheduler, StrategicChronologyReplay chronology,
            boolean trajectoryState) {
        List<StrategicSearchState.PatrolState> values = patrols.stream().map(a -> new StrategicSearchState.PatrolState(a.id(), a.position(), 0,
                ((FiniteFuel) a.fuel()).amount(), List.of(), List.of(), List.of(), false)).toList();
        StrategicChronologyReplay.ChronologyResult replay = !trajectoryState
                ? endpointInitialResult(state, patrols)
                : root.present()
                        ? chronology.replaySupported(state, Map.of(), root.trajectory())
                        : chronology.replay(state, Map.of());
        return new StrategicSearchNode(new StrategicSearchState(values, replay.remainingStock(),
                replay.claims().stream().map(StrategicChronologyReplay.Claim::position).collect(java.util.stream.Collectors.toSet()),
                replay.collections(), replay.brands(), allocation, root.supportStateKey(0), replay.visited(),
                replay.fingerprint()), List.of(), 0, "", root);
    }

    private static StrategicSearchState advance(StrategicSearchState state, int index, StrategicOpportunity target,
            StrategicOpportunityGraph graph, DayState dayState, List<AgentState> patrols,
            StrategicTrajectoryCache cache, StrategicChronologyReplay chronology, boolean trajectoryState,
            V3SupportRootContext root, SupportAwareTrajectoryScheduler scheduler) {
        if (!trajectoryState) return advanceEndpoint(state, index, target, graph, dayState);
        List<StrategicSearchState.PatrolState> nextPatrols = new ArrayList<>(state.patrols());
        StrategicSearchState.PatrolState old = nextPatrols.get(index);
        List<Position> route = new ArrayList<>(old.route()); route.add(target.position());
        nextPatrols.set(index, new StrategicSearchState.PatrolState(old.patrolId(), target.position(), old.elapsed(), old.fuel(), route,
                old.primaryRegions(), old.secondaryRegions(), false));
        Map<AgentId, List<CachedTrajectoryEffect>> effects = committedEffects(
                new StrategicSearchState(nextPatrols, state.remainingStock(), state.claimedOpportunities(),
                        state.collectionEstimate(), state.brands(), state.allocation(), state.supportState(),
                        state.visitedByAgent(), state.chronologyFingerprint()),
                graph, dayState, patrols, cache, root.present());
        if (effects == null) return null;
        StrategicChronologyReplay.ChronologyResult replay = root.present()
                ? supportedReplay(dayState, patrols, effects, chronology, root, scheduler)
                : chronology.replay(dayState, effects);
        // PART 15: an optimistic schedule is rejected here, never silently clamped to zero fuel.
        if (replay == null || !replay.fuelFeasible()) return null;
        List<StrategicSearchState.PatrolState> normalized = normalizedPatrols(nextPatrols, replay);
        Set<Position> claimed = replay.claims().stream().map(StrategicChronologyReplay.Claim::position)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        // PART 19: the remaining tanker future is part of the identity, so it is recomputed from the
        // earliest committed prefix any PATROL has reached.
        String supportState = root.supportStateKey(normalized.stream()
                .mapToInt(StrategicSearchState.PatrolState::elapsed).min().orElse(0));
        return new StrategicSearchState(normalized, replay.remainingStock(), claimed, replay.collections(),
                replay.brands(), state.allocation(), supportState, replay.visited(), replay.fingerprint());
    }

    /**
     * PART 21: normalisation under a mobile root is always base state + ALL committed PATROL trajectories +
     * the one fixed R3 support trajectory. The inserted waiting is re-derived per PATROL and never searched.
     */
    static StrategicChronologyReplay.ChronologyResult supportedReplay(DayState dayState,
            List<AgentState> patrols, Map<AgentId, List<CachedTrajectoryEffect>> effects,
            StrategicChronologyReplay chronology, V3SupportRootContext root,
            SupportAwareTrajectoryScheduler scheduler) {
        Map<AgentId, List<StrategicChronologyReplay.ScheduledLeg>> schedules = new LinkedHashMap<>();
        for (AgentState patrol : patrols) {
            List<StrategicChronologyReplay.ScheduledLeg> scheduled = scheduler.scheduleAll(patrol.position(),
                    ((FiniteFuel) patrol.fuel()).amount(), effects.getOrDefault(patrol.id(), List.of()));
            if (scheduled == null) return null;
            schedules.put(patrol.id(), scheduled);
        }
        return chronology.replaySupported(dayState, schedules, root.trajectory());
    }

    private static StrategicChronologyReplay.ChronologyResult endpointInitialResult(DayState state,
            List<AgentState> patrols) {
        Map<AgentId, Position> positions = patrols.stream()
                .collect(Collectors.toMap(AgentState::id, AgentState::position));
        Map<AgentId, Integer> elapsed = patrols.stream()
                .collect(Collectors.toMap(AgentState::id, ignored -> 0));
        Map<AgentId, Integer> fuel = patrols.stream()
                .collect(Collectors.toMap(AgentState::id, a -> ((FiniteFuel) a.fuel()).amount()));
        return new StrategicChronologyReplay.ChronologyResult(state.spotStock(), Map.of(), Set.of(), Map.of(),
                List.of(), positions, elapsed, fuel, "");
    }

    private static StrategicSearchState advanceEndpoint(StrategicSearchState state, int index,
            StrategicOpportunity target, StrategicOpportunityGraph graph, DayState dayState) {
        List<StrategicSearchState.PatrolState> next = new ArrayList<>(state.patrols());
        StrategicSearchState.PatrolState old = next.get(index);
        Route route = old.route().isEmpty()
                ? graph.agentRoutes().getOrDefault(old.patrolId(), Map.of()).get(target.position())
                : graph.opportunityRoutes().getOrDefault(old.position(), Map.of()).get(target.position());
        if (route == null || old.fuel() < route.fuelUsed()
                || old.elapsed() + route.stepsUsed() > dayState.stepBudget()) return null;
        next.set(index, new StrategicSearchState.PatrolState(old.patrolId(), target.position(),
                old.elapsed() + route.stepsUsed(), old.fuel() - route.fuelUsed(),
                appendPosition(old.route(), target.position()), old.primaryRegions(), old.secondaryRegions(), false));
        Map<Position, Integer> stock = new LinkedHashMap<>(state.remainingStock());
        Set<Position> claimed = new LinkedHashSet<>(state.claimedOpportunities());
        Set<vn.ptit.procon.domain.udon.BrandId> brands = new LinkedHashSet<>(state.brands());
        int collections = state.collectionEstimate();
        if (stock.getOrDefault(target.position(), 0) > 0 && claimed.add(target.position())) {
            stock.put(target.position(), stock.get(target.position()) - 1);
            collections++;
            brands.add(target.brand());
        }
        return new StrategicSearchState(next, stock, claimed, collections, brands,
                state.allocation(), state.supportState(), Map.of(), "endpoint:" + target.position().value());
    }

    private static boolean routeFuelFeasible(DayState state, StrategicSearchState.PatrolState patrol, Route route) {
        int fuel = patrol.fuel();
        var refuelPositions = state.agents().stream().filter(agent -> agent.kind() == AgentKind.REFUEL)
                .map(AgentState::position).collect(Collectors.toSet());
        CachedTrajectoryEffect effect = CachedTrajectoryEffect.from(state, patrol.patrolId(), route);
        for (CachedTrajectoryEffect.Segment segment : effect.segments()) {
            fuel -= segment.fuelAfter() - segment.fuelBefore();
            if (fuel < 0) return false;
            if (refuelPositions.contains(segment.destination())) {
                fuel = state.matchData().patrolFuelCapacity().value();
            }
        }
        return true;
    }

    private static boolean quotaReached(StrategicSearchConfig config, int stateChildren, int frontierChildren) {
        return (config.perStateChildQuota() ? stateChildren : frontierChildren)
                >= config.maxStrategicChildrenPerState();
    }

    /**
     * Mirrors the allocation preference {@link StrategicRouteSkeletonSearch} applies.  It is reported to
     * observers only; the allocation is a sort preference and never a filter.
     */
    private static boolean preferred(StrategicAllocation allocation, StrategicSearchState.PatrolState patrol,
            StrategicOpportunityGraph.OpportunityEdge edge) {
        if (!patrol.route().isEmpty() || patrol.patrolId().value() >= allocation.primaryTargets().size()) {
            return false;
        }
        return allocation.primaryTargets().get(patrol.patrolId().value())
                .contains(edge.to().position().value());
    }

    private static int min(List<Integer> values) { return values.stream().mapToInt(Integer::intValue).min().orElse(0); }
    private static int max(List<Integer> values) { return values.stream().mapToInt(Integer::intValue).max().orElse(0); }
    private static int median(List<Integer> values) {
        if (values.isEmpty()) return 0;
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        return sorted.get((sorted.size() - 1) / 2);
    }

    private static List<Position> appendPosition(List<Position> values, Position value) {
        List<Position> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    /**
     * PART 21: a partial plan is the state at the END of its committed prefix, so the tank the next leg may
     * spend is the tank at that instant — never the tank the trailing all-day WAIT would eventually reach.
     */
    private static List<StrategicSearchState.PatrolState> normalizedPatrols(
            List<StrategicSearchState.PatrolState> patrols, StrategicChronologyReplay.ChronologyResult replay) {
        List<StrategicSearchState.PatrolState> result = new ArrayList<>();
        for (StrategicSearchState.PatrolState intent : patrols) result.add(new StrategicSearchState.PatrolState(intent.patrolId(),
                replay.finalPositions().get(intent.patrolId()), replay.finalElapsed().get(intent.patrolId()),
                replay.fuelAtElapsed().get(intent.patrolId()), intent.route(), intent.primaryRegions(),
                intent.secondaryRegions(), intent.stopped()));
        return List.copyOf(result);
    }

    private static Map<AgentId, List<CachedTrajectoryEffect>> committedEffects(StrategicSearchState state,
            StrategicOpportunityGraph graph, DayState dayState, List<AgentState> patrols,
            StrategicTrajectoryCache cache, boolean supportAware) {
        Map<AgentId, List<CachedTrajectoryEffect>> effects = new LinkedHashMap<>();
        for (int i = 0; i < state.patrols().size(); i++) {
            StrategicSearchState.PatrolState intent = state.patrols().get(i);
            Position cursor = patrols.get(i).position();
            List<CachedTrajectoryEffect> committed = new ArrayList<>();
            for (Position goal : intent.route()) {
                // PART 9: the first hop reads the entry cache the subtree was searched under. Under a mobile
                // root that is the capacity-lifted geometry; the legality of using it was decided already.
                Route movement = committed.isEmpty()
                        ? graph.entryRoutes(supportAware).getOrDefault(intent.patrolId(), Map.of()).get(goal)
                        : graph.opportunityRoutes().getOrDefault(cursor, Map.of()).get(goal);
                if (movement == null) return null;
                committed.add(cache.effect(intent.patrolId(), movement)); cursor = goal;
            }
            effects.put(intent.patrolId(), List.copyOf(committed));
        }
        return effects;
    }

    private static Route oneMove(DayState state, Position start, Position goal) {
        for (vn.ptit.procon.domain.map.Direction direction : vn.ptit.procon.domain.map.Direction.values()) {
            if (!state.matchData().map().neighbor(start, direction).orElse(start).equals(goal)) continue;
            var traffic = state.matchData().map().terrainAt(start) == vn.ptit.procon.domain.map.Terrain.ROAD
                    ? state.roadTraffic().get(start) : null;
            var cost = vn.ptit.procon.rules.MovementRules.costFromSource(state.matchData().map(), start, traffic).orElse(null);
            return cost == null ? null : new Route(start, goal, List.of(direction), cost.stepCost(), cost.patrolFuelCost());
        }
        return null;
    }

    private static StrategicSearchState stop(List<StrategicSearchState.PatrolState> patrols, int index, StrategicSearchState state) {
        List<StrategicSearchState.PatrolState> next = new ArrayList<>(patrols); var old = next.get(index);
        next.set(index, new StrategicSearchState.PatrolState(old.patrolId(), old.position(), old.elapsed(), old.fuel(), old.route(), old.primaryRegions(), old.secondaryRegions(), true));
        return new StrategicSearchState(next, state.remainingStock(), state.claimedOpportunities(), state.collectionEstimate(),
                state.brands(), state.allocation(), state.supportState(), state.visitedByAgent(), state.chronologyFingerprint());
    }

    private static int nextPatrol(List<StrategicSearchState.PatrolState> patrols) {
        return java.util.stream.IntStream.range(0, patrols.size()).filter(i -> !patrols.get(i).stopped()).boxed()
                .min(Comparator.<Integer>comparingInt(i -> patrols.get(i).elapsed())
                        .thenComparingInt(i -> patrols.get(i).patrolId().value())).orElse(-1);
    }

    /**
     * PART 7/47: ONE combined TeamPlan. The PATROL sequences come from V3, the REFUEL sequence comes
     * verbatim from the selected EXISTING R3 root, every agent consumes exactly the day budget, and
     * {@code PlanValidator} plus {@code DaySimulator} stay the only authority on legality.
     *
     * <p>PART 8: with the tanker-free root this delegates to the historical materialisation below, byte for
     * byte, so a NO_REFUEL run produces the plan it always produced.
     */
    static Optional<TeamPlan> materialize(DayState state, StrategicOpportunityGraph graph,
            List<AgentState> patrols, StrategicSearchState value, V3SupportRootContext root,
            SupportAwareTrajectoryScheduler scheduler) {
        return root.present() ? materializeSupported(state, graph, patrols, value, root, scheduler)
                : materialize(state, graph, patrols, value);
    }

    /**
     * Materialises a plan under one FIXED existing R3 support trajectory.
     *
     * <p>No fuel is granted anywhere here. The PATROL sequences carry the deterministic lead-in waiting the
     * scheduler derived (PART 12), so the fuel a PATROL spends later genuinely arrives from the tanker
     * standing on its cell at that step, and the simulator is what proves it.
     */
    private static Optional<TeamPlan> materializeSupported(DayState state, StrategicOpportunityGraph graph,
            List<AgentState> patrols, StrategicSearchState value, V3SupportRootContext root,
            SupportAwareTrajectoryScheduler scheduler) {
        Map<AgentId, List<Route>> routesByPatrol = new LinkedHashMap<>();
        for (int index = 0; index < patrols.size(); index++) {
            AgentState patrol = patrols.get(index);
            List<Route> routes = committedRoutes(graph, patrol, value.patrols().get(index));
            if (routes == null) return Optional.empty();
            routesByPatrol.put(patrol.id(), routes);
        }
        return V3SupportPlanMaterializer.materialize(state, root, scheduler, routesByPatrol);
    }

    /** The exact routes a committed prefix names, read from the caches its subtree searched. */
    private static List<Route> committedRoutes(StrategicOpportunityGraph graph, AgentState agent,
            StrategicSearchState.PatrolState intent) {
        List<Route> routes = new ArrayList<>();
        Position cursor = agent.position();
        for (Position goal : intent.route()) {
            Route route = routes.isEmpty()
                    ? graph.entryRoutes(true).getOrDefault(agent.id(), Map.of()).get(goal)
                    : graph.opportunityRoutes().getOrDefault(cursor, Map.of()).get(goal);
            if (route == null) return null;
            routes.add(route);
            cursor = goal;
        }
        return List.copyOf(routes);
    }

    private static Optional<TeamPlan> materialize(DayState state, StrategicOpportunityGraph graph, List<AgentState> patrols, StrategicSearchState value) {
        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            if (agent.kind() != AgentKind.PATROL) { actions.put(agent.id(), List.of(new WaitAction(state.stepBudget()))); continue; }
            int index = patrols.indexOf(agent); var intent = value.patrols().get(index); List<AgentAction> sequence = new ArrayList<>();
            Position cursor = agent.position(); int used = 0; int fuel = ((FiniteFuel) agent.fuel()).amount();
            Set<Position> refuelPositions = state.agents().stream().filter(a -> a.kind() == AgentKind.REFUEL)
                    .map(AgentState::position).collect(Collectors.toSet());
            for (Position goal : intent.route()) {
                Route route = cursor.equals(agent.position()) && used == 0
                        ? graph.agentRoutes().getOrDefault(agent.id(), Map.of()).get(goal)
                        : graph.opportunityRoutes().getOrDefault(cursor, Map.of()).get(goal);
                if (route == null || used + route.stepsUsed() > state.stepBudget()) return Optional.empty();
                for (var direction : route.directions()) {
                    Position destination = state.matchData().map().neighbor(cursor, direction).orElse(null);
                    var traffic = state.matchData().map().terrainAt(cursor) == vn.ptit.procon.domain.map.Terrain.ROAD
                            ? state.roadTraffic().get(cursor) : null;
                    var cost = vn.ptit.procon.rules.MovementRules.costFromSource(state.matchData().map(), cursor, traffic).orElse(null);
                    if (destination == null || cost == null || fuel < cost.patrolFuelCost()) return Optional.empty();
                    sequence.add(new MoveAction(direction));
                    used += cost.stepCost();
                    fuel -= cost.patrolFuelCost();
                    cursor = destination;
                    if (refuelPositions.contains(cursor)) fuel = state.matchData().patrolFuelCapacity().value();
                }
            }
            if (used < state.stepBudget()) sequence.add(new WaitAction(state.stepBudget() - used));
            actions.put(agent.id(), List.copyOf(sequence));
        }
        try { return Optional.of(new TeamPlan(actions)); } catch (RuntimeException exception) { return Optional.empty(); }
    }

    private static Comparator<StrategicSearchNode> nodeOrder() {
        return Comparator.comparingInt((StrategicSearchNode n) -> n.state().optimisticPotential()).reversed()
                .thenComparing(Comparator.comparingInt((StrategicSearchNode n) -> n.state().brands().size()).reversed())
                .thenComparing(Comparator.comparingInt((StrategicSearchNode n) -> n.state().collectionEstimate()).reversed())
                .thenComparingInt(n -> n.state().patrols().stream().mapToInt(StrategicSearchState.PatrolState::elapsed).sum())
                .thenComparing(n -> n.state().exactKey());
    }

    private static StrategicOracleEvaluation evaluateBest(FrozenObjectiveEvaluator evaluator, List<TeamPlan> seeds, TeamPlan fallback) {
        StrategicOracleEvaluation best = evaluator.evaluate(fallback).orElseThrow();
        for (TeamPlan plan : seeds) { Optional<StrategicOracleEvaluation> value = evaluator.evaluate(plan); if (value.isPresent() && better(value.get(), best)) best = value.get(); }
        return best;
    }

    static boolean better(StrategicOracleEvaluation left, StrategicOracleEvaluation right) { return HybridCalibratedMarginEvaluation.compare(left.objective(), right.objective()) < 0; }
    static boolean expired(LongSupplier clock, long deadline) { return clock.getAsLong() >= deadline; }
    private static List<StrategicTransition> append(List<StrategicTransition> values, StrategicTransition value) { List<StrategicTransition> result = new ArrayList<>(values); result.add(value); return result; }
}
