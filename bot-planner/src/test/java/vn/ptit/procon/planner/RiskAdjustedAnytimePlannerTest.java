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
import java.util.ArrayList;
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
import vn.ptit.procon.engine.DayState;

class RiskAdjustedAnytimePlannerTest {

    private static final AnytimePlannerConfig SAME_BUDGET = new AnytimePlannerConfig(4, 16, 2);
    private static final AgentId PATROL = new AgentId(0);

    @Test
    void defaultWeightsAreExplicitAndCustomWeightsAreDeterministic() {
        assertEquals(new RiskAdjustmentWeights(4, 2, 1, 4), RiskAdjustmentWeights.defaults());
        ArrivalAttribution attribution = new ArrivalAttribution(2, 1, 3, 4);
        assertEquals(29, RiskAdjustmentWeights.defaults().score(attribution));
        assertEquals(2 * 7 + 1 * 5 + 3 * 2 + 4 * 7,
                new RiskAdjustmentWeights(7, 5, 2, 7).score(attribution));
        assertEquals(4, RiskAdjustmentWeights.defaults().weightFor(ArrivalContentionClassification.ARRIVAL_SAFE));
        assertThrows(IllegalArgumentException.class, () -> new RiskAdjustmentWeights(-1, 2, 1, 4));
        assertThrows(IllegalArgumentException.class, () -> new RiskAdjustmentWeights(4, -1, 1, 4));
        assertThrows(IllegalArgumentException.class, () -> new RiskAdjustmentWeights(4, 2, -1, 4));
        assertThrows(IllegalArgumentException.class, () -> new RiskAdjustmentWeights(4, 2, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new RiskAdjustmentWeights(0, 0, 0, 0));
    }

    @Test
    void riskAdjustedObjectiveOrdersBrandsAdjustedRawRiskAndDeterministicTies() {
        RiskAdjustedPlanEvaluation a = evaluation(4, 19, 36, 5, 2, 12);
        RiskAdjustedPlanEvaluation b = evaluation(4, 18, 58, 12, 4, 2);
        assertTrue(b.betterThan(a), "higher adjusted value must override one raw Udon");

        assertTrue(evaluation(4, 12, 20, 1, 0, 0).betterThan(
                evaluation(3, 20, 60, 0, 0, 0)), "brands remain primary");
        assertTrue(evaluation(4, 15, 40, 1, 0, 0).betterThan(
                evaluation(4, 14, 40, 1, 0, 0)), "raw Udon breaks adjusted ties");
        assertTrue(evaluation(4, 15, 40, 0, 0, 2).betterThan(
                evaluation(4, 15, 40, 0, 0, 5)), "risk breaks full score ties");
    }

    @Test
    void noOpponentRiskAdjustedModePreservesHarvestCollectionValue() {
        DayState state = state(7, 6, List.of(spot("A", 2), spot("B", 4)), List.of());

        AnytimePlanResult harvest = new HarvestAnytimeTeamPlanner(SAME_BUDGET).planWithStats(state);
        AnytimePlanResult adjusted = new RiskAdjustedAnytimePlanner(SAME_BUDGET).planWithStats(state);

        assertEquals(harvest.evaluation().teamBrandCount(), adjusted.evaluation().teamBrandCount());
        assertEquals(harvest.evaluation().udonTotal(), adjusted.evaluation().udonTotal());
        assertFalse(adjusted.evaluation().deterministicSignature().isEmpty());
    }

    @Test
    void liveShapedFixtureSelectsSaferPlanValueAndSameBudgetIsComparable() {
        DayState state = corridorState();

        AnytimePlanResult weighted = new WeightedArrivalContentionAnytimePlanner(SAME_BUDGET).planWithStats(state);
        AnytimePlanResult adjusted = new RiskAdjustedAnytimePlanner(SAME_BUDGET).planWithStats(state);
        ArrivalAttribution weightedAttribution = weightedAttribution(state, weighted);
        RiskAdjustedPlanEvaluation adjustedEvaluation = adjusted.riskAdjustedEvaluation().orElseThrow();

        assertEquals(weighted.stats().expandedStates(), adjusted.stats().expandedStates());
        assertEquals(weighted.stats().completedPlans(), adjusted.stats().completedPlans());
        assertEquals(1, weighted.evaluation().teamBrandCount());
        assertEquals(1, adjusted.evaluation().teamBrandCount());
        assertEquals(19, weighted.evaluation().udonTotal());
        assertEquals(18, adjusted.evaluation().udonTotal());
        assertEquals(0, weightedAttribution.arrivalSafeProjected());
        assertEquals(0, weightedAttribution.arrivalTiedProjected());
        assertEquals(19, weightedAttribution.arrivalAtRiskProjected());
        assertEquals(18, adjustedEvaluation.arrivalSafeCollections());
        assertEquals(0, adjustedEvaluation.arrivalTiedCollections());
        assertEquals(0, adjustedEvaluation.arrivalAtRiskCollections());
        assertEquals(19, RiskAdjustmentWeights.defaults().score(weightedAttribution));
        assertEquals(72, adjustedEvaluation.adjustedCollectionScore().value());
    }

    @Test
    void contentionAdjustedScoreCalculatesTheLiveShapedCountsExactly() {
        ArrivalAttribution safePlan = new ArrivalAttribution(12, 4, 2, 0);
        ArrivalAttribution riskyPlan = new ArrivalAttribution(5, 2, 12, 0);
        assertEquals(58, RiskAdjustmentWeights.defaults().score(safePlan));
        assertEquals(36, RiskAdjustmentWeights.defaults().score(riskyPlan));
        assertTrue(ContentionAdjustedCollectionScore.from(safePlan, RiskAdjustmentWeights.defaults()).value()
                > ContentionAdjustedCollectionScore.from(riskyPlan, RiskAdjustmentWeights.defaults()).value());
    }

    @Test
    void customWeightsAreDeterministicThroughProductionPlanner() {
        DayState state = corridorState();
        RiskAdjustmentWeights custom = new RiskAdjustmentWeights(9, 5, 1, 9);
        RiskAdjustedAnytimePlanner planner = new RiskAdjustedAnytimePlanner(SAME_BUDGET, custom);

        AnytimePlanResult first = planner.planWithStats(state);
        AnytimePlanResult second = planner.planWithStats(state);

        assertEquals(first.plan().actionsByAgent(), second.plan().actionsByAgent());
        assertEquals(first.riskAdjustedEvaluation(), second.riskAdjustedEvaluation());
        assertEquals(162, first.riskAdjustedEvaluation().orElseThrow()
                .adjustedCollectionScore().value());
    }

    @Test
    void startingCellUsesWeightedArrivalClassification() {
        DayState safe = state(
                3, 2, List.of(spot("A", 1)),
                List.of(group(1, new ObservedOtherAgent(position(2), 1, 60))), 1);
        DayState tied = state(
                3, 2, List.of(spot("A", 1)),
                List.of(group(1, new ObservedOtherAgent(position(1), 1, 60))), 1);

        RiskAdjustedPlanEvaluation safeEval = new RiskAdjustedAnytimePlanner(SAME_BUDGET)
                .planWithStats(safe).riskAdjustedEvaluation().orElseThrow();
        RiskAdjustedPlanEvaluation tiedEval = new RiskAdjustedAnytimePlanner(SAME_BUDGET)
                .planWithStats(tied).riskAdjustedEvaluation().orElseThrow();

        assertEquals(1, safeEval.arrivalSafeCollections());
        assertEquals(4, safeEval.adjustedCollectionScore().value());
        assertEquals(1, tiedEval.arrivalTiedCollections());
        assertEquals(2, tiedEval.adjustedCollectionScore().value());
    }

    @Test
    void diagnosticsExposeRawAdjustedAttributionAndBoundedWork() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new RiskAdjustedAnytimePlanner(
                    SAME_BUDGET, RiskAdjustmentWeights.defaults(), true).planWithStats(corridorState());
        } finally {
            System.setOut(original);
        }

