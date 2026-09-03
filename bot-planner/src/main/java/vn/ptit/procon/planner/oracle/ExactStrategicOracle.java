package vn.ptit.procon.planner.oracle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.FuelConsumedEvent;
import vn.ptit.procon.engine.MoveCompletedEvent;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.planner.WeightedRouteFinder;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.FrozenObjectiveEvaluator;
import vn.ptit.procon.planner.v3.StrategicOracleEvaluation;

/** Independent strategic DFS/branch-and-bound oracle; benchmark and tests only. */
public final class ExactStrategicOracle {
    private final DaySimulator simulator = new DaySimulator();
    private final PlanValidator validator = new PlanValidator();
    private DayState activeState;

    public ExactOracleResult solve(DayState state) { return solve(state, ExactOracleConfig.defaults(), List.of()); }
    public ExactOracleResult solve(DayState state, ExactOracleConfig config) { return solve(state, config, List.of()); }

    /** Known legal plans seed the incumbent; REFUEL plans also provide continuation roots. */
    public ExactOracleResult solve(DayState state, ExactOracleConfig config, List<TeamPlan> knownPlans) {
        activeState = state;
        if (state.agents().size() > config.maxAgents() || state.stepBudget() > config.maxDaySteps()) {
            return cappedFallback(state, knownPlans);
        }
        long started = System.nanoTime();
        List<TeamPlan> seeds = new ArrayList<>(knownPlans);
        if (seeds.isEmpty()) {
            try { seeds.add(new JointTeamBeamR3Planner().planWithStats(state).plan()); }
            catch (RuntimeException ignored) { /* waitAll remains the mandatory seed */ }
        }
        Search search = new Search(state, config, Catalog.build(state), seeds, started);
        search.run();
        return search.result();
    }

    private ExactOracleResult cappedFallback(DayState state, List<TeamPlan> seeds) {
        FrozenObjectiveEvaluator evaluator = new FrozenObjectiveEvaluator(state);
        TeamPlan best = SafePlanFactory.waitAll(state);
        StrategicOracleEvaluation value = evaluator.evaluate(best).orElseThrow();
        for (TeamPlan seed : seeds) {
            StrategicOracleEvaluation candidate = evaluator.evaluate(seed).orElse(null);
            if (candidate != null && candidate.objective().betterThan(value.objective())) { best = seed; value = candidate; }
        }
        int certifiedUpper = totalStock(state);
        boolean ownProven = value.ownSemiCollections() >= certifiedUpper;
        ExactOracleDiagnostics d = oldDiagnostics(value, "SEEDED_OR_FALLBACK", ownProven);
        return new ExactOracleResult(best, value.ownSemiCollections(), value.coupledOwnCollections(),
                value.hybridMarginScore4(), certifiedUpper,
                Math.max(0, certifiedUpper - value.ownSemiCollections()), ownProven, ownProven, false,
                value.physicalSignature(), skeleton(state, best), d, List.of(), best,
                value.physicalSignature(), value.ownSemiCollections(), new ExactOracleAudit(
                        value.ownSemiCollections(), value.hybridMarginScore4(), 0, 0,
                        value.ownSemiCollections(), value.hybridMarginScore4(), "SEEDED_OR_FALLBACK", true,
                        0, 0, 0, 0, value.ownSemiCollections(), value.hybridMarginScore4(), 0, 0, 0,
                        0, 0, certifiedUpper, value.ownSemiCollections(), 0, 0, 0, 0,
                        0, 0, false, 0, "NONE", false, "TRIVIAL_REMAINING_STOCK"));
    }

