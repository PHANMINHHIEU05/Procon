package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
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

class IntentAwareAnytimePlannerTest {

    private static final AnytimePlannerConfig SAME_BUDGET = new AnytimePlannerConfig(16, 32, 4);
    private static final AgentId PATROL = new AgentId(0);

    @Test
    void objectivePrefersForecastValueButKeepsBrandPriorityFirst() {
        IntentAwarePlanEvaluation claimed = evaluation(2, 2, 15, 20, 5, 10, 0);
        IntentAwarePlanEvaluation realizable = evaluation(2, 2, 14, 56, 14, 0, 0);
        IntentAwarePlanEvaluation moreBrands = evaluation(3, 3, 1, 0, 0, 1, 1);

        assertTrue(realizable.betterThan(claimed));
        assertTrue(moreBrands.betterThan(realizable));
    }

    @Test
    void forecastRealizableBrandLossOutranksLocalBrandCount() {
        IntentAwarePlanEvaluation localFourth = evaluation(4, 3, 16, 48, 12, 4, 0);
        IntentAwarePlanEvaluation realizableFourth = evaluation(4, 4, 15, 52, 15, 0, 0);

        assertTrue(realizableFourth.betterThan(localFourth));
        assertFalse(localFourth.betterThan(realizableFourth));
        assertTrue(IntentAwarePlanEvaluation.preference()
                .compare(realizableFourth, localFourth) < 0);
    }

    @Test
    void locallyNewBrandDoesNotDominateWhenItsOnlySourceIsForecastClaimed() {
        IntentAwarePlanEvaluation locallyNewOnly = evaluation(4, 3, 12, 36, 12, 3, 0);
        IntentAwarePlanEvaluation realizableCoverage = evaluation(3, 3, 12, 40, 12, 0, 0);

        assertTrue(realizableCoverage.betterThan(locallyNewOnly));
    }

    @Test
    void realizableBrandCoverageBeatsGreaterRealizableQuantity() {
        IntentAwarePlanEvaluation fourBrands = evaluation(4, 4, 12, 44, 12, 0, 0);
        IntentAwarePlanEvaluation threeBrandsMoreUdon = evaluation(3, 3, 18, 64, 18, 0, 0);

        assertTrue(fourBrands.betterThan(threeBrandsMoreUdon));
    }

    @Test
    void equalRealizableBrandsLetIntentScoreDecideBeforeRawUdon() {
        IntentAwarePlanEvaluation higherIntentLowerRaw = evaluation(3, 3, 14, 56, 14, 0, 0);
        IntentAwarePlanEvaluation lowerIntentHigherRaw = evaluation(3, 3, 17, 40, 10, 7, 0);

        assertTrue(higherIntentLowerRaw.betterThan(lowerIntentHigherRaw));
    }

    @Test
    void m2929FixtureRanksRealizableThreeAboveLocalFourWithoutInflatingRawUdon() {
        IntentAwarePlanEvaluation m10LocalBrands = evaluation(4, 3, 15, 44, 11, 4, 0);
        IntentAwarePlanEvaluation correctedRealizable = evaluation(4, 4, 15, 52, 15, 0, 0);

        assertTrue(correctedRealizable.betterThan(m10LocalBrands));
        assertEquals(m10LocalBrands.base().udonTotal(), correctedRealizable.base().udonTotal());
        assertEquals(4, correctedRealizable.localTeamBrandCount());
    }

