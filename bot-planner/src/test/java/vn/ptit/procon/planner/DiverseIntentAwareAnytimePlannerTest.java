package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/**
 * M11 diversity-aware bounded search over the unchanged M10 corrected complete-plan objective.
 *
 * <p>Every comparison here runs both production planner modes at the same production budget
 * ({@code 64/48/4}) and decides the winner with {@link IntentAwarePlanEvaluation#preference()}
 * only. No fixture hard-codes an expected plan.</p>
 */
class DiverseIntentAwareAnytimePlannerTest {

    private static final AnytimePlannerConfig PRODUCTION = AnytimePlannerConfig.defaults();
    private static final AnytimePlannerConfig TINY = new AnytimePlannerConfig(3, 4, 4);

    @Test
    void diversityStatesAreActuallyExpandedByTheProductionSearch() {
        AnytimePlanResult result = diverse(PRODUCTION).planWithStats(hiddenBranchState());
        DiverseSearchStats diversity = result.diverseSearchStats().orElseThrow();

        assertTrue(diversity.diversityExpansions() > 0,
                "The 64-state budget must actually reach the diversity reserve");
        assertTrue(diversity.uniqueStrategyKeysExpanded() > 1,
                "Expansions must span more than one strategic opening");
        assertTrue(diversity.uniqueStrategyKeysExpanded() <= diversity.uniqueStrategyKeysGenerated());
        assertTrue(diversity.maxStrategyExpansionCount() < result.stats().expandedStates(),
                "No single strategy may consume the whole expansion budget");
        assertEquals(result.stats().expandedStates(), diversity.totalExpansions());
        assertEquals(diversity.qualityExpansions() + diversity.diversityExpansions(),
                diversity.totalExpansions());
        assertTrue(diversity.qualityExpansions() > diversity.diversityExpansions(),
                "Quality expansion must stay the majority of the budget");
    }

