package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.Direction;
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

class ArrivalContentionPlannerTest {

    private static final AnytimePlannerConfig BUDGET = new AnytimePlannerConfig(2, 8, 1);

    @Test
    void test17_ArrivalClassificationEnumValues() {
        assertEquals(4, ArrivalContentionClassification.values().length);
        assertEquals(ArrivalContentionClassification.ARRIVAL_SAFE, ArrivalContentionClassification.valueOf("ARRIVAL_SAFE"));
        assertEquals(ArrivalContentionClassification.ARRIVAL_TIED, ArrivalContentionClassification.valueOf("ARRIVAL_TIED"));
        assertEquals(ArrivalContentionClassification.ARRIVAL_AT_RISK, ArrivalContentionClassification.valueOf("ARRIVAL_AT_RISK"));
        assertEquals(ArrivalContentionClassification.UNOBSERVED, ArrivalContentionClassification.valueOf("UNOBSERVED"));
    }

    @Test
    void test18_RouteArrivalContentionMetricsRecord() {
        RouteArrivalContentionMetrics metrics = new RouteArrivalContentionMetrics(6, 3, 2, 1, 3, 2, 1, 0);
        assertEquals(6, metrics.projectedCollectionGain());
        assertEquals(3, metrics.arrivalSafeCollections());
        assertEquals(2, metrics.arrivalTiedCollections());
        assertEquals(1, metrics.arrivalAtRiskCollections());
        assertEquals(3, metrics.staticSafeCollections());
        assertEquals(2, metrics.staticTiedCollections());
        assertEquals(1, metrics.staticContestedCollections());
        assertEquals(0, metrics.stronglyStaticContestedCollections());
    }

    @Test
    void test19_ArrivalAttributionRecord() {
        ArrivalAttribution attribution = new ArrivalAttribution(5, 3, 2, 1);
        assertEquals(5, attribution.arrivalSafeProjected());
        assertEquals(3, attribution.arrivalTiedProjected());
        assertEquals(2, attribution.arrivalAtRiskProjected());
        assertEquals(1, attribution.unobservedProjected());
    }

    @Test
    void test20_ArrivalAwarePlanEvaluationRecord() {
        PlanEvaluation base = new PlanEvaluation(2, 5, 1, 20, 4, "sig");
        ArrivalAwarePlanEvaluation eval = new ArrivalAwarePlanEvaluation(base, 3, 1, 1, 0);

        assertEquals(base, eval.base());
        assertEquals(3, eval.arrivalSafeCollections());
        assertEquals(1, eval.arrivalTiedCollections());
        assertEquals(1, eval.arrivalAtRiskCollections());
        assertEquals(0, eval.stronglyContestedCollections());
        assertEquals(2, eval.base().teamBrandCount());
        assertEquals(5, eval.base().udonTotal());
    }

    @Test
    void test21_AnalyzeArrivalClassifications() {
        ContentionAnalyzer analyzer = new ContentionAnalyzer();

        ArrivalContentionMetrics safe = analyzer.analyzeArrival(p(5), 2, OptionalInt.of(4));
        assertEquals(ArrivalContentionClassification.ARRIVAL_SAFE, safe.classification());
        assertEquals(2, safe.ourArrivalStep());
        assertEquals(OptionalInt.of(4), safe.otherHexDistanceLowerBound());

        ArrivalContentionMetrics tied = analyzer.analyzeArrival(p(5), 3, OptionalInt.of(3));
        assertEquals(ArrivalContentionClassification.ARRIVAL_TIED, tied.classification());

        ArrivalContentionMetrics atRisk = analyzer.analyzeArrival(p(5), 4, OptionalInt.of(2));
        assertEquals(ArrivalContentionClassification.ARRIVAL_AT_RISK, atRisk.classification());

        ArrivalContentionMetrics unobserved = analyzer.analyzeArrival(p(5), 4, OptionalInt.empty());
        assertEquals(ArrivalContentionClassification.UNOBSERVED, unobserved.classification());
        assertTrue(unobserved.otherHexDistanceLowerBound().isEmpty());
    }

    @Test
    void test22_OpponentLowerBounds() {
        DayState state = gridState(
                List.of(spot("A", 1), spot("A", 5)),
                List.of(group(1, agent(0))));

        ContentionAnalyzer analyzer = new ContentionAnalyzer();
        Map<Position, OptionalInt> bounds = analyzer.opponentLowerBounds(state);

        assertNotNull(bounds);
        assertTrue(bounds.containsKey(p(1)));
        assertTrue(bounds.get(p(1)).isPresent());
        assertTrue(bounds.get(p(1)).getAsInt() > 0);
    }

