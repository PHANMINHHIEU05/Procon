package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.Route;

/**
 * PART 7: the bounded BEST-FIRST TEAM-COMPOSITION search — the ONE architectural change of Phase 2.6.
 *
 * <p>What changes is the UNIT of a search step, and nothing else. The joint beam this replaces committed one
 * graph hop of one PATROL per expansion, so a team needed as many expansion levels as it had transitions: the
 * V2 witness on CURRENT LARGE needs fourteen, and the frozen budget of 128 expansions against a beam of 32
 * buys exactly four uniform levels. Measured, not assumed: {@code maxExpandedDepth=4}, {@code atCap=0}. Here
 * one expansion assigns one WHOLE already-legal strategic route to one PATROL, so a complete five-PATROL team
 * is reached in five expansions and the last of them yields up to a full beam of complete teams at once.
 *
 * <p>No budget is raised. Every bound below is an existing frozen field of {@link StrategicSearchConfig}:
 * route length is {@code maxRouteDepth}, the per-PATROL portfolio and the children of one composition state
 * are {@code maxStrategicChildrenPerState}, the composition frontier is {@code strategicBeamWidth}, the
 * global expansion budget is {@code maxStrategicExpandedStates} shared fairly across admitted support roots,
 * the roots come from the existing {@link StrategicTeamSearch#admit} under {@code maxAllocationCandidates},
 * and materialisation plus coupled evaluation stay inside {@code maxTerminalEvaluations}.
 *
 * <p>Nothing here searches geometry, so {@code strategicSearchPathfindingExecutions} stays 0 (PART 37), and
 * nothing here re-implements the simulator: legality of a leg is {@link SupportAwareTrajectoryScheduler} and
 * the settled truth of a partial team is {@link StrategicChronologyReplay} (PART 9).
 */
public final class StrategicTeamComposition {

    /** PART 35: what the composition search actually did, per fixture. */
    public record Counters(int routesGenerated, int routesRetained, int partialStatesGenerated,
            int partialStatesUnique, int partialStatesExpanded, int completeTeamCandidates,
            int materializedPlans, int validPlans, int coupledEvaluations, int portfolioExpansions,
            int chronologyRejections, int duplicateStates, int bestOwnAtMaterializationIndex,
            int bestHybridAtMaterializationIndex, boolean materializationCapReached,
            int uniqueOwnScores, int uniquePhysicalPlans) { }

    /** One composition state plus the frozen-shaped search state its chronology settled to. */
    public record ComposedTeam(TeamCompositionState team, StrategicSearchState state) {
        public ComposedTeam {
            Objects.requireNonNull(team, "Team must not be null");
            Objects.requireNonNull(state, "State must not be null");
        }
    }

    /** The whole Phase 2.6 payload: the frozen-shaped result plus the mandated audit evidence. */
    public record Outcome(StrategicSearchResult search,
            Map<String, Map<AgentId, StrategicRoutePortfolio.Portfolio>> portfoliosByRoot,
            List<ComposedTeam> completed, List<ComposedTeam> materialized, Counters counters) {
        public Outcome {
            Objects.requireNonNull(search, "Search result must not be null");
            portfoliosByRoot = Map.copyOf(Objects.requireNonNull(portfoliosByRoot));
            completed = List.copyOf(Objects.requireNonNull(completed));
            materialized = List.copyOf(Objects.requireNonNull(materialized));
            Objects.requireNonNull(counters, "Counters must not be null");
        }
    }

    /** The frozen-shaped entry point {@link StrategicTeamSearch} delegates to when the flag is set. */
    public StrategicSearchResult solve(DayState state, StrategicSearchConfig config, List<TeamPlan> seedPlans,
            int representationOracleOwn, int representationOracleHybrid4, StrategicSearchObserver observer,
            V3SupportRootUniverse universe) {
        return run(state, config, seedPlans, representationOracleOwn, representationOracleHybrid4, observer,
                universe).search();
    }