        String logs = captured.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("ANYTIME_RISK_ADJUSTED_START day=0 incumbentBrands=1 "
                + "incumbentRawUdon=19 incumbentAdjustedScore=19 budget=4"));
        assertTrue(logs.contains("ANYTIME_RISK_ADJUSTED_DONE day=0 brands=1 rawUdon=18 "
                + "adjustedScore=72 safeProjected=18 tiedProjected=0 riskProjected=0 "
                + "unobservedProjected=0 expanded=3 completedPlans=4 improvements=1 "
                + "budgetExhausted=false"));
    }

    private static RiskAdjustedPlanEvaluation evaluation(
            int brands, int raw, int adjusted, int safe, int tied, int risk) {
        PlanEvaluation base = new PlanEvaluation(brands, raw, 1, 10, 1,
                brands + ":" + raw + ":" + adjusted + ":" + risk + ":" + safe + ":" + tied);
        return new RiskAdjustedPlanEvaluation(
                base,
                new ContentionAdjustedCollectionScore(adjusted),
                safe,
                tied,
                risk,
                0,
                0);
    }

    private static ArrivalAttribution weightedAttribution(DayState state, AnytimePlanResult result) {
        ContentionAnalyzer analyzer = new ContentionAnalyzer();
        return ArrivalAttribution.fromSimulation(
                state,
                new vn.ptit.procon.engine.DaySimulator().simulate(state, result.plan()),
                new OpponentWeightedArrivalLowerBound().lowerBounds(state),
                analyzer);
    }

    private static DayState corridorState() {
        List<UdonSpot> spots = new ArrayList<>();
        for (int position = 1; position <= 18; position++) {
            spots.add(spot("A", position));
        }
        for (int position = 20; position <= 38; position++) {
            spots.add(spot("A", position));
        }
        Terrain[] terrain = new Terrain[39];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), 1));
        StaticMatchData match = new StaticMatchData(
                new HexMap(39, 1, terrain),
                new DayStepBudgets(new int[] {38}),
                List.of(),
                new vn.ptit.procon.domain.agent.FuelCapacity(100),
                spots);
        return new DayState(
                match,
                new DayIndex(0),
                List.of(AgentState.patrol(PATROL, position(19), 100)),
                Map.of(),
                stock,
                List.of(group(1, new ObservedOtherAgent(position(20), 1, 60))));
    }

    private static DayState state(
            int width,
            int budget,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(width, 1, terrain),
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new vn.ptit.procon.domain.agent.FuelCapacity(20),
                spots);
        return new DayState(
                match,
                new DayIndex(0),
                List.of(AgentState.patrol(PATROL, position(0), 20)),
                Map.of(),
                stock,
                others);
    }

    private static DayState state(
            int width,
            int budget,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others,
            int patrolPosition) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(width, 1, terrain),
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new vn.ptit.procon.domain.agent.FuelCapacity(20),
                spots);
        return new DayState(
                match,
                new DayIndex(0),
                List.of(AgentState.patrol(PATROL, position(patrolPosition), 20)),
                Map.of(),
                stock,
                others);
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