package vn.ptit.procon.benchmark;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.FrozenObjectiveEvaluator;
import vn.ptit.procon.planner.v3.StrategicAllocation;
import vn.ptit.procon.planner.v3.StrategicOpportunityGraphBuilder;
import vn.ptit.procon.planner.v3.StrategicOracleEvaluation;
import vn.ptit.procon.planner.v3.StrategicSearchConfig;
import vn.ptit.procon.planner.v3.StrategicSearchNode;
import vn.ptit.procon.planner.v3.StrategicSearchObserver;
import vn.ptit.procon.planner.v3.StrategicSearchState;
import vn.ptit.procon.planner.v3.StrategicTeamComposition;
import vn.ptit.procon.planner.v3.V3EdgeRetentionPolicy;
import vn.ptit.procon.planner.v3.V3ShadowPlanner;
import vn.ptit.procon.planner.v3.V3SupportRootUniverse;

/**
 * PART 8: where the V3 milliseconds actually go, measured without touching one line of {@code src/main}.
 *
 * <p>Two independent measurements are combined. The <strong>outer</strong> one times the three top-level
 * stages a V3 evaluation performs — opportunity-graph construction, support-root universe construction and
 * the bounded team-composition search — by calling their existing public entry points in the same order the
 * search does. The <strong>inner</strong> one attributes the composition time to phases by timestamping the
 * no-op {@link StrategicSearchObserver} callbacks and charging each interval to the phase its closing event
 * belongs to: expansion (frontier/expand/edge/child), terminal handling (materialisation plus coupled
 * evaluation) and the root/tail remainder.
 *
 * <p>Because {@code StrategicSearchObserver} is a pure observation interface with default no-op methods,
 * profiling adds no behaviour to the search: the plan, the counters and the verdicts are identical to an
 * unprofiled run. The only cost is a {@code System.nanoTime()} read per event.
 */
public final class V3CorpusProfiler {

    /** The phases a composition search's wall-clock is charged to. */
    public enum Phase { ROOT, EXPANSION, TERMINAL, TAIL }

    private final long budgetMillis;

    public V3CorpusProfiler(long budgetMillis) {
        this.budgetMillis = budgetMillis;
    }

    /** One profiled day. All figures are milliseconds unless the name says otherwise. */
    public record Profile(
            String matchId,
            int day,
            String sizeClass,
            int agentCount,
            int stepBudget,
            long v2IncumbentMillis,
            long graphMillis,
            long universeMillis,
            long compositionMillis,
            Map<Phase, Long> phaseMillis,
            long singleEvaluationMicros,
            long coupledShareMillis,
            long measuredMaterializationMillis,
            long measuredCoupledMillis,
            long measuredSearchMillis,
            int events,
            int statesExpanded,
            int completeTeams,
            int materialized,
            int coupledEvaluations,
            int routesGenerated,
            int routesRetained) {

        /** The share of composition time the coupled objective evaluations alone account for. */
        public double coupledSharePercent() {
            return compositionMillis == 0 ? 0.0 : 100.0 * coupledShareMillis / compositionMillis;
        }

        /**
         * The same share taken from the search's OWN stopwatch rather than from a per-evaluation estimate.
         * The two are independent measurements of one quantity, so a large gap between them is itself a
         * finding rather than a number to average away.
         */
        public double measuredCoupledSharePercent() {
            return compositionMillis == 0 ? 0.0 : 100.0 * measuredCoupledMillis / compositionMillis;
        }

        public long phase(Phase phase) {
            return phaseMillis.getOrDefault(phase, 0L);
        }

        public String line() {
            return String.format(Locale.ROOT,
                    "PROFILE match=%s day=%d size=%s agents=%d stepBudget=%d v2Millis=%d graphMillis=%d"
                            + " universeMillis=%d compositionMillis=%d rootMillis=%d expansionMillis=%d"
                            + " terminalMillis=%d tailMillis=%d evalMicros=%d coupledShareMillis=%d"
                            + " coupledSharePct=%.1f measuredMaterializeMillis=%d measuredCoupledMillis=%d"
                            + " measuredCoupledPct=%.1f measuredSearchMillis=%d events=%d states=%d teams=%d"
                            + " materialized=%d coupled=%d routesGenerated=%d routesRetained=%d",
                    matchId, day, sizeClass, agentCount, stepBudget, v2IncumbentMillis, graphMillis,
                    universeMillis, compositionMillis, phase(Phase.ROOT), phase(Phase.EXPANSION),
                    phase(Phase.TERMINAL), phase(Phase.TAIL), singleEvaluationMicros, coupledShareMillis,
                    coupledSharePercent(), measuredMaterializationMillis, measuredCoupledMillis,
                    measuredCoupledSharePercent(), measuredSearchMillis, events, statesExpanded,
                    completeTeams, materialized, coupledEvaluations, routesGenerated, routesRetained);
        }
    }