    private static ExactOracleDiagnostics oldDiagnostics(StrategicOracleEvaluation value, String source,
            boolean ownProven) {
        return new ExactOracleDiagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0, value.ownSemiCollections(),
                value.hybridMarginScore4(), ownProven, false, true, false, 0, 0, 0, 0);
    }

    private final class Search {
        private final DayState state; private final ExactOracleConfig config; private final Catalog catalog;
        private final FrozenObjectiveEvaluator evaluator; private final long started;
        private final Map<ExactOracleMemoKey, Node> memo = new HashMap<>();
        private final List<TeamPlan> validLeaves = new ArrayList<>();
        private final Set<String> terminalSignatures = new HashSet<>();
        private final List<TeamPlan> supportRoots;
        private boolean externalSupportUsed; private boolean searchCapReached; private boolean wallCapReached;
        private boolean targetCapReached; private int statesExpanded; private int transitionsGenerated;
        private long memoHits; private long memoMisses; private long boundPrunes; private long illegalTransitionsRejected;
        private long leafPlans; private long validLeafPlans; private int bestOwn = -1; private int bestHybrid = Integer.MIN_VALUE;
        private StrategicOracleEvaluation bestOwnEvaluation; private StrategicOracleEvaluation bestHybridEvaluation;
        private TeamPlan bestOwnPlan; private TeamPlan bestHybridPlan; private ExactOracleState bestOwnState;
        private int seededV2Own; private int seededV2Hybrid; private int seededV3Own; private int seededV3Hybrid;
        private int initialOracleOwn; private int initialOracleHybrid; private String incumbentSource = "WAIT_FALLBACK";
        private boolean incumbentPlanValid; private int supportRootsGenerated; private int supportRootsReplayed;
        private int supportRootsAccepted; private int continuationStatesExpanded; private int continuationBestOwn;
        private int continuationBestHybrid = Integer.MIN_VALUE; private int statesWithStopBranch; private int stopBranchesGenerated;
        private int uniqueTerminalSkeletons; private int fullyStoppedLeaves; private int partiallyStoppedThenCompletedLeaves;
        private int trivialUpperBound; private int tightUpperBound; private int branchOrderHintedTransitions;
        private final int certifiedUpperBound;

        Search(DayState state, ExactOracleConfig config, Catalog catalog, List<TeamPlan> seeds, long started) {
            this.state = state; this.config = config; this.catalog = catalog; this.started = started;
            this.evaluator = new FrozenObjectiveEvaluator(state);
            this.certifiedUpperBound = totalStock(state);
            this.trivialUpperBound = certifiedUpperBound;
            this.supportRoots = seeds.stream().filter(this::hasRefuelEvent).distinct()
                    .limit(config.maxSupportRoots()).toList();
            supportRootsGenerated = supportRoots.size();
            seedPlan(SafePlanFactory.waitAll(state), "WAIT_FALLBACK");
            for (TeamPlan seed : seeds) {
                if (seed.actionsByAgent().equals(SafePlanFactory.waitAll(state).actionsByAgent())) continue;
                seedPlan(seed, hasRefuelEvent(seed) ? "R3_SUPPORT_SEED" : "V2_SEED");
            }
            if (!seeds.isEmpty()) {
                StrategicOracleEvaluation v2 = evaluator.evaluate(seeds.getFirst()).orElse(null);
                if (v2 != null) { seededV2Own = v2.ownSemiCollections(); seededV2Hybrid = v2.hybridMarginScore4(); }
            }
            if (seeds.size() > 1) {
                StrategicOracleEvaluation v3 = evaluator.evaluate(seeds.get(1)).orElse(null);
                if (v3 != null) { seededV3Own = v3.ownSemiCollections(); seededV3Hybrid = v3.hybridMarginScore4(); }
            }
            initialOracleOwn = bestOwn; initialOracleHybrid = bestHybrid; 
            incumbentPlanValid = bestOwnPlan != null;
        }

        private void seedPlan(TeamPlan plan, String source) {
            StrategicOracleEvaluation value = evaluator.evaluate(plan).orElse(null);
            if (value == null) return;
            if (hasRefuelEvent(plan)) { supportRootsReplayed++; supportRootsAccepted++; externalSupportUsed = true; }
            updateBest(plan, value, null, source);
        }

        void run() {
            Map<AgentId, List<AgentAction>> empty = new LinkedHashMap<>();
            replay(empty, "NO_REFUEL", Set.of()).ifPresent(v -> dfs(new Node(v.state(), empty, Set.of(), false)));
            for (TeamPlan root : supportRoots) {
                if (capped()) break;
                Optional<Map<AgentId, List<AgentAction>>> prefix = supportPrefix(root);
                if (prefix.isEmpty()) continue;
                Map<AgentId, List<AgentAction>> actions = prefix.orElseThrow();
                replay(actions, "R3_SUPPORT_CONTINUATION", Set.of()).ifPresent(v ->
                        dfs(new Node(v.state(), actions, Set.of(), true)));
            }
        }

        private void dfs(Node node) {
            if (capped()) return;
            if (memo.putIfAbsent(new ExactOracleMemoKey(node.state()), node) != null) { memoHits++; return; }
            memoMisses++; statesExpanded++; if (node.continuation()) continuationStatesExpanded++;
            int trivial = node.state().ownCollections() + remainingStock(node.state());
            int tight = tightUpper(node.state());
            tightUpperBound = Math.max(tightUpperBound, Math.min(certifiedUpperBound, tight));
            // The independent route bound is reported for audit, but only the original
            // stock-total bound is used for pruning until every movement/claim edge case is proven.
            if (bestOwn >= 0 && trivial < bestOwn) { boundPrunes++; return; }
            terminalize(node);
            List<ExactOracleTransition> transitions = transitions(node);
            if (transitions.isEmpty()) { leafPlans++; stopBranches(node); return; }
            for (ExactOracleTransition transition : transitions) {
                Map<AgentId, List<AgentAction>> actions = append(node.actions(), transition.agentId(), transition.route());
                replay(actions, node.state().supportProvenance(), node.stopped()).ifPresent(v ->
                        dfs(new Node(v.state(), actions, node.stopped(), node.continuation())));
                if (capped()) return;
            }
            stopBranches(node);
        }

        private void terminalize(Node node) {
            replay(node.actions(), node.state().supportProvenance(), node.stopped()).ifPresent(v -> {
                TeamPlan terminal = plan(completeActions(node.actions()));
                boolean allStopped = state.agents().stream().filter(a -> a.kind() == AgentKind.PATROL)
                        .allMatch(a -> node.stopped().contains(a.id()));
                if (allStopped) fullyStoppedLeaves++;
                else if (!node.stopped().isEmpty()) partiallyStoppedThenCompletedLeaves++;
                int before = terminalSignatures.size();
                considerPlan(terminal, v.state(), node.continuation());
                if (terminalSignatures.size() > before) {
                    uniqueTerminalSkeletons++;
                }
            });
        }

        private void stopBranches(Node node) {
            List<AgentId> active = state.agents().stream().filter(a -> a.kind() == AgentKind.PATROL)
                    .map(AgentState::id).filter(id -> !node.stopped().contains(id)).toList();
            if (!active.isEmpty()) statesWithStopBranch++;
            for (AgentId id : active) {
                Set<AgentId> stopped = new LinkedHashSet<>(node.stopped()); stopped.add(id);
                replay(node.actions(), node.state().supportProvenance(), stopped).ifPresent(v -> {
                    stopBranchesGenerated++;
                    dfs(new Node(v.state(), node.actions(), stopped, node.continuation()));
                });
                if (capped()) return;
            }
        }

        private List<ExactOracleTransition> transitions(Node node) {
            List<ExactOracleTransition> result = new ArrayList<>();
            for (ExactOracleState.AgentProgress progress : node.state().agents()) {
                if (progress.kind() != AgentKind.PATROL || progress.stopped()) continue;
                Map<Position, Route> routes = catalog.fromPositions.getOrDefault(progress.position(), Map.of());
                List<Position> targets = catalog.targets.stream().limit(config.maxReachableTargets()).toList();
                if (catalog.targets.size() > config.maxReachableTargets()) targetCapReached = true;
                for (Position target : targets) {
                    Route route = routes.get(target);
                    if (route == null || route.stepsUsed() <= 0 || progress.elapsedSteps() + route.stepsUsed() > state.stepBudget()
                            || route.fuelUsed() > progress.fuel()) { illegalTransitionsRejected++; continue; }
                    Map<AgentId, List<AgentAction>> actions = append(node.actions(), progress.id(), route);
                    Optional<Replay> replay = replay(actions, node.state().supportProvenance(), node.stopped());
                    if (replay.isEmpty() || replay.orElseThrow().state().equals(node.state())) continue;
                    ExactOracleState next = replay.orElseThrow().state();
                    ExactOracleState.AgentProgress nextProgress = next.agents().stream()
                            .filter(v -> v.id().equals(progress.id())).findFirst().orElseThrow();
                    result.add(new ExactOracleTransition(progress.id(), target, route,
                            progress.elapsedSteps() + route.stepsUsed(),
                            Math.max(0, next.ownCollections() - node.state().ownCollections()),
                            nextProgress.fuel(), next));
                }
            }
            result.sort(Comparator.comparingInt(ExactOracleTransition::collectionGain).reversed()
                    .thenComparingInt(ExactOracleTransition::arrivalStep)
                    .thenComparingInt(v -> v.agentId().value()).thenComparingInt(v -> v.target().value()));
            transitionsGenerated += result.size(); branchOrderHintedTransitions += result.size(); return result;
        }

        private void considerPlan(TeamPlan plan, ExactOracleState exactState, boolean continuation) {
            if (!validator.validate(state, plan).valid()) return;
            if (!(simulator.simulate(state, plan) instanceof ValidDaySimulationResult)) return;
            validLeafPlans++;
            StrategicOracleEvaluation evaluation = evaluator.evaluate(plan).orElse(null);
            if (evaluation == null) return;
            terminalSignatures.add(evaluation.physicalSignature());
            if (bestOwnPlan == null || evaluation.ownSemiCollections() > bestOwn
                    || evaluation.ownSemiCollections() == bestOwn && evaluation.objective().betterThan(bestOwnEvaluation.objective())) {
                bestOwn = evaluation.ownSemiCollections(); bestOwnPlan = plan; bestOwnEvaluation = evaluation;
                bestOwnState = exactState; incumbentSource = continuation ? "SUPPORT_CONTINUATION" : "EXACT_SEARCH";
            }
            if (bestHybridPlan == null || evaluation.objective().betterThan(bestHybridEvaluation.objective())) {
                bestHybrid = evaluation.hybridMarginScore4(); bestHybridPlan = plan; bestHybridEvaluation = evaluation;
            }
            continuationBestOwn = Math.max(continuationBestOwn, evaluation.ownSemiCollections());
            continuationBestHybrid = Math.max(continuationBestHybrid, evaluation.hybridMarginScore4());
        }

        private void updateBest(TeamPlan plan, StrategicOracleEvaluation evaluation,
                ExactOracleState exactState, String source) {
            if (bestOwnPlan == null || evaluation.ownSemiCollections() > bestOwn
                    || evaluation.ownSemiCollections() == bestOwn && (bestOwnEvaluation == null
                    || evaluation.objective().betterThan(bestOwnEvaluation.objective()))) {
                bestOwn = evaluation.ownSemiCollections(); bestOwnPlan = plan; bestOwnEvaluation = evaluation;
                bestOwnState = exactState; incumbentSource = source;
            }
            if (bestHybridPlan == null || evaluation.objective().betterThan(bestHybridEvaluation.objective())) {
                bestHybrid = evaluation.hybridMarginScore4(); bestHybridPlan = plan; bestHybridEvaluation = evaluation;
            }
        }

        ExactOracleResult result() {
            int upper = certifiedUpperBound;
            boolean proofClosed = bestOwn >= upper;
            boolean ownComplete = proofClosed
                    || (!searchCapReached && !wallCapReached && !targetCapReached && !externalSupportUsed);
            // Own proof closure is independent from the frozen hybrid objective.  Unless the
            // search was exhaustive (and no external support root was supplied), no hybrid
            // upper bound exists and a hybrid optimum must remain unclaimed.
            boolean hybridComplete = !searchCapReached && !wallCapReached && !targetCapReached
                    && !externalSupportUsed;
            int gap = Math.max(0, upper - bestOwn);
            ExactOracleDiagnostics d = new ExactOracleDiagnostics(statesExpanded, transitionsGenerated, memoHits,
                    memoMisses, memo.size(), boundPrunes, illegalTransitionsRejected, leafPlans, validLeafPlans,
                    bestOwn, bestHybrid, ownComplete, hybridComplete, searchCapReached, wallCapReached,
                    catalog.pathfindingExecutions, 0, 0, millis(started));
            return new ExactOracleResult(bestOwnPlan, bestOwn, bestOwnEvaluation.coupledOwnCollections(), bestHybrid,
                    upper, gap, proofClosed, proofClosed, hybridComplete, bestOwnEvaluation.physicalSignature(),
                    skeleton(state, bestOwnPlan), d, validLeaves, bestHybridPlan, bestHybridEvaluation.physicalSignature(),
                    bestHybridEvaluation.ownSemiCollections(), new ExactOracleAudit(
                            seededV2Own, seededV2Hybrid, seededV3Own, seededV3Hybrid, initialOracleOwn,
                            initialOracleHybrid, incumbentSource, incumbentPlanValid, supportRootsGenerated,
                            supportRootsReplayed, supportRootsAccepted, continuationStatesExpanded,
                            continuationBestOwn, continuationBestHybrid, statesWithStopBranch, stopBranchesGenerated,
                            uniqueTerminalSkeletons, fullyStoppedLeaves, partiallyStoppedThenCompletedLeaves,
                            certifiedUpperBound, tightUpperBound, memo.size(), memo.size(), (int) memoHits, 0, 0,
                            branchOrderHintedTransitions, false, 0, "COLLECTION_GAIN_ARRIVAL_AGENT_TARGET",
                            false, "TRIVIAL_REMAINING_STOCK"));
        }

        private int tightUpper(ExactOracleState value) {
            int capacity = 0;
            for (ExactOracleState.AgentProgress patrol : value.agents()) {
                if (patrol.kind() != AgentKind.PATROL || patrol.stopped()) continue;
                int steps = state.stepBudget() - patrol.elapsedSteps(); int personal = 0;
                for (Position target : catalog.targets) {
                    Route route = catalog.fromPositions.getOrDefault(patrol.position(), Map.of()).get(target);
                    if (route != null && route.stepsUsed() <= steps && route.fuelUsed() <= patrol.fuel())
                        personal += value.remainingStock().getOrDefault(target, 0);
                }
                capacity += personal;
            }
            return value.ownCollections() + Math.min(remainingStock(value), capacity);
        }
        private int remainingStock(ExactOracleState value) { return value.remainingStock().values().stream().mapToInt(Integer::intValue).sum(); }
        private boolean capped() {
            if (statesExpanded >= config.maxSearchStates()) { searchCapReached = true; return true; }
            if ((System.nanoTime() - started) / 1_000_000L >= config.maxWallMillis()) { wallCapReached = true; return true; }
            return false;
        }
        private boolean hasRefuelEvent(TeamPlan plan) {
            return simulator.simulate(state, plan) instanceof ValidDaySimulationResult valid
                    && valid.events().stream().anyMatch(RefueledEvent.class::isInstance);
        }
    }

    private Optional<Map<AgentId, List<AgentAction>>> supportPrefix(TeamPlan plan) {
        if (!(simulator.simulate(activeState, plan) instanceof ValidDaySimulationResult valid)) return Optional.empty();
        int last = valid.events().stream().filter(RefueledEvent.class::isInstance).map(RefueledEvent.class::cast)
                .mapToInt(RefueledEvent::step).max().orElse(0);
        if (last == 0) return Optional.empty();
        Map<AgentId, List<AgentAction>> prefix = new LinkedHashMap<>();
        for (AgentState agent : activeState.agents()) {
            prefix.put(agent.id(), truncateAt(activeState, agent, plan.actionsByAgent().getOrDefault(agent.id(), List.of()), last));
        }
        return Optional.of(prefix);
    }

    private static List<AgentAction> truncateAt(DayState state, AgentState agent, List<AgentAction> actions, int limit) {
        List<AgentAction> result = new ArrayList<>(); Position cursor = agent.position(); int used = 0;
        for (AgentAction action : actions) {
            if (action instanceof WaitAction wait) {
                int take = Math.min(wait.steps(), Math.max(0, limit - used));
                if (take > 0) result.add(new WaitAction(take)); used += take; if (used >= limit) break; continue;
            }
            MoveAction move = (MoveAction) action;
            Optional<Position> destination = state.matchData().map().neighbor(cursor, move.direction()); if (destination.isEmpty()) break;
            Terrain terrain = state.matchData().map().terrainAt(cursor);
            var traffic = terrain == Terrain.ROAD ? state.roadTraffic().get(cursor) : null;
            Optional<MoveCost> cost = vn.ptit.procon.rules.MovementRules.costFromSource(state.matchData().map(), cursor, traffic);
            if (cost.isEmpty() || used + cost.orElseThrow().stepCost() > limit) break;
            result.add(action); used += cost.orElseThrow().stepCost(); cursor = destination.orElseThrow(); if (used >= limit) break;
        }
        return List.copyOf(result);
    }

    private Optional<Replay> replay(Map<AgentId, List<AgentAction>> prefixes, String support, Set<AgentId> stopped) {
        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
        for (AgentState agent : activeState.agents()) {
            List<AgentAction> sequence = new ArrayList<>(prefixes.getOrDefault(agent.id(), List.of()));
            int used = consumedSteps(activeState, agent, sequence);
            if (used < activeState.stepBudget()) sequence.add(new WaitAction(activeState.stepBudget() - used));
            actions.put(agent.id(), List.copyOf(sequence));
        }
        TeamPlan plan;
        try { plan = new TeamPlan(actions); } catch (RuntimeException exception) { return Optional.empty(); }
        if (!validator.validate(activeState, plan).valid()) return Optional.empty();
        if (!(simulator.simulate(activeState, plan) instanceof ValidDaySimulationResult valid)) return Optional.empty();
        return snapshot(prefixes, valid, support, stopped);
    }

    private Optional<Replay> snapshot(Map<AgentId, List<AgentAction>> prefixes, ValidDaySimulationResult valid,
            String support, Set<AgentId> stopped) {
        Map<AgentId, ExactOracleState.AgentProgress> progress = new LinkedHashMap<>();
        for (AgentState agent : activeState.agents()) {
            AgentState finalAgent = valid.finalAgents().stream().filter(v -> v.id().equals(agent.id())).findFirst().orElse(agent);
            int used = consumedSteps(activeState, agent, prefixes.getOrDefault(agent.id(), List.of()));
            progress.put(agent.id(), new ExactOracleState.AgentProgress(agent.id(), agent.kind(), finalAgent.position(), used,
                    finalAgent.fuel() instanceof FiniteFuel finite ? finite.amount() : -1,
                    stopped.contains(agent.id()), visited(activeState, agent, prefixes.getOrDefault(agent.id(), List.of()))));
        }
        Map<Position, List<ExactOracleState.Arrival>> timeline = new LinkedHashMap<>();
        valid.events().stream().filter(UdonCollectedEvent.class::isInstance).map(UdonCollectedEvent.class::cast)
                .forEach(event -> timeline.computeIfAbsent(event.position(), ignored -> new ArrayList<>())
                        .add(new ExactOracleState.Arrival(event.agentId(), event.step())));
        return Optional.of(new Replay(new ExactOracleState(new ArrayList<>(progress.values()), valid.remainingSpotStock(),
                valid.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum(), valid.brandsCollected(), timeline, support), valid));
    }

    private static Map<AgentId, List<AgentAction>> append(Map<AgentId, List<AgentAction>> current, AgentId id, Route route) {
        Map<AgentId, List<AgentAction>> result = new LinkedHashMap<>(); current.forEach((k, v) -> result.put(k, new ArrayList<>(v)));
        result.computeIfAbsent(id, ignored -> new ArrayList<>()).addAll(route.toMoveActions()); return result;
    }
    private Map<AgentId, List<AgentAction>> completeActions(Map<AgentId, List<AgentAction>> prefixes) {
        Map<AgentId, List<AgentAction>> result = new LinkedHashMap<>();
        for (AgentState agent : activeState.agents()) {
            List<AgentAction> actions = new ArrayList<>(prefixes.getOrDefault(agent.id(), List.of()));
            int used = consumedSteps(activeState, agent, actions); if (used < activeState.stepBudget()) actions.add(new WaitAction(activeState.stepBudget() - used));
            result.put(agent.id(), List.copyOf(actions));
        }
        return result;
    }
    private static TeamPlan plan(Map<AgentId, List<AgentAction>> actions) { return new TeamPlan(actions); }
    private static Set<Position> visited(DayState state, AgentState agent, List<AgentAction> actions) {
        Set<Position> result = new LinkedHashSet<>(); result.add(agent.position()); Position cursor = agent.position();
        for (AgentAction action : actions) if (action instanceof MoveAction move) {
            state.matchData().map().neighbor(cursor, move.direction()).ifPresent(result::add);
            cursor = state.matchData().map().neighbor(cursor, move.direction()).orElse(cursor);
        }
        return result;
    }
    private static int consumedSteps(DayState state, AgentState agent, List<AgentAction> actions) {
        Position cursor = agent.position(); int used = 0;
        for (AgentAction action : actions) {
            if (action instanceof WaitAction wait) { used += wait.steps(); continue; }
            MoveAction move = (MoveAction) action; Optional<Position> destination = state.matchData().map().neighbor(cursor, move.direction());
            if (destination.isEmpty()) return state.stepBudget() + 1;
            Terrain terrain = state.matchData().map().terrainAt(cursor); var traffic = terrain == Terrain.ROAD ? state.roadTraffic().get(cursor) : null;
            Optional<MoveCost> cost = vn.ptit.procon.rules.MovementRules.costFromSource(state.matchData().map(), cursor, traffic);
            if (cost.isEmpty()) return state.stepBudget() + 1; used += cost.orElseThrow().stepCost(); cursor = destination.orElseThrow();
        }
        return used;
    }
    private static OptimalCollectionSkeleton skeleton(DayState state, TeamPlan plan) {
        DaySimulationResult result = new DaySimulator().simulate(state, plan); if (!(result instanceof ValidDaySimulationResult valid)) return new OptimalCollectionSkeleton(List.of(), List.of());
        Map<AgentId, List<OptimalCollectionSkeleton.Visit>> visits = new LinkedHashMap<>();
        List<UdonCollectedEvent> collections = valid.events().stream().filter(UdonCollectedEvent.class::isInstance)
                .map(UdonCollectedEvent.class::cast).toList();
        for (UdonCollectedEvent event : collections) {
            AgentState initial = state.agents().stream().filter(value -> value.id().equals(event.agentId())).findFirst().orElseThrow();
            int initialFuel = initial.fuel() instanceof FiniteFuel fuel ? fuel.amount() : 0;
            List<FuelConsumedEvent> fuelEvents = valid.events().stream().filter(FuelConsumedEvent.class::isInstance)
                    .map(FuelConsumedEvent.class::cast).filter(value -> value.agentId().equals(event.agentId()))
                    .toList();
            int fuelBefore = fuelEvents.stream().filter(value -> value.step() < event.step())
                    .reduce((left, right) -> left.step() >= right.step() ? left : right)
                    .map(FuelConsumedEvent::after).orElse(initialFuel);
            int fuelAfter = fuelEvents.stream().filter(value -> value.step() <= event.step())
                    .reduce((left, right) -> left.step() >= right.step() ? left : right)
                    .map(FuelConsumedEvent::after).orElse(fuelBefore);
            int from = valid.events().stream().filter(MoveCompletedEvent.class::isInstance)
                    .map(MoveCompletedEvent.class::cast).filter(value -> value.agentId().equals(event.agentId())
                            && value.destination().equals(event.position()) && value.step() <= event.step())
                    .reduce((left, right) -> left.step() >= right.step() ? left : right)
                    .map(value -> value.source().value()).orElse(event.position().value());
            visits.computeIfAbsent(event.agentId(), ignored -> new ArrayList<>()).add(
                    new OptimalCollectionSkeleton.Visit(event.position().value(), event.step(), 1, event.brand(),
                            from, fuelBefore, fuelAfter, event.remainingStock() + 1, event.remainingStock()));
        }
        List<OptimalCollectionSkeleton.SupportEvent> support = valid.events().stream()
                .filter(RefueledEvent.class::isInstance).map(RefueledEvent.class::cast)
                .sorted(Comparator.comparingInt(RefueledEvent::step))
                .map(value -> new OptimalCollectionSkeleton.SupportEvent(value.step(), value.position().value(),
                        "patrol=" + value.patrolId().value())).toList();
        return new OptimalCollectionSkeleton(visits.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingInt(AgentId::value)))
                .map(e -> new OptimalCollectionSkeleton.AgentSkeleton(e.getKey(), e.getValue())).toList(), support);
    }
    private record Node(ExactOracleState state, Map<AgentId, List<AgentAction>> actions, Set<AgentId> stopped, boolean continuation) { }
    private record Replay(ExactOracleState state, ValidDaySimulationResult simulation) { }

    private static final class Catalog {
        private final List<Position> targets; private final Map<Position, Map<Position, Route>> fromPositions; private final int pathfindingExecutions;
        private Catalog(List<Position> targets, Map<Position, Map<Position, Route>> routes, int pathfindingExecutions) { this.targets = targets; fromPositions = routes; this.pathfindingExecutions = pathfindingExecutions; }
        static Catalog build(DayState state) {
            WeightedRouteFinder finder = new WeightedRouteFinder();
            List<Position> targets = state.matchData().udonSpots().stream().map(UdonSpot::position).sorted(Comparator.comparingInt(Position::value)).toList();
            Map<Position, Map<Position, Route>> routes = new LinkedHashMap<>(); int count = 0; int fuel = state.matchData().patrolFuelCapacity().value(); AgentId synthetic = new AgentId(Integer.MAX_VALUE);
            for (int raw = 0; raw < state.matchData().map().cellCount(); raw++) {
                Position from = new Position(raw); if (!state.matchData().map().isTraversable(from)) continue;
                AgentState source = AgentState.patrol(synthetic, from, fuel); Map<Position, Route> outgoing = new LinkedHashMap<>();
                for (Position target : targets) { count++; finder.find(state, source, target).ifPresent(route -> outgoing.put(target, route)); }
                routes.put(from, outgoing);
            }
            return new Catalog(targets, routes, count);
        }
    }
    private static long millis(long start) { return Math.max(0, (System.nanoTime() - start) / 1_000_000L); }

    private static int totalStock(DayState state) {
        return state.spotStock().values().stream().mapToInt(Integer::intValue).sum();
    }
}
