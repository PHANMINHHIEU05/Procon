package vn.ptit.procon.planner.v3;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.HybridCalibratedMarginEvaluation;

/**
 * {@code V3ShadowPlanner} — the Phase 2.7 shadow adapter. It consumes a {@link DayState} and returns data.
 *
 * <p>The algorithm is NOT duplicated here: the class composes the existing Phase 1 opportunity graph, the
 * existing mobile R3 support-root universe and the existing Phase 2.6 bounded team-composition search, all
 * through their public entry points.
 *
 * <p><strong>Why accidental submission is structurally impossible.</strong> This class lives in
 * {@code bot-planner}, which does not depend on {@code bot-protocol} at all, so no HTTP client type is even
 * on its compile classpath. It has no fields, no callbacks, and its single method returns
 * {@link V3ShadowEvaluation} — numbers and signatures, never a plan and never an action.
 *
 * <p><strong>Interruption safety.</strong> The wall budget is expressed through the search's OWN existing
 * deadline hook, {@link StrategicSearchConfig#maxPlanningMillis()}. Nothing in the search was changed for
 * shadow use.
 */
public final class V3ShadowPlanner {

    /** The conservative observation profile: the middle frozen Phase 2.3 budget, applied to every day. */
    public static final int SHADOW_BEAM_WIDTH = 32;
    public static final int SHADOW_EXPANDED_STATES = 512;
    public static final int SHADOW_CHILDREN_PER_STATE = 32;
    public static final int SHADOW_ALLOCATION_CANDIDATES = 16;
    public static final int SHADOW_ROUTE_DEPTH = 10;
    public static final int SHADOW_TERMINAL_EVALUATIONS = 64;

    /** The value used when a REFUEL trajectory in the incumbent plan matches no exported R3 root. */
    public static final String UNMATCHED_SUPPORT_ROOT = "UNMATCHED";

    /**
     * The shadow search configuration. It is derived from the state's SHAPE only — never from a fixture
     * name — and the only tunable is the wall budget, which comes from runtime configuration.
     *
     * @param maxPlanningMillis the shadow wall budget; this is an observation bound and is never a
     *        production action deadline
     */
    public static StrategicSearchConfig shadowConfig(long maxPlanningMillis) {
        if (maxPlanningMillis <= 0) {
            throw new IllegalArgumentException("Shadow budget must be positive: " + maxPlanningMillis);
        }
        return new StrategicSearchConfig(SHADOW_BEAM_WIDTH, SHADOW_EXPANDED_STATES,
                SHADOW_CHILDREN_PER_STATE, SHADOW_ALLOCATION_CANDIDATES, SHADOW_ROUTE_DEPTH,
                SHADOW_TERMINAL_EVALUATIONS, maxPlanningMillis).withCompositionSearch(true);
    }