    /** Charges every inter-event interval to the phase of the event that closed it. */
    private static final class PhaseTimer implements StrategicSearchObserver {

        private final Map<Phase, Long> nanos = new EnumMap<>(Phase.class);
        private long last = System.nanoTime();
        private int events;

        private void charge(Phase phase) {
            long now = System.nanoTime();
            nanos.merge(phase, now - last, Long::sum);
            last = now;
            events++;
        }

        Map<Phase, Long> millis(long tailNanos) {
            Map<Phase, Long> result = new EnumMap<>(Phase.class);
            nanos.forEach((phase, value) -> result.put(phase, value / 1_000_000L));
            result.merge(Phase.TAIL, tailNanos / 1_000_000L, Long::sum);
            return result;
        }

        int events() {
            return events;
        }

        @Override
        public void onRoot(StrategicAllocation allocation, StrategicSearchNode node, boolean unique) {
            charge(Phase.ROOT);
        }

        @Override
        public void onFrontier(int iteration, int frontierSize, int beamWidth) {
            charge(Phase.EXPANSION);
        }

        @Override
        public void onExpanded(StrategicSearchNode node) {
            charge(Phase.EXPANSION);
        }

        @Override
        public void onEdgeCandidate(int depth, AgentId patrolId, Position from, Position to,
                boolean allocationPreferred) {
            charge(Phase.EXPANSION);
        }

        @Override
        public void onChildRejected(int depth, AgentId patrolId, Position from, Position to,
                String reason) {
            charge(Phase.EXPANSION);
        }

        @Override
        public void onChildAccepted(int depth, AgentId patrolId, Position from, Position to,
                StrategicSearchState child) {
            charge(Phase.EXPANSION);
        }

        @Override
        public void onStopChild(int depth, AgentId patrolId, boolean unique) {
            charge(Phase.EXPANSION);
        }

        @Override
        public void onQuotaReached(int depth, AgentId patrolId, int skippedCandidates) {
            charge(Phase.EXPANSION);
        }

        @Override
        public void onBeamRetained(int iteration, List<StrategicSearchNode> candidates,
                List<StrategicSearchNode> retained) {
            charge(Phase.EXPANSION);
        }

        @Override
        public void onTerminal(StrategicSearchNode node, boolean materialized, boolean valid,
                StrategicOracleEvaluation evaluation) {
            charge(Phase.TERMINAL);
        }
    }