    /**
     * The composition search, with every counter PART 35 and PART 36 ask about kept.
     *
     * <p>PART 15/16/17: composition is ROOT-LOCAL. Each structurally distinct support root admitted by the
     * existing fair admission builds its own per-PATROL portfolios against its own real tanker timeline, dives
     * inside its own bounded share of the ONE global expansion budget, and only the complete teams are merged
     * globally. The global total is the frozen total; the merge adds no slot.
     */
    public Outcome run(DayState state, StrategicSearchConfig config, List<TeamPlan> seedPlans,
            int representationOracleOwn, int representationOracleHybrid4, StrategicSearchObserver observer,
            V3SupportRootUniverse universe) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(config, "Config must not be null");
        Objects.requireNonNull(universe, "Universe must not be null");
        long started = config.nanoClock().getAsLong();
        long deadline = config.maxPlanningMillis() == 0 ? Long.MAX_VALUE
                : started + config.maxPlanningMillis() * 1_000_000L;
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder()
                .build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        StrategicTrajectoryCache cache = new StrategicTrajectoryCache(state);
        StrategicChronologyReplay chronology = new StrategicChronologyReplay();
        List<AgentState> patrols = state.agents().stream().filter(agent -> agent.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(agent -> agent.id().value())).toList();
        FrozenObjectiveEvaluator evaluator = new FrozenObjectiveEvaluator(state);
        StrategicOracleEvaluation seed = evaluator.evaluate(SafePlanFactory.waitAll(state)).orElseThrow();
        for (TeamPlan plan : seedPlans) {
            Optional<StrategicOracleEvaluation> value = evaluator.evaluate(plan);
            if (value.isPresent() && StrategicTeamSearch.better(value.get(), seed)) seed = value.get();
        }
        Map<String, SupportAwareTrajectoryScheduler> schedulers = new LinkedHashMap<>();
        for (V3SupportRootContext root : universe.roots()) {
            schedulers.put(root.supportRootId(), SupportAwareTrajectoryScheduler.of(state, root.trajectory()));
        }
        List<StrategicAllocation> allocations = new StrategicAllocationGenerator().generate(state, graph, config);
        List<StrategicTeamSearch.SupportSeed> seeds = StrategicTeamSearch.admit(allocations, universe.roots(),
                config.maxAllocationCandidates());
        Map<String, StrategicTeamSearch.SupportSeed> distinct = new LinkedHashMap<>();
        for (StrategicTeamSearch.SupportSeed pair : seeds) distinct.putIfAbsent(pair.root().signature(), pair);
        Map<String, Map<AgentId, StrategicRoutePortfolio.Portfolio>> portfoliosByRoot = new LinkedHashMap<>();
        List<RootComposition> composers = new ArrayList<>();
        int retainedSlots = Math.max(1, config.maxStrategicChildrenPerState() - 1);
        for (StrategicTeamSearch.SupportSeed pair : distinct.values()) {
            V3SupportRootContext root = pair.root();
            SupportAwareTrajectoryScheduler scheduler = schedulers.get(root.supportRootId());
            Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios = StrategicRoutePortfolio.build(state,
                    graph, patrols, root, scheduler, cache, config.maxRouteDepth(),
                    config.maxStrategicChildrenPerState(), retainedSlots);
            portfoliosByRoot.put(root.signature(), portfolios);
            composers.add(new RootComposition(state, patrols, chronology, cache, config, root,
                    pair.allocation(), scheduler, portfolios, observer));
        }
        // PART 17: bounded initial opportunity per structurally distinct root, then the remainder shared in
        // the existing deterministic admission order. No root ever receives a score bonus.
        int budget = config.maxStrategicExpandedStates();
        int used = 0;
        boolean deadlineExceeded = false;
        int share = composers.isEmpty() ? 0 : Math.max(1, budget / composers.size());
        for (RootComposition composer : composers) {
            for (int step = 0; step < share && used < budget; step++) {
                if (StrategicTeamSearch.expired(config.nanoClock(), deadline)) { deadlineExceeded = true; break; }
                if (!composer.expand()) break;
                used++;
            }
            if (deadlineExceeded) break;
        }
        boolean progress = true;
        while (used < budget && progress && !deadlineExceeded) {
            progress = false;
            for (RootComposition composer : composers) {
                if (used >= budget) break;
                if (StrategicTeamSearch.expired(config.nanoClock(), deadline)) { deadlineExceeded = true; break; }
                if (composer.expand()) { used++; progress = true; }
            }
        }
        // PART 18/20: materialisation is LATE. Only complete teams reach it, ranked by the composition order
        // and then thinned to structurally distinct futures, so the frozen slots buy distinct legal plans.
        List<ComposedTeam> completed = composers.stream().flatMap(value -> value.completed().stream())
                .sorted(Comparator.comparing(ComposedTeam::team, compositionOrder())).toList();
        List<ComposedTeam> ranked = diverse(completed, config.maxTerminalEvaluations());
        StrategicOracleEvaluation fallbackEvaluation = evaluator.evaluate(SafePlanFactory.waitAll(state))
                .orElseThrow();
        StrategicOracleEvaluation rawWinner = fallbackEvaluation;
        StrategicSearchNode rawWinnerNode = fallbackNode(state, patrols, chronology, universe.noRefuel(),
                distinct.values().stream().findFirst().map(StrategicTeamSearch.SupportSeed::allocation)
                        .orElse(EMPTY_ALLOCATION));
        StrategicSearchNode winnerNode = rawWinnerNode;
        List<ComposedTeam> materializedTeams = new ArrayList<>();
        List<StrategicTerminalSnapshot> terminals = new ArrayList<>();
        Set<Integer> ownScores = new LinkedHashSet<>();
        Set<String> physicalPlans = new LinkedHashSet<>();
        int materialized = 0, valid = 0, coupled = 0, bestOwnIndex = -1, bestHybridIndex = -1;
        int bestOwn = -1, bestHybrid = Integer.MIN_VALUE;
        long materialNanos = 0, coupledNanos = 0;
        String improvement = "NEVER";
        for (ComposedTeam candidate : ranked) {
            if (StrategicTeamSearch.expired(config.nanoClock(), deadline)) { deadlineExceeded = true; break; }
            V3SupportRootContext root = candidate.team().supportRoot();
            long materialStarted = config.nanoClock().getAsLong();
            Optional<TeamPlan> plan = StrategicTeamSearch.materialize(state, graph, patrols, candidate.state(),
                    root, schedulers.get(root.supportRootId()));
            materialNanos += Math.max(0, config.nanoClock().getAsLong() - materialStarted);
            StrategicSearchNode node = terminalNode(candidate);
            if (plan.isEmpty()) { observer.onTerminal(node, false, false, null); continue; }
            materialized++;
            materializedTeams.add(candidate);
            long evalStarted = config.nanoClock().getAsLong();
            Optional<StrategicOracleEvaluation> evaluation = evaluator.evaluate(plan.get());
            coupledNanos += Math.max(0, config.nanoClock().getAsLong() - evalStarted);
            observer.onTerminal(node, true, evaluation.isPresent(), evaluation.orElse(null));
            if (evaluation.isEmpty()) continue;
            valid++; coupled++;
            StrategicOracleEvaluation value = evaluation.get();
            ownScores.add(value.ownSemiCollections());
            physicalPlans.add(value.physicalSignature());
            if (value.ownSemiCollections() > bestOwn) { bestOwn = value.ownSemiCollections(); bestOwnIndex = materialized - 1; }
            if (value.hybridMarginScore4() > bestHybrid) { bestHybrid = value.hybridMarginScore4(); bestHybridIndex = materialized - 1; }
            terminals.add(new StrategicTerminalSnapshot(plan.get(), candidate.state(), value));
            if (StrategicTeamSearch.better(value, rawWinner)) {
                rawWinner = value;
                rawWinnerNode = node;
                improvement = improvement.equals("NEVER")
                        ? Long.toString((config.nanoClock().getAsLong() - started) / 1_000_000L) : improvement;
            }
        }
        // PART 33: the frozen V2 incumbent fallback is unchanged — it decides the SELECTED figure only.
        StrategicOracleEvaluation winner = StrategicTeamSearch.better(rawWinner, seed) ? rawWinner : seed;
        if (StrategicTeamSearch.better(rawWinner, seed)) winnerNode = rawWinnerNode;
        long totalMillis = Math.max(0, (config.nanoClock().getAsLong() - started) / 1_000_000L);
        double recovery = representationOracleOwn <= 0 ? 1.0 : Math.min(1.0,
                (double) Math.max(0, winner.ownSemiCollections() - seed.ownSemiCollections())
                        / Math.max(1, representationOracleOwn - seed.ownSemiCollections()));
        Counters counters = counters(composers, completed, materialized, valid, coupled, bestOwnIndex,
                bestHybridIndex, config, ownScores.size(), physicalPlans.size());
        Map<String, Integer> allocationsPerRoot = new LinkedHashMap<>();
        Map<String, Integer> expandedPerRoot = new LinkedHashMap<>();
        for (StrategicTeamSearch.SupportSeed pair : seeds) {
            allocationsPerRoot.merge(pair.root().supportRootId(), 1, Integer::sum);
        }
        for (RootComposition composer : composers) {
            expandedPerRoot.merge(composer.root().supportRootId(), composer.expanded(), Integer::sum);
        }
        StrategicSearchDiagnostics diagnostics = new StrategicSearchDiagnostics(allocations.size(), seeds.size(),
                counters.partialStatesGenerated(), counters.partialStatesUnique(), used,
                counters.duplicateStates(), 0, counters.routesRetained(), 0, 0, composers.size(),
                counters.completeTeamCandidates(), materialized, valid, coupled, seed.ownSemiCollections(),
                seed.hybridMarginScore4(), winner.ownSemiCollections(), winner.hybridMarginScore4(),
                representationOracleOwn, representationOracleHybrid4, recovery, totalMillis,
                materialNanos / 1_000_000L, coupledNanos / 1_000_000L, deadlineExceeded, 0,
                (int) seeds.stream().map(pair -> pair.allocation().signature()).distinct().count(),
                (int) completed.stream().map(value -> value.team().diversityKey()).distinct().count(),
                (int) completed.stream().map(value -> value.team().routeSignatures()).distinct().count(),
                distinct.size(), counters.partialStatesUnique(), improvement,
                counters.partialStatesGenerated(), config.maxStrategicChildrenPerState(),
                min(composers), median(composers), max(composers), 0, counters.partialStatesGenerated(),
                counters.partialStatesGenerated(), maxFrontier(composers), cache.size(), cache.requests(),
                cache.hits(), cache.misses(), chronology.replays(), chronology.eventsProcessed(), 0,
                config.trajectoryState(), config.perStateChildQuota());
        V3SupportSearchDiagnostics supportDiagnostics = V3SupportSearchDiagnostics.of(universe, schedulers,
                allocationsPerRoot, expandedPerRoot, chronology.supportEventsProcessed(),
                winnerNode.supportRoot(), rawWinnerNode.supportRoot(), graph.entryPointsByPatrol(false),
                graph.entryPointsByPatrol(true));
        StrategicSearchResult result = new StrategicSearchResult(graph, winner, seed, diagnostics, winnerNode,
                rawWinner, valid == 0 || !winner.physicalSignature().equals(rawWinner.physicalSignature()),
                terminals, supportDiagnostics);
        return new Outcome(result, portfoliosByRoot, completed, materializedTeams, counters);
    }

    private static final StrategicAllocation EMPTY_ALLOCATION =
            new StrategicAllocation(List.of(), List.of(), "NO_REFUEL", "EMPTY");

    /**
     * PART 11: the lexicographic frontier order. There is NO weighted scalar anywhere in it.
     *
     * <p>The assigned-route count sits third on purpose. Assigning the empty route to the next PATROL leaves
     * the settled chronology — and therefore keys one and two — untouched, so a deeper state always outranks
     * its own shallower siblings on a tie. That single property is what turns this into a dive: a complete
     * team is reached in exactly as many expansions as there are PATROLs, and the remaining budget is spent on
     * alternatives instead of on re-deriving the same prefixes one layer at a time.
     */
    public static Comparator<TeamCompositionState> compositionOrder() {
        return Comparator.comparingInt(TeamCompositionState::securedCollections).reversed()
                .thenComparing(Comparator.comparingInt((TeamCompositionState value) -> value.brands().size())
                        .reversed())
                .thenComparing(Comparator.comparingInt(TeamCompositionState::assignedCount).reversed())
                .thenComparing(Comparator.comparingInt(TeamCompositionState::optimisticTotal).reversed())
                .thenComparingInt(TeamCompositionState::duplicatedStockDemand)
                .thenComparingInt(TeamCompositionState::routeOverlap)
                .thenComparingInt(TeamCompositionState::totalElapsed)
                .thenComparing(TeamCompositionState::identity);
    }

    /** PART 20: the terminal slots go to structurally distinct legal futures first, in quality order. */
    static List<ComposedTeam> diverse(List<ComposedTeam> ordered, int cap) {
        if (cap <= 0 || ordered.size() <= cap) return List.copyOf(ordered);
        List<ComposedTeam> retained = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (ComposedTeam value : ordered) {
            if (retained.size() >= cap) break;
            if (keys.add(value.team().diversityKey())) retained.add(value);
        }
        for (ComposedTeam value : ordered) {
            if (retained.size() >= cap) break;
            if (!retained.contains(value)) retained.add(value);
        }
        return List.copyOf(retained);
    }

    /**
     * PART 24: the SAME composition engine, driven by the representation oracle's own four caps.
     *
     * <p>The oracle asks a representation question under ONE fixed root, so there is no root budget to share
     * and no seed to improve — only the composition itself. {@code bounded} carries the oracle caps verbatim
     * ({@code maxTeamCandidates} as both frontier width and expansion budget, {@code maxPathsPerPatrol + 1}
     * as the per-state choice count, which is exactly the {@code paths + empty} choice list the layered
     * Cartesian product already used), so nothing is enlarged here either.
     */
    static List<ComposedTeam> composeUnderRoot(DayState state, List<AgentState> patrols,
            StrategicChronologyReplay chronology, StrategicTrajectoryCache cache,
            StrategicSearchConfig bounded, V3SupportRootContext root,
            SupportAwareTrajectoryScheduler scheduler,
            Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios) {
        RootComposition composer = new RootComposition(state, patrols, chronology, cache, bounded, root,
                EMPTY_ALLOCATION, scheduler, portfolios, StrategicSearchObserver.NONE);
        for (int step = 0; step < bounded.maxStrategicExpandedStates(); step++) {
            if (!composer.expand()) break;
        }
        return composer.completed().stream()
                .sorted(Comparator.comparing(ComposedTeam::team, compositionOrder())).toList();
    }

    /**
     * PART 9: settles ONE explicit partial assignment through the same chronology the search uses.
     *
     * <p>The search itself never needs this — it composes partials as it expands — but the mandated partial-team
     * evidence has to be able to point at a named partial assignment and show what the chronology already knew
     * about it. It is the same {@code compose} the search calls, so the evidence cannot drift from the search.
     */
    static ComposedTeam composePartial(DayState state, List<AgentState> patrols,
            StrategicChronologyReplay chronology, StrategicTrajectoryCache cache,
            StrategicSearchConfig bounded, V3SupportRootContext root,
            SupportAwareTrajectoryScheduler scheduler,
            Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios,
            List<StrategicRouteCandidate> assigned) {
        return new RootComposition(state, patrols, chronology, cache, bounded, root, EMPTY_ALLOCATION,
                scheduler, portfolios, StrategicSearchObserver.NONE).compose(List.copyOf(assigned));
    }

    private static StrategicSearchNode terminalNode(ComposedTeam team) {        return new StrategicSearchNode(team.state(), List.of(), team.team().assignedCount(),
                team.team().assigned().isEmpty() ? ""
                        : Integer.toString(team.team().assigned().getFirst().firstTarget().value()),
                team.team().supportRoot());
    }

    /** The WAIT-all baseline node, so a fixture whose composition finds nothing still reports a real root. */
    private static StrategicSearchNode fallbackNode(DayState state, List<AgentState> patrols,
            StrategicChronologyReplay chronology, V3SupportRootContext root, StrategicAllocation allocation) {
        StrategicChronologyReplay.ChronologyResult replay = root.present()
                ? chronology.replaySupported(state, Map.of(), root.trajectory())
                : chronology.replay(state, Map.of());
        List<StrategicSearchState.PatrolState> values = patrols.stream()
                .map(patrol -> new StrategicSearchState.PatrolState(patrol.id(), patrol.position(), 0,
                        ((FiniteFuel) patrol.fuel()).amount(), List.of(), List.of(), List.of(), false)).toList();
        return new StrategicSearchNode(new StrategicSearchState(values, replay.remainingStock(),
                replay.claims().stream().map(StrategicChronologyReplay.Claim::position)
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                replay.collections(), replay.brands(), allocation, root.supportStateKey(0), replay.visited(),
                replay.fingerprint()), List.of(), 0, "", root);
    }

    private static Counters counters(List<RootComposition> composers, List<ComposedTeam> completed,
            int materialized, int valid, int coupled, int bestOwnIndex, int bestHybridIndex,
            StrategicSearchConfig config, int uniqueOwnScores, int uniquePhysicalPlans) {
        int routesGenerated = 0, routesRetained = 0, portfolioExpansions = 0;
        int generated = 0, unique = 0, expanded = 0, rejections = 0, duplicates = 0;
        for (RootComposition composer : composers) {
            for (StrategicRoutePortfolio.Portfolio portfolio : composer.portfolios().values()) {
                routesGenerated += portfolio.generated();
                routesRetained += portfolio.retained();
                portfolioExpansions += portfolio.expansions();
            }
            generated += composer.generated(); unique += composer.unique(); expanded += composer.expanded();
            rejections += composer.rejections(); duplicates += composer.duplicates();
        }
        return new Counters(routesGenerated, routesRetained, generated, unique, expanded, completed.size(),
                materialized, valid, coupled, portfolioExpansions, rejections, duplicates, bestOwnIndex,
                bestHybridIndex,
                config.maxTerminalEvaluations() > 0 && materialized >= config.maxTerminalEvaluations(),
                uniqueOwnScores, uniquePhysicalPlans);
    }

    private static List<Integer> childCounts(List<RootComposition> composers) {
        List<Integer> values = new ArrayList<>();
        composers.forEach(composer -> values.addAll(composer.childrenPerState()));
        return values;
    }

    private static int min(List<RootComposition> composers) {
        return childCounts(composers).stream().mapToInt(Integer::intValue).min().orElse(0);
    }

    private static int max(List<RootComposition> composers) {
        return childCounts(composers).stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static int median(List<RootComposition> composers) {
        List<Integer> values = new ArrayList<>(childCounts(composers));
        if (values.isEmpty()) return 0;
        values.sort(Integer::compareTo);
        return values.get((values.size() - 1) / 2);
    }

    private static int maxFrontier(List<RootComposition> composers) {
        return composers.stream().mapToInt(RootComposition::maxFrontier).max().orElse(0);
    }

    /**
     * PART 16: ONE root-local bounded composition. It owns its portfolios, its frontier and its complete
     * teams; the enclosing method owns the single global expansion budget and hands out shares of it.
     */
    private static final class RootComposition {
        private final DayState state;
        private final List<AgentState> patrols;
        private final StrategicChronologyReplay chronology;
        private final StrategicTrajectoryCache cache;
        private final StrategicSearchConfig config;
        private final V3SupportRootContext root;
        private final StrategicAllocation allocation;
        private final SupportAwareTrajectoryScheduler scheduler;
        private final Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios;
        private final StrategicSearchObserver observer;
        private final Map<AgentId, AgentState> byId = new LinkedHashMap<>();
        private final Set<String> seen = new LinkedHashSet<>();
        private final List<ComposedTeam> completed = new ArrayList<>();
        private final List<Integer> childrenPerState = new ArrayList<>();
        private final List<AgentId> order;
        private List<ComposedTeam> frontier = new ArrayList<>();
        private int expanded, generated, unique, rejections, duplicates, maxFrontier;

        private RootComposition(DayState state, List<AgentState> patrols,
                StrategicChronologyReplay chronology, StrategicTrajectoryCache cache,
                StrategicSearchConfig config, V3SupportRootContext root, StrategicAllocation allocation,
                SupportAwareTrajectoryScheduler scheduler,
                Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios, StrategicSearchObserver observer) {
            this.state = state; this.patrols = patrols; this.chronology = chronology; this.cache = cache;
            this.config = config; this.root = root; this.allocation = allocation; this.scheduler = scheduler;
            this.portfolios = portfolios; this.observer = observer;
            patrols.forEach(patrol -> byId.put(patrol.id(), patrol));
            this.order = branchOrder();
            ComposedTeam origin = compose(List.of());
            if (origin != null) {
                seen.add(origin.team().identity());
                frontier.add(origin);
                generated++; unique++;
                observer.onRoot(allocation, terminalNode(origin), true);
            }
        }

        private V3SupportRootContext root() { return root; }

        private Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios() { return portfolios; }

        private List<ComposedTeam> completed() { return List.copyOf(completed); }

        private int expanded() { return expanded; }

        private int generated() { return generated; }

        private int unique() { return unique; }

        private int rejections() { return rejections; }

        private int duplicates() { return duplicates; }

        private int maxFrontier() { return maxFrontier; }

        private List<Integer> childrenPerState() { return List.copyOf(childrenPerState); }

        /** ONE expansion: give the next PATROL in branch order every route its portfolio offers. */
        private boolean expand() {
            if (frontier.isEmpty()) return false;
            frontier.sort(Comparator.comparing(ComposedTeam::team, compositionOrder()));
            ComposedTeam node = frontier.removeFirst();
            expanded++;
            observer.onExpanded(terminalNode(node));
            AgentId next = order.get(node.team().assignedCount());
            AgentState patrol = byId.get(next);
            List<StrategicRouteCandidate> options = new ArrayList<>(portfolios.get(next).routes());
            int slots = Math.max(1, config.maxStrategicChildrenPerState() - 1);
            if (options.size() > slots) options = new ArrayList<>(options.subList(0, slots));
            // PART 10: "leave this PATROL idle" is always reachable — one child slot is reserved for it,
            // because the V2 witness itself leaves one PATROL with nothing at all to do.
            options.add(StrategicRouteCandidate.empty(next, patrol.position(),
                    ((FiniteFuel) patrol.fuel()).amount()));
            int children = 0;
            for (StrategicRouteCandidate option : options) {
                List<StrategicRouteCandidate> assigned = new ArrayList<>(node.team().assigned());
                assigned.add(option);
                ComposedTeam child = compose(List.copyOf(assigned));
                if (child == null) { rejections++; continue; }
                generated++; children++;
                if (!seen.add(child.team().identity())) { duplicates++; continue; }
                unique++;
                if (child.team().complete()) completed.add(child);
                else frontier.add(child);
            }
            childrenPerState.add(children);
            if (frontier.size() > config.strategicBeamWidth()) {
                frontier.sort(Comparator.comparing(ComposedTeam::team, compositionOrder()));
                frontier = new ArrayList<>(frontier.subList(0, config.strategicBeamWidth()));
            }
            maxFrontier = Math.max(maxFrontier, frontier.size());
            return true;
        }

        /**
         * PART 9: the settled truth of one partial team, from the EXISTING trajectory-faithful chronology.
         *
         * <p>Every assigned route is replayed together with the one fixed tanker trajectory, so same-stock
         * competition, arrival order, incidental refills and intermediate collections are all already decided
         * here — before a single materialisation slot is spent. Unassigned PATROLs simply contribute nothing.
         */
        private ComposedTeam compose(List<StrategicRouteCandidate> assigned) {
            Map<AgentId, List<CachedTrajectoryEffect>> effects = new LinkedHashMap<>();
            patrols.forEach(patrol -> effects.put(patrol.id(), List.of()));
            for (StrategicRouteCandidate route : assigned) {
                List<CachedTrajectoryEffect> legs = new ArrayList<>();
                for (Route leg : route.legs()) legs.add(cache.effect(route.patrolId(), leg));
                effects.put(route.patrolId(), List.copyOf(legs));
            }
            StrategicChronologyReplay.ChronologyResult replay = root.present()
                    ? StrategicTeamSearch.supportedReplay(state, patrols, effects, chronology, root, scheduler)
                    : chronology.replay(state, effects);
            if (replay == null || !replay.fuelFeasible()) return null;
            return assemble(assigned, replay);
        }

        private ComposedTeam assemble(List<StrategicRouteCandidate> assigned,
                StrategicChronologyReplay.ChronologyResult replay) {
            Set<AgentId> assignedIds = assigned.stream().map(StrategicRouteCandidate::patrolId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<AgentId> unassigned = order.stream().filter(id -> !assignedIds.contains(id)).toList();
            List<StrategicSearchState.PatrolState> states = new ArrayList<>();
            Map<AgentId, Position> endPositions = new LinkedHashMap<>();
            Map<AgentId, Integer> endElapsed = new LinkedHashMap<>();
            Map<AgentId, Integer> endFuel = new LinkedHashMap<>();
            for (AgentState patrol : patrols) {
                Position end = replay.finalPositions().getOrDefault(patrol.id(), patrol.position());
                int elapsed = Math.max(0, replay.finalElapsed().getOrDefault(patrol.id(), 0));
                int fuel = Math.max(0, replay.fuelAtElapsed().getOrDefault(patrol.id(), 0));
                endPositions.put(patrol.id(), end);
                endElapsed.put(patrol.id(), elapsed);
                endFuel.put(patrol.id(), fuel);
                states.add(new StrategicSearchState.PatrolState(patrol.id(), end, elapsed, fuel,
                        targets(assigned, patrol.id()), List.of(), List.of(),
                        assignedIds.contains(patrol.id())));
            }
            int minElapsed = endElapsed.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            StrategicSearchState searchState = new StrategicSearchState(states, replay.remainingStock(),
                    replay.claims().stream().map(StrategicChronologyReplay.Claim::position)
                            .collect(Collectors.toCollection(LinkedHashSet::new)),
                    replay.collections(), replay.brands(), allocation, root.supportStateKey(minElapsed),
                    replay.visited(), replay.fingerprint());
            TeamCompositionState team = new TeamCompositionState(root, assigned, unassigned,
                    replay.remainingStock(), replay.collections(), replay.brands(),
                    optimistic(unassigned, replay.remainingStock()), duplicated(assigned), overlap(assigned),
                    endElapsed.values().stream().mapToInt(Integer::intValue).sum(),
                    endFuel.values().stream().mapToInt(Integer::intValue).min().orElse(0),
                    replay.fingerprint(), endPositions, endElapsed, endFuel, identity(assigned));
            return new ComposedTeam(team, searchState);
        }

        private static List<Position> targets(List<StrategicRouteCandidate> assigned, AgentId patrolId) {
            return assigned.stream().filter(route -> route.patrolId().equals(patrolId))
                    .map(StrategicRouteCandidate::targets).findFirst().orElse(List.of());
        }

        /** PART 12: what the still-unassigned PATROLs could add if nothing conflicted. Ordering only. */
        private int optimistic(List<AgentId> unassigned, Map<Position, Integer> stock) {
            int total = 0;
            for (AgentId id : unassigned) {
                int best = 0;
                for (StrategicRouteCandidate route : portfolios.get(id).routes()) {
                    best = Math.max(best, (int) route.targetSet().stream()
                            .filter(position -> stock.getOrDefault(position, 0) > 0).count());
                }
                total += best;
            }
            return total;
        }

        /** Demand this team places on cells beyond what those cells actually hold. */
        private int duplicated(List<StrategicRouteCandidate> assigned) {
            Map<Position, Integer> demand = demand(assigned);
            int total = 0;
            for (Map.Entry<Position, Integer> entry : demand.entrySet()) {
                total += Math.max(0, entry.getValue() - state.spotStock().getOrDefault(entry.getKey(), 0));
            }
            return total;
        }

        private static int overlap(List<StrategicRouteCandidate> assigned) {
            return demand(assigned).values().stream().mapToInt(value -> Math.max(0, value - 1)).sum();
        }

        private static Map<Position, Integer> demand(List<StrategicRouteCandidate> assigned) {
            Map<Position, Integer> demand = new LinkedHashMap<>();
            assigned.forEach(route -> route.targetSet()
                    .forEach(position -> demand.merge(position, 1, Integer::sum)));
            return demand;
        }

        private String identity(List<StrategicRouteCandidate> assigned) {
            return root.signature() + "|n=" + assigned.size() + "|" + assigned.stream()
                    .sorted(Comparator.comparingInt(route -> route.patrolId().value()))
                    .map(route -> route.patrolId().value() + "=" + route.signature())
                    .reduce((a, b) -> a + " " + b).orElse("EMPTY");
        }

        /**
         * PART 10: the deterministic BRANCH order — the order PATROLs are decided in, nothing else.
         *
         * <p>Fewest retained legal routes first, because a PATROL with two options must not be decided after
         * the team has already been shaped around it; then the most support-dependent, because its legality
         * hangs on the tanker timeline; then the most stock-contentious, because its cells are the ones another
         * PATROL will want; then PATROL id, so the order is total. Blind id order is deliberately NOT used.
         */
        private List<AgentId> branchOrder() {
            Map<AgentId, Integer> contention = contention();
            return patrols.stream().map(AgentState::id)
                    .sorted(Comparator.comparingInt((AgentId id) -> portfolios.get(id).retained())
                            .thenComparing(Comparator.comparingInt((AgentId id) -> supportDependent(id))
                                    .reversed())
                            .thenComparing(Comparator.comparingInt(
                                    (AgentId id) -> contention.getOrDefault(id, 0)).reversed())
                            .thenComparingInt(AgentId::value))
                    .toList();
        }

        private int supportDependent(AgentId patrolId) {
            return (int) portfolios.get(patrolId).routes().stream()
                    .filter(StrategicRouteCandidate::supportDependent).count();
        }

        /** How much of a PATROL's reachable stock some other PATROL can also reach. */
        private Map<AgentId, Integer> contention() {
            Map<AgentId, Set<Position>> reachable = new LinkedHashMap<>();
            portfolios.forEach((patrolId, portfolio) -> {
                Set<Position> cells = new LinkedHashSet<>();
                portfolio.routes().forEach(route -> cells.addAll(route.targetSet()));
                reachable.put(patrolId, cells);
            });
            Map<Position, Integer> reach = new LinkedHashMap<>();
            reachable.values().forEach(cells -> cells.forEach(cell -> reach.merge(cell, 1, Integer::sum)));
            Map<AgentId, Integer> result = new LinkedHashMap<>();
            reachable.forEach((patrolId, cells) -> result.put(patrolId, cells.stream()
                    .mapToInt(cell -> Math.max(0, reach.getOrDefault(cell, 1) - 1)).sum()));
            return result;
        }
    }
}
