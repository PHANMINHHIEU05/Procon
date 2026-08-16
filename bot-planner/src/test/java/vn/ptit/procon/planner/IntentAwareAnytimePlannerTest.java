package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        IntentAwarePlanEvaluation claimed = evaluation(2, 15, 20, 5, 10, 0);
        IntentAwarePlanEvaluation realizable = evaluation(2, 14, 56, 14, 0, 0);
        IntentAwarePlanEvaluation moreBrands = evaluation(3, 1, 0, 0, 1, 1);

        assertTrue(realizable.betterThan(claimed));
        assertTrue(moreBrands.betterThan(realizable));
    }

    @Test
    void noOpponentMatchesHarvestPrimaryCollectionResult() {
        DayState state = state(List.of(spot("A", 1), spot("B", 2), spot("B", 8)), List.of());

        AnytimePlanResult harvest = new HarvestAnytimeTeamPlanner(SAME_BUDGET).planWithStats(state);
        AnytimePlanResult intent = new IntentAwareAnytimePlanner(SAME_BUDGET).planWithStats(state);

        assertEquals(harvest.evaluation().teamBrandCount(), intent.evaluation().teamBrandCount());
        assertEquals(harvest.evaluation().udonTotal(), intent.evaluation().udonTotal());
        assertEquals(intent.evaluation().udonTotal() * 4,
                intent.intentAwareEvaluation().orElseThrow().adjustedCollectionScore().value());
        assertEquals(intent.evaluation().udonTotal(),
                intent.intentAwareEvaluation().orElseThrow().forecastRealizableCollections());
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

        assertEquals(4, forecast.physicallyReachablePairs());
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
                > riskUnderIntent.forecastRealizableCollections());
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
        assertEquals(8, forecast.stockedSpotCount());
        assertTrue(forecast.physicallyReachablePairs() > forecast.retainedIntentTargets());
        assertTrue(forecast.pressureBySpot().size() < forecast.stockedSpotCount());
        assertTrue(result.intentAwareEvaluation().orElseThrow().unforecastedCollections() > 0);
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
        assertTrue(logs.contains("physicallyReachablePairs="));
        assertTrue(logs.contains("retainedIntentTargets="));
        assertTrue(logs.contains("ANYTIME_INTENT_AWARE_START day=0"));
        assertTrue(logs.contains("ANYTIME_INTENT_AWARE_DONE day=0"));
        assertTrue(logs.contains("likelyClaimedFirst="));
        assertTrue(logs.lines().filter(line -> line.startsWith("OPPONENT_INTENT_TARGET ")).count() <= 12);
        assertTrue(logs.lines().filter(line -> line.startsWith("INTENT_STOCK_PRESSURE ")).count() <= 8);
        assertFalse(logs.contains("probability"));
    }

    private static IntentAwarePlanEvaluation evaluation(
            int brands,
            int raw,
            int adjusted,
            int realizable,
            int claimedFirst,
            int ties) {
        return new IntentAwarePlanEvaluation(
                new PlanEvaluation(brands, raw, 1, 10, 10, "sig"),
                new IntentAdjustedCollectionScore(adjusted),
                realizable,
                claimedFirst,
                ties,
                0);
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
                ? List.of(agent(7, 0), agent(7, 1), agent(7, 0), agent(7, 1))
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