    @Test
    void test23_AnalyzeRouteArrival() {
        DayState state = gridState(
                List.of(spot("A", 1), spot("A", 2)),
                List.of(group(1, agent(0))));
        ContentionAnalyzer analyzer = new ContentionAnalyzer();
        Map<Position, OptionalInt> bounds = analyzer.opponentLowerBounds(state);

        Route route = new Route(p(4), p(5), List.of(Direction.RIGHT), 1, 1);
        RouteArrivalContentionMetrics metrics = analyzer.analyzeRouteArrival(state, route, bounds, 0);

        assertNotNull(metrics);
        assertTrue(metrics.arrivalSafeCollections() + metrics.arrivalTiedCollections() + metrics.arrivalAtRiskCollections() >= 0);
    }

    @Test
    void test24_ArrivalCandidateMetricsComparators() {
        ArrivalContentionCandidateMetrics metrics1 = new ArrivalContentionCandidateMetrics(
                true, 2, 2, 0, 0, 2, 0, 0, 0, 2, 1, 10, p(1), new AgentId(0));
        ArrivalContentionCandidateMetrics metrics2 = new ArrivalContentionCandidateMetrics(
                false, 2, 0, 0, 2, 0, 0, 2, 0, 2, 1, 10, p(2), new AgentId(0));

        assertTrue(ArrivalContentionCandidateMetrics.coveragePreference().compare(metrics1, metrics2) < 0);
    }

    @Test
    void test25_ArrivalContentionFrontierMetricsPreference() {
        ArrivalContentionFrontierMetrics m1 = new ArrivalContentionFrontierMetrics(
                2, 3, 0, 0, 2, 5, 5, 10, 20, 2, 1, 0L);
        ArrivalContentionFrontierMetrics m2 = new ArrivalContentionFrontierMetrics(
                2, 1, 0, 2, 2, 5, 5, 10, 20, 2, 1, 1L);

        assertTrue(ArrivalContentionFrontierMetrics.preference().compare(m1, m2) < 0);
    }

    @Test
    void test26_ArrivalContentionAnytimePlannerDelegation() {
        DayState state = state(
                7,
                6,
                List.of(spot("A", 2)),
                List.of());

        ArrivalContentionAnytimePlanner planner = new ArrivalContentionAnytimePlanner(BUDGET);
        AnytimePlanResult result = planner.planWithStats(state);

        assertNotNull(result);
        assertNotNull(result.plan());
        assertNotNull(result.evaluation());
        assertNotNull(result.stats());
    }

    @Test
    void test27_PlanWithStatsArrivalContentionOutputAndLogs() {
        DayState state = state(
                12,
                9,
                List.of(spot("A", 4), spot("A", 3)),
                List.of(group(1, agent(5))));

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            AnytimePlanResult result = new ArrivalContentionAnytimePlanner(BUDGET).planWithStats(state);
            assertNotNull(result);
        } finally {
            System.setOut(original);
        }