    @Test
    void hiddenBetterBranchIsReachedOnlyByTheDiverseSearchAtTheSameBudget() {
        DayState state = hiddenBranchState();

        AnytimePlanResult m10 = intentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11 = diverse(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation quality = m10.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation diverse = m11.intentAwareEvaluation().orElseThrow();

        assertEquals(PRODUCTION.maxExpandedStates(), m10.stats().expandedStates());
        assertEquals(PRODUCTION.maxExpandedStates(), m11.stats().expandedStates());
        assertTrue(m10.stats().budgetExhausted());
        assertTrue(m11.stats().budgetExhausted());
        assertTrue(diverse.betterThan(quality),
                "M11 must find the branch the quality-first search misses");
        assertTrue(IntentAwarePlanEvaluation.preference().compare(diverse, quality) < 0);
        assertFalse(quality.betterThan(diverse));
        assertTrue(m11.diverseSearchStats().orElseThrow().diversityExpansions() > 0);
        assertTrue(m10.diverseSearchStats().isEmpty(), "M10 must stay free of M11 search state");
    }

    @Test
    void completedPlanObjectiveIsIdenticalForBothSearchModes() {
        DayState state = hiddenBranchState();

        AnytimePlanResult m10 = intentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11 = diverse(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation quality = m10.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation diverse = m11.intentAwareEvaluation().orElseThrow();

        // Each mode's own reported objective must equal the objective recomputed from the raw
        // production forecast pipeline, so a single evaluator scores both already-completed plans.
        IntentAwarePlanEvaluation recomputedQuality =
                objectiveOf(state, m10.plan(), m10.evaluation());
        IntentAwarePlanEvaluation recomputedDiverse =
                objectiveOf(state, m11.plan(), m11.evaluation());

        assertEquals(quality, recomputedQuality);
        assertEquals(diverse, recomputedDiverse);
        assertEquals(
                IntentAwarePlanEvaluation.preference().compare(diverse, quality) < 0,
                IntentAwarePlanEvaluation.preference().compare(recomputedDiverse, recomputedQuality) < 0);
        // Cross-scoring: the M11 plan judged with the M10 forecast configuration keeps its verdict.
        assertTrue(recomputedDiverse.betterThan(recomputedQuality));
        assertFalse(recomputedQuality.betterThan(recomputedDiverse));
    }

    @Test
    void repeatedRunsProduceIdenticalPlansStatisticsAndDiagnostics() {
        DayState state = hiddenBranchState();

        AnytimePlanResult first = diverse(PRODUCTION).planWithStats(state);
        AnytimePlanResult second = diverse(PRODUCTION).planWithStats(state);

        assertEquals(actionsOf(first.plan()), actionsOf(second.plan()));
        assertEquals(first.evaluation(), second.evaluation());
        assertEquals(first.evaluation().deterministicSignature(),
                second.evaluation().deterministicSignature());
        assertEquals(first.intentAwareEvaluation(), second.intentAwareEvaluation());
        assertEquals(first.stats(), second.stats());
        assertEquals(first.diverseSearchStats(), second.diverseSearchStats());
        assertEquals(first.stats().expandedStates(), second.stats().expandedStates());
    }

    @Test
    void noOpponentKeepsTheDiverseSearchOnTheUnadjustedCollectionObjective() {
        DayState state = lineState(9, 8, List.of(spot("A", 1), spot("B", 2), spot("C", 6)), List.of());

        AnytimePlanResult result = diverse(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation evaluation = result.intentAwareEvaluation().orElseThrow();

        assertEquals(0, evaluation.likelyClaimedFirstCollections());
        assertEquals(0, evaluation.tieCollections());
        assertEquals(result.evaluation().udonTotal(), evaluation.forecastRealizableCollections());
        assertEquals(result.evaluation().teamBrandCount(), evaluation.forecastRealizableBrandCount());
        assertEquals(result.evaluation().udonTotal() * 4, evaluation.adjustedCollectionScore().value());
        assertTrue(result.stats().completedPlans() > 0);
    }

    @Test
    void liveShapedSameBudgetComparisonNeverReturnsAWorsePlan() {
        DayState state = liveShapedState();
        OpponentIntentForecast forecast = new OpponentIntentForecaster().forecast(state);

        AnytimePlanResult m10 = intentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11 = diverse(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation quality = m10.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation diverse = m11.intentAwareEvaluation().orElseThrow();
        DiverseSearchStats diversity = m11.diverseSearchStats().orElseThrow();

        assertEquals(8, forecast.stockedSpotCount());
        assertEquals(4, forecast.observedAgentCount());
        assertEquals(3, forecast.collectionEligibleAgentCount());
        assertFalse(quality.betterThan(diverse), "M11 must never return a worse plan here");
        assertTrue(diverse.betterThan(quality), "M11 must return a strictly better plan here");
        assertEquals(PRODUCTION.maxExpandedStates(), m11.stats().expandedStates());
        assertTrue(diversity.uniqueStrategyKeysExpanded() > 1);
        assertTrue(diversity.uniqueStrategyKeysGenerated()
                >= diversity.uniqueStrategyKeysExpanded());
        assertTrue(diverse.forecastRealizableBrandCount() <= diverse.localTeamBrandCount());
        assertTrue(diverse.forecastRealizableCollections() <= diverse.base().udonTotal());
    }

    /**
     * Every quantity the M11 report quotes comes from this one same-budget production run, so the
     * report never mixes two numeric scales. Only the unchanged eleven-criterion M10 objective
     * decides the winner; {@code movementSteps} and the strategy-key counters stay diagnostics.
     */
    @Test
    void liveShapedComparisonReportsTheSameQuantitiesForBothModes() {
        DayState state = liveShapedState();

        AnytimePlanResult m10 = intentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11 = diverse(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation quality = m10.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation diverse = m11.intentAwareEvaluation().orElseThrow();
        DiverseSearchStats diversity = m11.diverseSearchStats().orElseThrow();

        // Criterion 1 ties, so criterion 2 - the intent-adjusted collection score - decides.
        assertEquals(4, quality.forecastRealizableBrandCount());
        assertEquals(4, diverse.forecastRealizableBrandCount());
        assertEquals(18, quality.adjustedCollectionScore().value());
        assertEquals(24, diverse.adjustedCollectionScore().value());
        assertEquals(6, quality.forecastRealizableCollections());
        assertEquals(6, diverse.forecastRealizableCollections());
        assertEquals(7, quality.base().udonTotal());
        assertEquals(7, diverse.base().udonTotal());
        assertEquals(1, quality.likelyClaimedFirstCollections());
        assertEquals(1, diverse.likelyClaimedFirstCollections());
        assertEquals(3, quality.tieCollections());
        assertEquals(0, diverse.tieCollections());
        assertEquals(38, quality.base().movementSteps());
        assertEquals(32, diverse.base().movementSteps());
        assertEquals(64, m10.stats().expandedStates());
        assertEquals(64, m11.stats().expandedStates());
        assertEquals(22, diversity.uniqueStrategyKeysGenerated());
        assertEquals(19, diversity.uniqueStrategyKeysExpanded());
        assertEquals(49, diversity.qualityExpansions());
        assertEquals(15, diversity.diversityExpansions());
    }

    @Test
    void searchStaysBoundedInStatesFrontierAndCandidatesPerState() {
        AnytimePlanResult result = diverse(PRODUCTION).planWithStats(liveShapedState());
        AnytimeSearchStats stats = result.stats();
        DiverseSearchStats diversity = result.diverseSearchStats().orElseThrow();

        assertTrue(stats.expandedStates() <= PRODUCTION.maxExpandedStates());
        assertTrue(diversity.frontierPeak() <= PRODUCTION.maxFrontierSize());
        assertTrue(stats.candidateRetained()
                <= PRODUCTION.topCandidatesPerState() * stats.expandedStates());
        assertEquals(stats.candidateRetained(),
                diversity.candidateEliteSelected() + diversity.candidateDiverseSelected());
        assertTrue(diversity.candidateDiverseSelected() > 0,
                "The production run must actually use the per-state diversity reserve");
        assertTrue(diversity.candidateEliteSelected() >= diversity.candidateDiverseSelected(),
                "At least half of per-state capacity must stay elite quality-first");
        assertTrue(diversity.statesRejectedByFrontierLimit() <= stats.prunedStates());
        assertTrue(diversity.statesRejectedByExactDedup() <= stats.duplicateStates());
        assertTrue(diversity.strategyBucketsSeen() >= diversity.uniqueStrategyKeysExpanded());
    }

    @Test
    void singleStrategicOpeningFallsBackToPureQualitySearch() {
        DayState state = lineState(7, 6, List.of(spot("A", 1)), List.of());

        AnytimePlanResult m10 = intentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11 = diverse(PRODUCTION).planWithStats(state);
        DiverseSearchStats diversity = m11.diverseSearchStats().orElseThrow();

        assertEquals(0, diversity.diversityExpansions(),
                "One strategic opening leaves nothing for the diversity reserve");
        assertEquals(m11.stats().expandedStates(), diversity.qualityExpansions());
        assertEquals(actionsOf(m10.plan()), actionsOf(m11.plan()));
        assertEquals(m10.evaluation(), m11.evaluation());
        assertEquals(m10.intentAwareEvaluation(), m11.intentAwareEvaluation());
    }

    @Test
    void tinyBudgetSpendsEverythingOnTheGloballyBestEliteBranch() {
        DayState state = hiddenBranchState();

        AnytimePlanResult m10 = intentAware(TINY).planWithStats(state);
        AnytimePlanResult m11 = diverse(TINY).planWithStats(state);
        DiverseSearchStats diversity = m11.diverseSearchStats().orElseThrow();

        assertTrue(m11.stats().expandedStates() <= TINY.maxExpandedStates());
        assertEquals(0, diversity.diversityExpansions(),
                "The elite lane must own a budget too small to reach an exploration turn");
        assertEquals(m11.stats().expandedStates(), diversity.qualityExpansions());
        assertEquals(m10.stats().expandedStates(), m11.stats().expandedStates());
        assertTrue(m11.stats().completedPlans() > 0);
        assertTrue(diversity.frontierPeak() <= TINY.maxFrontierSize());
    }

    @Test
    void diagnosticsExposeHowTheBudgetWasSpentAcrossOpeningStrategies() {
        DayState state = liveShapedState();
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new DiverseIntentAwareAnytimePlanner(
                    PRODUCTION,
                    OpponentIntentConfig.defaults(),
                    IntentAdjustmentWeights.defaults(),
                    DiverseSearchConfig.defaults(),
                    true).planWithStats(state);
        } finally {
            System.setOut(original);
        }
        String logs = captured.toString(StandardCharsets.UTF_8);

        assertTrue(logs.contains("ANYTIME_DIVERSE_INTENT_START day=0"));
        assertTrue(logs.contains("incumbentForecastBrands="));
        assertTrue(logs.contains("incumbentIntentScore="));
        assertTrue(logs.contains("incumbentForecastRealizable="));
        assertTrue(logs.contains("incumbentRawUdon="));
        assertTrue(logs.contains("frontierLimit=48"));
        assertTrue(logs.contains("candidateLimit=4"));
        assertTrue(logs.contains("ANYTIME_DIVERSE_INTENT_DONE day=0"));
        assertTrue(logs.contains("uniqueStrategyKeysGenerated="));
        assertTrue(logs.contains("uniqueStrategyKeysExpanded="));
        assertTrue(logs.contains("qualityExpansions="));
        assertTrue(logs.contains("diversityExpansions="));
        assertTrue(logs.contains("maxStrategyExpansionCount="));
        assertTrue(logs.contains("frontierPeak="));
        assertTrue(logs.contains("budgetExhausted="));
        assertTrue(logs.contains("SEARCH_DIVERSITY_SUMMARY day=0"));
        assertTrue(logs.contains("candidateEliteSelected="));
        assertTrue(logs.contains("candidateDiverseSelected="));
        assertTrue(logs.contains("frontierEliteRetained="));
        assertTrue(logs.contains("frontierDiverseRetained="));
        assertTrue(logs.contains("strategyBucketsSeen="));
        assertTrue(logs.contains("statesRejectedByExactDedup="));
        assertTrue(logs.contains("statesRejectedByFrontierLimit="));
        assertFalse(logs.contains("ANYTIME_INTENT_AWARE_DONE"),
                "M11 must not impersonate the M10 search event");
        assertEquals(1, logs.lines()
                .filter(line -> line.startsWith("SEARCH_DIVERSITY_SUMMARY ")).count());
        assertEquals(1, logs.lines()
                .filter(line -> line.startsWith("ANYTIME_DIVERSE_INTENT_DONE ")).count());
    }

    /** Structural view of a plan, since {@link TeamPlan} has reference identity semantics. */
    private static Map<Integer, List<String>> actionsOf(TeamPlan plan) {
        Map<Integer, List<String>> actions = new java.util.TreeMap<>();
        plan.actionsByAgent().forEach((agent, list) -> actions.put(
                agent.value(), list.stream().map(Object::toString).toList()));
        return actions;
    }

    /** Recomputes the M10 corrected intent layer from the raw production evaluation pipeline. */
    private static IntentAwarePlanEvaluation objectiveOf(
            DayState state, TeamPlan plan, PlanEvaluation base) {
        IntentCollectionAttribution attribution = new IntentForecastEvaluator().evaluate(
                state,
                new DaySimulator().simulate(state, plan),
                new OpponentIntentForecaster().forecast(state),
                IntentAdjustmentWeights.defaults());
        return new IntentAwarePlanEvaluation(
                base,
                attribution.adjustedScore(),
                attribution.forecastRealizableBrands().size(),
                attribution.forecastRealizableCollections(),
                attribution.likelyClaimedFirstCollections(),
                attribution.tieCollections(),
                attribution.unforecastedCollections());
    }

    private static IntentAwareAnytimePlanner intentAware(AnytimePlannerConfig config) {
        return new IntentAwareAnytimePlanner(
                config, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults());
    }

    private static DiverseIntentAwareAnytimePlanner diverse(AnytimePlannerConfig config) {
        return new DiverseIntentAwareAnytimePlanner(
                config,
                OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(),
                DiverseSearchConfig.defaults(),
                false);
    }

    /**
     * Purpose-built hidden-branch fixture. The openings the M10 candidate comparator ranks first
     * lead to a strong but strategically narrow plan; a weaker-looking opening extends into a
     * complete plan the unchanged M10 evaluator prefers. Both searches exhaust the same 64-state
     * budget, so the difference is discovery, not evaluation.
     */
    private static DayState hiddenBranchState() {
        return gridState(
                14,
                List.of(
                        spot("B", 2), spot("C", 21), spot("D", 40), spot("A", 59), spot("B", 14),
                        spot("C", 33), spot("D", 52), spot("A", 7), spot("B", 26), spot("C", 45)));
    }

    /**
     * Live-shaped m-3262/m-3266 day: eight stocked spots over four brands, three own PATROL agents
     * plus one REFUEL, and one observed opponent group of three collection-eligible agents and one
     * raw-kind-one non-collector.
     */
    private static DayState liveShapedState() {
        return gridState(
                16,
                List.of(
                        spot("A", 2), spot("B", 5), spot("C", 16), spot("D", 23),
                        spot("A", 34), spot("B", 40), spot("C", 53), spot("D", 61)));
    }

    private static DayState gridState(int budget, List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[64];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<AgentState> agents = new ArrayList<>();
        int[] patrolStarts = {27, 28, 36};
        for (int index = 0; index < patrolStarts.length; index++) {
            agents.add(AgentState.patrol(new AgentId(index), new Position(patrolStarts[index]), 30));
        }
        agents.add(AgentState.refuel(new AgentId(3), new Position(35)));
        List<ObservedOtherAgent> others = List.of(
                other(11, 0), other(12, 0), other(50, 0), other(19, 1));
        return state(new HexMap(8, 8, terrain), budget, agents, spots,
                List.of(new ObservedOtherGroup(5, others)));
    }

    private static DayState lineState(
            int width, int budget, List<UdonSpot> spots, List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        return state(
                new HexMap(width, 1, terrain),
                budget,
                List.of(AgentState.patrol(new AgentId(0), new Position(0), 30)),
                spots,
                others);
    }

    private static DayState state(
            HexMap map,
            int budget,
            List<AgentState> agents,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                map,
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new FuelCapacity(30),
                spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock, others);
    }

    private static ObservedOtherAgent other(int position, int rawKind) {
        return new ObservedOtherAgent(new Position(position), rawKind, 40);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), new Position(position), 1);
    }
}