    @Test
    void impossibleForecastMetricsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> evaluation(3, 4, 10, 20, 5, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> evaluation(3, 3, 10, 20, 11, 0, 0));
    }

    @Test
    void noOpponentMatchesHarvestPrimaryCollectionResult() {
        DayState state = state(List.of(spot("A", 1), spot("B", 2), spot("B", 8)), List.of());

        AnytimePlanResult harvest = new HarvestAnytimeTeamPlanner(SAME_BUDGET).planWithStats(state);
        AnytimePlanResult intent = new IntentAwareAnytimePlanner(SAME_BUDGET).planWithStats(state);
        IntentAwarePlanEvaluation evaluation = intent.intentAwareEvaluation().orElseThrow();

        assertEquals(harvest.evaluation().teamBrandCount(), intent.evaluation().teamBrandCount());
        assertEquals(harvest.evaluation().udonTotal(), intent.evaluation().udonTotal());
        assertEquals(intent.evaluation().udonTotal() * 4,
                evaluation.adjustedCollectionScore().value());
        assertEquals(intent.evaluation().udonTotal(), evaluation.forecastRealizableCollections());
        assertEquals(evaluation.localTeamBrandCount(), evaluation.forecastRealizableBrandCount());
    }

    @Test
    void physicallyReachableButUnselectedSpotKeepsFullValue() {
        DayState state = lineState(
                7,
                12,
                6,
                List.of(spot("A", 1), spot("A", 2), spot("A", 3), spot("A", 4)),
                List.of(group(3, agent(0, 0))));
        OpponentIntentForecast forecast = new OpponentIntentForecaster().forecast(state);

        assertEquals(4, forecast.physicalPairsAllObserved());
        assertEquals(4, forecast.physicalPairsCollectionEligible());
        assertEquals(3, forecast.retainedIntentTargets());
        assertEquals(null, forecast.pressureAt(position(4)));
        ForecastCollectionAssessment assessment = new IntentForecastEvaluator().assessCollection(
                state.spotStock(), position(4), 8, forecast, IntentAdjustmentWeights.defaults());
        assertEquals(IntentCollectionClassification.UNFORECASTED, assessment.classification());
        assertEquals(4, assessment.intentValueUnits());
    }

    @Test
    void sameBudgetM10PreservesUnselectedBranchValue() {
        DayState state = branchState(false);

        AnytimePlanResult risk = new RiskAdjustedAnytimePlanner(SAME_BUDGET).planWithStats(state);
        AnytimePlanResult intent = new IntentAwareAnytimePlanner(SAME_BUDGET).planWithStats(state);
        IntentCollectionAttribution riskUnderIntent = new IntentForecastEvaluator().evaluate(
                state,
                new DaySimulator().simulate(state, risk.plan()),
                new OpponentIntentForecaster().forecast(state),
                IntentAdjustmentWeights.defaults());

        assertEquals(risk.evaluation().teamBrandCount(), intent.evaluation().teamBrandCount());
        assertTrue(intent.intentAwareEvaluation().orElseThrow().adjustedCollectionScore().value()
                >= riskUnderIntent.adjustedScore().value());
        assertTrue(intent.intentAwareEvaluation().orElseThrow().forecastRealizableCollections()
                >= riskUnderIntent.forecastRealizableCollections());
        assertTrue(intent.intentAwareEvaluation().orElseThrow().unforecastedCollections() > 0);
        assertTrue(risk.stats().expandedStates() <= SAME_BUDGET.maxExpandedStates());
        assertTrue(intent.stats().expandedStates() <= SAME_BUDGET.maxExpandedStates());
        assertTrue(risk.stats().completedPlans() > 0);
        assertTrue(intent.stats().completedPlans() > 0);
    }

    @Test
    void liveShapedFourAgentForecastSelectsOnlySubsetOfEightSpots() {
        DayState state = branchState(true);

        OpponentIntentForecast forecast = new OpponentIntentForecaster().forecast(state);
        AnytimePlanResult result = new IntentAwareAnytimePlanner(SAME_BUDGET).planWithStats(state);

        assertEquals(4, forecast.observedAgentCount());
        assertEquals(3, forecast.collectionEligibleAgentCount());
        assertEquals(8, forecast.stockedSpotCount());
        assertTrue(forecast.physicalPairsAllObserved() > forecast.retainedIntentTargets());
        assertTrue(forecast.pressureBySpot().size() < forecast.stockedSpotCount());
        assertTrue(result.intentAwareEvaluation().orElseThrow().unforecastedCollections() > 0);
    }

    @Test
    void liveShapedRegressionRestoresForecastStockWithoutInflatingRawUdon() {
        DayState state = liveShapedState();
        OpponentIntentConfig previous = new OpponentIntentConfig(
                OpponentIntentConfig.DEFAULT_MAX_INTENT_TARGETS_PER_AGENT,
                OpponentCollectionEligibility.ALL_OBSERVED_COLLECT);

        OpponentIntentForecast beforeForecast = new OpponentIntentForecaster().forecast(state, previous);
        OpponentIntentForecast afterForecast = new OpponentIntentForecaster()
                .forecast(state, OpponentIntentConfig.defaults());
        AnytimePlanResult before = new IntentAwareAnytimePlanner(
                SAME_BUDGET, previous, IntentAdjustmentWeights.defaults()).planWithStats(state);
        AnytimePlanResult after = new IntentAwareAnytimePlanner(
                SAME_BUDGET, OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults()).planWithStats(state);
        IntentAwarePlanEvaluation beforeEval = before.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation afterEval = after.intentAwareEvaluation().orElseThrow();

        assertEquals(8, afterForecast.stockedSpotCount());
        assertEquals(4, beforeForecast.observedAgentCount());
        assertEquals(4, afterForecast.observedAgentCount());
        assertEquals(4, beforeForecast.collectionEligibleAgentCount());
        assertEquals(3, afterForecast.collectionEligibleAgentCount());
        assertTrue(afterForecast.forecastClaims() < beforeForecast.forecastClaims());
        assertTrue(remainingForecastStock(state, afterForecast)
                > remainingForecastStock(state, beforeForecast));
        assertTrue(afterEval.forecastRealizableCollections()
                > beforeEval.forecastRealizableCollections());
        assertTrue(afterEval.forecastRealizableBrandCount()
                > beforeEval.forecastRealizableBrandCount());
        assertTrue(afterEval.likelyClaimedFirstCollections()
                < beforeEval.likelyClaimedFirstCollections());
        assertEquals(beforeEval.base().udonTotal(), afterEval.base().udonTotal());
        assertTrue(after.stats().expandedStates() <= SAME_BUDGET.maxExpandedStates());
    }

    @Test
    void diagnosticsAreBoundedAndExposeLiveMetrics() {
        DayState state = branchState(true);
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new IntentAwareAnytimePlanner(
                    SAME_BUDGET,
                    OpponentIntentConfig.defaults(),
                    IntentAdjustmentWeights.defaults(),
                    true).planWithStats(state);
        } finally {
            System.setOut(original);
        }

        String logs = captured.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("OPPONENT_INTENT_SUMMARY day=0"));
        assertTrue(logs.contains("observedAgents=4"));
        assertTrue(logs.contains("collectionEligibleAgents=3"));
        assertTrue(logs.contains("physicalPairsAllObserved="));
        assertTrue(logs.contains("physicalPairsCollectionEligible="));
        assertTrue(logs.contains("retainedIntentTargets="));
        assertTrue(logs.contains("OPPONENT_OBSERVED_AGENT "));
        assertTrue(logs.contains("collectionEligible=false"));
        assertTrue(logs.contains("ANYTIME_INTENT_AWARE_START day=0"));
        assertTrue(logs.contains("incumbentLocalBrands="));
        assertTrue(logs.contains("incumbentForecastBrands="));
        assertTrue(logs.contains("incumbentForecastRealizable="));
        assertTrue(logs.contains("ANYTIME_INTENT_AWARE_DONE day=0"));
        assertTrue(logs.contains("localBrands="));
        assertTrue(logs.contains("forecastBrands="));
        assertTrue(logs.contains("likelyClaimedFirst="));
        assertFalse(logs.contains("physicallyReachablePairs="));
        assertTrue(logs.lines().filter(line -> line.startsWith("OPPONENT_INTENT_TARGET ")).count() <= 12);
        assertTrue(logs.lines().filter(line -> line.startsWith("OPPONENT_OBSERVED_AGENT ")).count() <= 12);
        assertTrue(logs.lines().filter(line -> line.startsWith("INTENT_STOCK_PRESSURE ")).count() <= 8);
        assertFalse(logs.contains("probability"));
        assertTrue(logs.lines()
                .filter(line -> line.startsWith("OPPONENT_INTENT_TARGET "))
                .allMatch(line -> line.contains("collectionEligible=true")));
    }

    private static int remainingForecastStock(DayState state, OpponentIntentForecast forecast) {
        int claimed = forecast.pressureBySpot().values().stream()
                .mapToInt(SpotIntentPressure::forecastClaimedPortions)
                .sum();
        int stock = state.spotStock().values().stream().mapToInt(Integer::intValue).sum();
        return stock - claimed;
    }

    private static IntentAwarePlanEvaluation evaluation(
            int localBrands,
            int realizableBrands,
            int raw,
            int adjusted,
            int realizable,
            int claimedFirst,
            int ties) {
        return new IntentAwarePlanEvaluation(
                new PlanEvaluation(localBrands, raw, 1, 10, 10, "sig"),
                new IntentAdjustedCollectionScore(adjusted),
                realizableBrands,
                realizable,
                claimedFirst,
                ties,
                0);
    }

    /**
     * Live m-2929/m-2933 group shape: eight stocked spots over four brands, one observed
     * opponent group of four agents with raw kinds {@code 0,0,0,1}. The raw-kind-one agent
     * stands next to distinct stock, so treating it as a collector deletes brand coverage.
     */
    private static DayState liveShapedState() {
        List<UdonSpot> spots = List.of(
                spot("A", 0), spot("B", 1), spot("C", 2), spot("D", 3),
                spot("A", 4), spot("B", 6), spot("C", 9), spot("D", 12));
        List<ObservedOtherAgent> agents = List.of(
                agent(10, 0), agent(10, 0), agent(10, 0), agent(3, 1));
        return state(spots, List.of(new ObservedOtherGroup(5, agents)));
    }

    private static DayState branchState(boolean fourAgents) {
        List<UdonSpot> spots = fourAgents
                ? List.of(
                        spot("A", 0), spot("A", 1), spot("A", 2), spot("A", 3),
                        spot("A", 4), spot("A", 6), spot("A", 9), spot("A", 12))
                : List.of(
                        spot("A", 1), spot("A", 2), spot("A", 6),
                        spot("A", 9), spot("A", 4));
        List<ObservedOtherAgent> agents = fourAgents
                ? List.of(agent(7, 0), agent(7, 0), agent(7, 0), agent(7, 1))
                : List.of(agent(7, 0));
        return state(spots, List.of(new ObservedOtherGroup(5, agents)));
    }

    private static DayState state(List<UdonSpot> spots, List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[15];
        Arrays.fill(terrain, Terrain.PLAIN);
        return state(new HexMap(5, 3, terrain), 6, position(7), spots, others);
    }

    private static DayState lineState(
            int width,
            int budget,
            int patrolPosition,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        return state(new HexMap(width, 1, terrain), budget, position(patrolPosition), spots, others);
    }

    private static DayState state(
            HexMap map,
            int budget,
            Position patrolPosition,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                map,
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new vn.ptit.procon.domain.agent.FuelCapacity(30),
                spots);
        return new DayState(
                match,
                new DayIndex(0),
                List.of(AgentState.patrol(PATROL, patrolPosition, 30)),
                Map.of(),
                stock,
                others);
    }

    private static ObservedOtherAgent agent(int position, int rawKind) {
        return new ObservedOtherAgent(position(position), rawKind, 40);
    }

    private static ObservedOtherGroup group(int rawId, ObservedOtherAgent agent) {
        return new ObservedOtherGroup(rawId, List.of(agent));
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), position(position), 1);
    }

    private static Position position(int value) {
        return new Position(value);
    }
}