    /**
     * Evaluates one day in shadow. The incumbent plan is used exactly twice: as the search seed, so the
     * safe figure is the one V3 would really keep, and as the comparison baseline, scored by the same
     * frozen objective.
     *
     * @param state the immutable authoritative day state V2/R3 planned from
     * @param incumbent the plan V2/R3 already submitted; never modified, never re-submitted
     */
    public V3ShadowEvaluation evaluate(DayState state, TeamPlan incumbent, StrategicSearchConfig config) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(incumbent, "Incumbent plan must not be null");
        Objects.requireNonNull(config, "Config must not be null");
        FrozenObjectiveEvaluator evaluator = new FrozenObjectiveEvaluator(state);
        StrategicOracleEvaluation v2 = evaluator.evaluate(incumbent).orElseThrow(
                () -> new IllegalArgumentException("Incumbent plan is not scoreable by the frozen objective"));
        V3SupportRootUniverse universe = V3SupportRootUniverse.of(state);
        long started = config.nanoClock().getAsLong();
        StrategicTeamComposition.Outcome outcome = new StrategicTeamComposition().run(state,
                config.withCompositionSearch(true), List.of(incumbent), 0, 0,
                StrategicSearchObserver.NONE, universe);
        long elapsedMillis = Math.max(0L, (config.nanoClock().getAsLong() - started) / 1_000_000L);
        StrategicSearchResult search = outcome.search();
        StrategicOracleEvaluation raw = search.rawWinner();
        StrategicOracleEvaluation safe = search.winner();
        StrategicTeamComposition.Counters counters = outcome.counters();
        return new V3ShadowEvaluation(state.day().value(), v2.ownSemiCollections(), v2.ownSemiBrands(),
                v2.coupledOwnCollections(), v2.baselineOpponentCollections(),
                v2.coupledOpponentCollections(), v2.hybridMarginScore4(),
                incumbentSupportRoot(incumbent, universe), v2.physicalSignature(),
                raw.ownSemiCollections(), raw.ownSemiBrands(), raw.coupledOwnCollections(),
                raw.baselineOpponentCollections(), raw.coupledOpponentCollections(),
                raw.hybridMarginScore4(), raw.physicalSignature(), safe.ownSemiCollections(),
                safe.hybridMarginScore4(), safe.physicalSignature(), search.fallbackUsed(),
                search.rawSupportRootSignature(), strategicSignature(search), elapsedMillis,
                search.diagnostics().deadlineBudgetExceeded(), counters.partialStatesExpanded(),
                counters.completeTeamCandidates(), counters.materializedPlans(),
                counters.coupledEvaluations(),
                search.diagnostics().strategicSearchPathfindingExecutions(), verdict(raw, v2),
                verdict(safe, v2));
    }

    /**
     * The frozen terminal comparator, three-way. Quantities are never compared directly, so two different
     * physical plans the comparator cannot separate are reported as {@code TIE}.
     */
    private static V3ShadowEvaluation.Verdict verdict(StrategicOracleEvaluation candidate,
            StrategicOracleEvaluation baseline) {
        if (HybridCalibratedMarginEvaluation.compare(candidate.objective(), baseline.objective()) < 0) {
            return V3ShadowEvaluation.Verdict.WIN;
        }
        if (HybridCalibratedMarginEvaluation.compare(baseline.objective(), candidate.objective()) < 0) {
            return V3ShadowEvaluation.Verdict.LOSS;
        }
        return V3ShadowEvaluation.Verdict.TIE;
    }

    /**
     * Names the support root the incumbent plan actually flew, by matching its REFUEL action sequence
     * against the roots R3 itself exported. V3 never invents a label here.
     */
    private static String incumbentSupportRoot(TeamPlan incumbent, V3SupportRootUniverse universe) {
        boolean anyRefuelMovement = false;
        for (V3SupportRootContext root : universe.mobileRoots()) {
            List<AgentAction> actions = incumbent.actionsFor(root.refuelAgentId());
            if (actions == null) continue;
            anyRefuelMovement = true;
            if (actions.equals(root.refuelActions())) return root.signature();
        }
        return anyRefuelMovement ? UNMATCHED_SUPPORT_ROOT : V3SupportRootContext.NO_REFUEL;
    }

    /** The strategic-level identity of the raw winner: its support root plus each PATROL's route. */
    private static String strategicSignature(StrategicSearchResult search) {
        StrategicSearchNode node = search.winningNode();
        String routes = node.state().patrols().stream()
                .sorted(Comparator.comparingInt(patrol -> patrol.patrolId().value()))
                .map(patrol -> patrol.patrolId().value() + ":" + (patrol.route().isEmpty() ? "STOP"
                        : patrol.route().stream().map(position -> String.valueOf(position.value()))
                                .collect(Collectors.joining(">"))))
                .collect(Collectors.joining("/"));
        return node.supportRoot().signature() + "|" + routes;
    }

    /** Convenience for the offline harness: scores the incumbent alone, without running any search. */
    public static Optional<StrategicOracleEvaluation> scoreIncumbent(DayState state, TeamPlan plan) {
        return new FrozenObjectiveEvaluator(Objects.requireNonNull(state)).evaluate(Objects.requireNonNull(plan));
    }
}