        String logs = captured.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("ANYTIME_ARRIVAL_CONTENTION_DONE"));
    }

    @Test
    void test28_ScoreboardPriorityOverArrivalSafety() {
        // Partial frontier guidance intentionally differs from the completed-plan objective.
        ArrivalContentionFrontierMetrics highBrand = new ArrivalContentionFrontierMetrics(
                3, 0, 0, 3, 0, 3, 3, 10, 20, 3, 1, 0L);
        ArrivalContentionFrontierMetrics highSafetyLowBrand = new ArrivalContentionFrontierMetrics(
                2, 3, 0, 0, 3, 3, 3, 10, 20, 3, 1, 1L);

        assertTrue(ArrivalContentionFrontierMetrics.preference().compare(highBrand, highSafetyLowBrand) < 0);
    }

    @Test
    void completedPlanObjectiveRanksPredictedUdonBeforeArrivalRisk() {
        ArrivalAwarePlanEvaluation higherUdon = evaluation(4, 15, 0, 5, "A");
        ArrivalAwarePlanEvaluation lowerRisk = evaluation(4, 14, 5, 0, "B");

        assertTrue(higherUdon.betterThan(lowerRisk));
    }

    @Test
    void completedPlanObjectiveRanksBrandsBeforeUdonAndArrivalRisk() {
        ArrivalAwarePlanEvaluation moreBrands = evaluation(4, 12, 0, 5, "A");
        ArrivalAwarePlanEvaluation higherScoreAndLowerRisk = evaluation(3, 20, 5, 0, "B");

        assertTrue(moreBrands.betterThan(higherScoreAndLowerRisk));
    }

    @Test
    void completedPlanObjectiveUsesRiskToBreakEqualScore() {
        ArrivalAwarePlanEvaluation higherRisk = evaluation(4, 15, 5, 4, "A");
        ArrivalAwarePlanEvaluation lowerRisk = evaluation(4, 15, 0, 1, "B");

        assertTrue(lowerRisk.betterThan(higherRisk));
    }

    @Test
    void completedPlanObjectiveUsesArrivalSafetyAfterEqualScoreAndRisk() {
        ArrivalAwarePlanEvaluation lessSafe = evaluation(4, 15, 2, 1, "A");
        ArrivalAwarePlanEvaluation moreSafe = evaluation(4, 15, 5, 1, "B");

        assertTrue(moreSafe.betterThan(lessSafe));
    }

    @Test
    void sameBudgetEvaluatesBothEqualScoreBranchesAndArrivalPlannerChoosesLowerRisk() {
        AnytimePlannerConfig budget = new AnytimePlannerConfig(3, 16, 2);
        DayState state = rectangularState(
                3,
                2,
                2,
                0,
                List.of(spot("A", 1), spot("A", 3)),
                List.of(group(1, agent(3))));

        AnytimePlanResult contention = new ContentionAwareAnytimePlanner(budget).planWithStats(state);
        AnytimePlanResult arrival = new ArrivalContentionAnytimePlanner(budget).planWithStats(state);
        int contentionRisk = arrivalRisk(state, contention);
        int arrivalRisk = arrivalRisk(state, arrival);

        assertEquals(contention.evaluation().teamBrandCount(), arrival.evaluation().teamBrandCount());
        assertEquals(contention.evaluation().udonTotal(), arrival.evaluation().udonTotal());
        assertTrue(arrival.evaluation().teamBrandCount() >= contention.evaluation().teamBrandCount());
        assertTrue(arrival.evaluation().udonTotal() >= contention.evaluation().udonTotal());
        assertEquals(1, contentionRisk);
        assertEquals(0, arrivalRisk);
        assertTrue(arrivalRisk <= contentionRisk);
        assertEquals(3, contention.stats().expandedStates());
        assertEquals(3, arrival.stats().expandedStates());
        assertEquals(4, contention.stats().completedPlans());
        assertEquals(4, arrival.stats().completedPlans());
        assertEquals(0, contention.stats().incumbentImprovements());
        assertEquals(1, arrival.stats().incumbentImprovements());
    }

    @Test
    void topKRetainsAndExpandsArrivalSafeAndMaximumGainBranches() {
        AnytimePlannerConfig budget = new AnytimePlannerConfig(3, 16, 2);
        DayState state = rectangularState(
                3,
                2,
                4,
                0,
                List.of(spot("A", 1), spot("A", 2), spot("A", 3)),
                List.of(group(1, agent(2))));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        AnytimePlanResult result;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            result = new ArrivalContentionAnytimePlanner(budget, true).planWithStats(state);
        } finally {
            System.setOut(original);
        }

        String logs = captured.toString(StandardCharsets.UTF_8);
        assertEquals(3, result.stats().candidateGenerated());
        assertEquals(2, result.stats().candidateRetained());
        assertEquals(1, result.stats().candidatePrunedByTopK());
        assertEquals(3, result.stats().expandedStates());
        assertEquals(4, result.stats().completedPlans());
        assertTrue(logs.contains("ARRIVAL_CONTENTION_CANDIDATE day=0 agent=0 target=3 "
                + "totalGain=1 arrivalSafe=1 arrivalTied=0 arrivalAtRisk=0"));
        assertTrue(logs.contains("ARRIVAL_CONTENTION_CANDIDATE day=0 agent=0 target=2 "
                + "totalGain=2 arrivalSafe=0 arrivalTied=0 arrivalAtRisk=2"));
    }

    @Test
    void weightedArrivalPolicyChoosesLowerRiskEqualScorePlanWithSameBudget() {
        AnytimePlannerConfig budget = new AnytimePlannerConfig(4, 16, 2);
        DayState state = state(
                5,
                4,
                List.of(spot("A", 0), spot("A", 3)),
                List.of(group(1, agent(0))),
                1);

        AnytimePlanResult oldArrival = new ArrivalContentionAnytimePlanner(budget).planWithStats(state);
        AnytimePlanResult weighted = new WeightedArrivalContentionAnytimePlanner(budget).planWithStats(state);
        int oldWeightedRisk = weightedArrivalRisk(state, oldArrival);
        int newWeightedRisk = weightedArrivalRisk(state, weighted);

        assertEquals(oldArrival.evaluation().teamBrandCount(), weighted.evaluation().teamBrandCount());
        assertEquals(oldArrival.evaluation().udonTotal(), weighted.evaluation().udonTotal());
        assertEquals(1, oldArrival.evaluation().teamBrandCount());
        assertEquals(1, oldArrival.evaluation().udonTotal());
        assertEquals(1, oldWeightedRisk);
        assertEquals(0, newWeightedRisk);
        assertEquals(3, oldArrival.stats().expandedStates());
        assertEquals(3, weighted.stats().expandedStates());
        assertEquals(4, oldArrival.stats().completedPlans());
        assertEquals(4, weighted.stats().completedPlans());
        assertEquals(0, oldArrival.stats().incumbentImprovements());
        assertEquals(1, weighted.stats().incumbentImprovements());
    }

    @Test
    void weightedModeReportsBoundedComparisonAndWeightedSummaryFields() {
        AnytimePlannerConfig budget = new AnytimePlannerConfig(4, 16, 2);
        DayState state = state(
                5,
                4,
                List.of(spot("A", 0), spot("A", 3)),
                List.of(group(1, agent(0))),
                1);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new WeightedArrivalContentionAnytimePlanner(budget, true).planWithStats(state);
        } finally {
            System.setOut(original);
        }

        String logs = captured.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("ANYTIME_WEIGHTED_ARRIVAL_CONTENTION_DONE"));
        assertTrue(logs.contains("weightedArrivalSafeProjected="));
        assertTrue(logs.contains("weightedArrivalTiedProjected="));
        assertTrue(logs.contains("weightedArrivalAtRiskProjected="));
        assertTrue(logs.contains("arrivalAwareIncumbentImprovements=1"));
        assertTrue(logs.contains("ARRIVAL_BOUND_COMPARISON position=0"));
        assertTrue(logs.contains("opponentHexDistanceLowerBound="));
        assertTrue(logs.contains("opponentWeightedStepLowerBound="));
        assertTrue(logs.lines().filter(line -> line.startsWith("ARRIVAL_BOUND_COMPARISON ")).count() <= 8);
    }

    private static ArrivalAwarePlanEvaluation evaluation(
            int brands, int udon, int arrivalSafe, int arrivalRisk, String signature) {
        return new ArrivalAwarePlanEvaluation(
                new PlanEvaluation(brands, udon, 1, 20, 2, signature),
                arrivalSafe,
                0,
                arrivalRisk,
                0);
    }

    private static int arrivalRisk(DayState state, AnytimePlanResult result) {
        ContentionAnalyzer analyzer = new ContentionAnalyzer();
        return ArrivalAttribution.fromSimulation(
                state,
                new DaySimulator().simulate(state, result.plan()),
                analyzer.opponentLowerBounds(state),
                analyzer).arrivalAtRiskProjected();
    }

    private static int weightedArrivalRisk(DayState state, AnytimePlanResult result) {
        ContentionAnalyzer analyzer = new ContentionAnalyzer();
        return ArrivalAttribution.fromSimulation(
                state,
                new DaySimulator().simulate(state, result.plan()),
                new OpponentWeightedArrivalLowerBound().lowerBounds(state),
                analyzer).arrivalAtRiskProjected();
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
                new FuelCapacity(20),
                spots);
        return new DayState(
                match,
                new DayIndex(0),
                List.of(AgentState.patrol(new AgentId(0), p(0), 20)),
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
                new FuelCapacity(20),
                spots);
        return new DayState(
                match,
                new DayIndex(0),
                List.of(AgentState.patrol(new AgentId(0), p(patrolPosition), 20)),
                Map.of(),
                stock,
                others);
    }

    private static DayState gridState(
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[9];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(3, 3, terrain),
                new DayStepBudgets(new int[] {2}),
                List.of(),
                new FuelCapacity(20),
                spots);
        return new DayState(
                match,
                new DayIndex(0),
                List.of(AgentState.patrol(new AgentId(0), p(4), 20)),
                Map.of(), stock, others);
    }

    private static DayState rectangularState(
            int width,
            int height,
            int dayBudget,
            int patrolPosition,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[width * height];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(width, height, terrain),
                new DayStepBudgets(new int[] {dayBudget}),
                List.of(),
                new FuelCapacity(20),
                spots);
        return new DayState(
                match,
                new DayIndex(0),
                List.of(AgentState.patrol(new AgentId(0), p(patrolPosition), 20)),
                Map.of(), stock, others);
    }

    private static ObservedOtherGroup group(int rawId, ObservedOtherAgent... agents) {
        return new ObservedOtherGroup(rawId, List.of(agents));
    }

    private static ObservedOtherAgent agent(int position) {
        return new ObservedOtherAgent(p(position), 1, 60);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), p(position), 1);
    }

    private static Position p(int value) {
        return new Position(value);
    }
}