    /**
     * Profiles one rebuilt live day. The stages are timed in the order the search performs them, and the
     * composition run gets the phase timer, so {@code compositionMillis} and the phase sum measure the same
     * interval from two directions.
     */
    public Profile profile(V3CorpusDay day) {
        DayState state = day.rebuild();

        long v2Started = System.nanoTime();
        TeamPlan incumbent = new JointTeamBeamR3Planner().plan(state);
        long v2Millis = millisSince(v2Started);

        long graphStarted = System.nanoTime();
        new StrategicOpportunityGraphBuilder().build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        long graphMillis = millisSince(graphStarted);

        long universeStarted = System.nanoTime();
        V3SupportRootUniverse universe = V3SupportRootUniverse.of(state);
        long universeMillis = millisSince(universeStarted);

        // The micro-cost of ONE frozen objective evaluation, measured on the same state, is what turns the
        // coupled-evaluation COUNT into a share of milliseconds.
        long evaluationMicros = singleEvaluationMicros(state);

        StrategicSearchConfig config = V3ShadowPlanner.shadowConfig(budgetMillis);
        PhaseTimer timer = new PhaseTimer();
        long compositionStarted = System.nanoTime();
        StrategicTeamComposition.Outcome outcome = new StrategicTeamComposition()
                .run(state, config.withCompositionSearch(true), List.of(incumbent), 0, 0, timer, universe);
        long compositionNanos = System.nanoTime() - compositionStarted;
        long tailNanos = Math.max(0, System.nanoTime() - timer.last);

        StrategicTeamComposition.Counters counters = outcome.counters();
        long coupledShareMillis = counters.coupledEvaluations() * evaluationMicros / 1000L;
        // The search keeps its own stopwatches for exactly these two stages; preferring them over the
        // estimate above is what turns "probably the evaluator" into evidence.
        var diagnostics = outcome.search().diagnostics();
        return new Profile(day.matchId(), day.day(), day.sizeClass(), day.agentCount(), day.stepBudget(),
                v2Millis, graphMillis, universeMillis, compositionNanos / 1_000_000L,
                timer.millis(tailNanos), evaluationMicros, coupledShareMillis,
                diagnostics.materializationMillis(), diagnostics.coupledEvaluationMillis(),
                diagnostics.searchMillis(), timer.events(),
                counters.partialStatesExpanded(), counters.completeTeamCandidates(),
                counters.materializedPlans(), counters.coupledEvaluations(), counters.routesGenerated(),
                counters.routesRetained());
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /** Median of a few repeats: one evaluation is far too fast to time once meaningfully. */
    private static long singleEvaluationMicros(DayState state) {
        FrozenObjectiveEvaluator evaluator = new FrozenObjectiveEvaluator(state);
        TeamPlan probe = SafePlanFactory.waitAll(state);
        evaluator.evaluate(probe);
        List<Long> samples = new ArrayList<>();
        for (int repeat = 0; repeat < 9; repeat++) {
            long started = System.nanoTime();
            evaluator.evaluate(probe);
            samples.add((System.nanoTime() - started) / 1000L);
        }
        samples.sort(Long::compareTo);
        return samples.get(samples.size() / 2);
    }

    public List<Profile> profileAll(List<V3CorpusDay> days) {
        List<Profile> profiles = new ArrayList<>();
        for (V3CorpusDay day : days) {
            profiles.add(profile(day));
        }
        return profiles;
    }

    /**
     * The bottleneck summary the PART 9 hypothesis has to be argued from: for the HEAVY cohort
     * (MEDIUM + LARGE, i.e. from the 6-agent/60-step shape upwards), which stage dominates and how much
     * of the composition time the coupled evaluations alone explain.
     */
    public static String bottleneck(List<Profile> profiles) {
        List<Profile> large = profiles.stream()
                .filter(profile -> profile.agentCount() >= 6 && profile.stepBudget() >= 60)
                .toList();
        List<Profile> scope = large.isEmpty() ? profiles : large;
        if (scope.isEmpty()) {
            return "BOTTLENECK cohort=NONE states=0";
        }
        long graph = 0;
        long universe = 0;
        long expansion = 0;
        long terminal = 0;
        long root = 0;
        long tail = 0;
        long coupled = 0;
        long composition = 0;
        long measuredMaterialize = 0;
        long measuredCoupled = 0;
        long evalMicros = 0;
        for (Profile profile : scope) {
            graph += profile.graphMillis();
            universe += profile.universeMillis();
            expansion += profile.phase(Phase.EXPANSION);
            terminal += profile.phase(Phase.TERMINAL);
            root += profile.phase(Phase.ROOT);
            tail += profile.phase(Phase.TAIL);
            coupled += profile.coupledShareMillis();
            composition += profile.compositionMillis();
            measuredMaterialize += profile.measuredMaterializationMillis();
            measuredCoupled += profile.measuredCoupledMillis();
            evalMicros += profile.singleEvaluationMicros();
        }
        int states = scope.size();
        return String.format(Locale.ROOT,
                "BOTTLENECK cohort=%s states=%d meanGraphMillis=%d meanUniverseMillis=%d"
                        + " meanCompositionMillis=%d meanRootMillis=%d meanExpansionMillis=%d"
                        + " meanTerminalMillis=%d meanTailMillis=%d meanCoupledShareMillis=%d"
                        + " coupledSharePct=%.1f meanMeasuredMaterializeMillis=%d"
                        + " meanMeasuredCoupledMillis=%d measuredCoupledPct=%.1f meanEvalMicros=%d"
                        + " dominant=%s measuredDominant=%s",
                large.isEmpty() ? "ALL" : "HEAVY", states, graph / states, universe / states,
                composition / states, root / states, expansion / states, terminal / states,
                tail / states, coupled / states,
                composition == 0 ? 0.0 : 100.0 * coupled / composition,
                measuredMaterialize / states, measuredCoupled / states,
                composition == 0 ? 0.0 : 100.0 * measuredCoupled / composition, evalMicros / states,
                dominant(graph, universe, expansion, terminal, tail),
                measuredDominant(graph, universe, expansion - measuredMaterialize - measuredCoupled,
                        measuredMaterialize, measuredCoupled));
    }

    /**
     * The same verdict argued only from stopwatches the search itself keeps: graph construction, support
     * universe construction, whatever expansion time is left once the two terminal stages are subtracted, the
     * materialisation stopwatch and the coupled-evaluation stopwatch.
     */
    private static String measuredDominant(long graph, long universe, long searchRemainder,
            long materialize, long coupledEvaluation) {
        Map<String, Long> stages = new java.util.LinkedHashMap<>();
        stages.put("GRAPH", graph);
        stages.put("SUPPORT_UNIVERSE", universe);
        stages.put("COMPOSITION_REMAINDER", Math.max(0, searchRemainder));
        stages.put("MATERIALIZATION", materialize);
        stages.put("COUPLED_EVALUATION", coupledEvaluation);
        return stages.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("NONE");
    }

    private static String dominant(long graph, long universe, long expansion, long terminal, long tail) {
        Map<String, Long> stages = new java.util.LinkedHashMap<>();
        stages.put("GRAPH", graph);
        stages.put("SUPPORT_UNIVERSE", universe);
        stages.put("EXPANSION", expansion);
        stages.put("TERMINAL_MATERIALIZATION", terminal);
        stages.put("TAIL_SELECTION", tail);
        return stages.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("NONE");
    }

    public static void main(String[] args) throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of(V3CorpusReplay.DEFAULT_CORPUS_DIRECTORY);
        long budget = V3CorpusReplay.UNCAPPED_BUDGET_MILLIS;
        String filterMatch = null;
        int filterDay = -1;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--corpus" -> root = java.nio.file.Path.of(args[++index]);
                case "--budget-millis" -> budget = Long.parseLong(args[++index]);
                case "--match" -> filterMatch = args[++index];
                case "--day" -> filterDay = Integer.parseInt(args[++index]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }
        List<V3CorpusDay> days = V3CorpusDay.loadAll(root, true);
        if (filterMatch != null) {
            String m = filterMatch;
            int d = filterDay;
            days = days.stream().filter(day -> day.matchId().equals(m) && (d < 0 || day.day() == d)).toList();
        }
        System.out.println("PROFILER_LOADED root=" + root + " days=" + days.size());
        List<Profile> profiles = new V3CorpusProfiler(budget).profileAll(days);
        profiles.forEach(profile -> System.out.println(profile.line()));
        System.out.println(bottleneck(profiles));
    }
}
