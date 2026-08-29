package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/** Required live-shaped M13 code-gate regressions. */
class M13VerificationCompletionTest {

    private static final AnytimePlannerConfig NO_SEARCH = new AnytimePlannerConfig(0, 48, 4);
    private static final StratifiedSearchConfig NO_STAGES = StratifiedSearchConfig.forBudget(0);

    @Test
    void eightByEightHealthyReadinessDoesNotTriggerOverRefuel() {
        DayState state = eightByEightHealthyFuelState();
        TeamFutureReadiness without = readiness(state, new TeamCoordinatorPlanner().plan(state));
        TeamPlan horizonPlan = new HorizonAwareTeamCoordinatorPlanner(
                new WeightedRouteFinder(), new RefuelRouteFinder(), new vn.ptit.procon.engine.PlanValidator(), true)
                .plan(state);
        TeamFutureReadiness with = readiness(state, horizonPlan);
        String logs = capture(() -> new HorizonAwareTeamCoordinatorPlanner(
                new WeightedRouteFinder(), new RefuelRouteFinder(), new vn.ptit.procon.engine.PlanValidator(), true)
                .plan(state));

        assertEquals(without, with);
        assertTrue(logs.contains("TEAM_REFUEL_HORIZON_NO_ASSIGN"));
        assertTrue(logs.contains("reason=NO_FUTURE_READINESS_GAIN"));
        assertFalse(hasRefuel(state, horizonPlan));
    }

    @Test
    void twelveByTwelveHealthyFuelDoesNotInventRefuelOrChangeM121Values() {
        DayState state = twelveByTwelveHealthyFuelState();
        AnytimePlanResult m121 = new SemiCommitmentAwareStratifiedPlanner(
                NO_SEARCH, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), NO_STAGES, false).planWithStats(state);
        AnytimePlanResult m13 = new HorizonAwareSemiCommitmentPlanner(
                NO_SEARCH, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), NO_STAGES, false).planWithStats(state);

        SemiCommitmentAwarePlanEvaluation oldValue = m121.semiCommitmentAwareEvaluation().orElseThrow();
        HorizonAwarePlanEvaluation newValue = m13.horizonAwareEvaluation().orElseThrow();
        assertEquals(oldValue.semiCommitmentRealizableBrandCount(),
                newValue.semiCommitment().semiCommitmentRealizableBrandCount());
        assertEquals(oldValue.adjustedCollectionScore(), newValue.semiCommitment().adjustedCollectionScore());
        assertEquals(oldValue.semiCommitmentRealizableCollections(),
                newValue.semiCommitment().semiCommitmentRealizableCollections());
        assertFalse(hasRefuel(state, m13.plan()));
        assertFalse(hasRefuel(state, m121.plan()));
        assertEquals(actionsOf(m121.plan()), actionsOf(m13.plan()));
    }

    @Test
    void sixteenBySixteenLongRouteRequiresM13ProactiveRefuel() {
        DayState state = sixteenBySixteenLongRouteState();
        String oldLogs = capture(() -> new TeamCoordinatorPlanner().plan(state));
        TeamPlan oldPlan = new TeamCoordinatorPlanner().plan(state);
        TeamPlan horizonPlan = new HorizonAwareTeamCoordinatorPlanner(
                new WeightedRouteFinder(), new RefuelRouteFinder(), new vn.ptit.procon.engine.PlanValidator(), true)
                .plan(state);
        String horizonLogs = capture(() -> new HorizonAwareTeamCoordinatorPlanner(
                new WeightedRouteFinder(), new RefuelRouteFinder(), new vn.ptit.procon.engine.PlanValidator(), true)
                .plan(state));

        TeamFutureReadiness oldReadiness = readiness(state, oldPlan);
        TeamFutureReadiness horizonReadiness = readiness(state, horizonPlan);
        assertTrue(oldLogs.contains("TEAM_REFUEL_NO_ASSIGN day=0 reason=NO_POSITIVE_TEAM_VALUE"));
        assertTrue(horizonLogs.contains("TEAM_REFUEL_HORIZON_ASSIGN"));
        assertTrue(hasRefuel(state, horizonPlan));
        assertEquals(0, oldReadiness.futureReadyPatrolCount());
        assertTrue(horizonReadiness.futureReadyPatrolCount() > oldReadiness.futureReadyPatrolCount());

        AnytimePlanResult oldValue = new SemiCommitmentAwareStratifiedPlanner(
                NO_SEARCH, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), NO_STAGES, false).planWithStats(state);
        AnytimePlanResult newValue = new HorizonAwareSemiCommitmentPlanner(
                NO_SEARCH, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), NO_STAGES, false).planWithStats(state);
        SemiCommitmentAwarePlanEvaluation oldSemi = oldValue.semiCommitmentAwareEvaluation().orElseThrow();
        SemiCommitmentAwarePlanEvaluation newSemi = newValue.horizonAwareEvaluation().orElseThrow().semiCommitment();
        assertEquals(oldSemi.semiCommitmentRealizableBrandCount(), newSemi.semiCommitmentRealizableBrandCount());
        assertEquals(oldSemi.adjustedCollectionScore(), newSemi.adjustedCollectionScore());
        assertEquals(oldSemi.semiCommitmentRealizableCollections(), newSemi.semiCommitmentRealizableCollections());
    }

    @Test
    void legalEarlierRefillCollapsesResourceReadinessGap() {
        DayState state = sixteenBySixteenLongRouteState();
        TeamPlan withoutPlan = new TeamCoordinatorPlanner().plan(state);
        TeamPlan withPlan = new HorizonAwareTeamCoordinatorPlanner(
                new WeightedRouteFinder(), new RefuelRouteFinder(), new vn.ptit.procon.engine.PlanValidator(), false)
                .plan(state);
        TeamFutureReadiness without = readiness(state, withoutPlan);
        TeamFutureReadiness with = readiness(state, withPlan);

        assertTrue(with.futureReadyPatrolCount() > without.futureReadyPatrolCount());
        assertTrue(with.totalReachableOpportunitySpots() > without.totalReachableOpportunitySpots());
        assertTrue(hasRefuel(state, withPlan));
    }

    @Test
    void equalTotalFuelPrefersStrategicallyDistributedFuel() {
        DayState concentrated = fuelDistributionState(List.of(10, 0));
        DayState distributed = fuelDistributionState(List.of(5, 5));
        TeamFutureReadiness concentratedReadiness = readiness(concentrated, waitPlan(concentrated));
        TeamFutureReadiness distributedReadiness = readiness(distributed, waitPlan(distributed));

        assertEquals(concentratedReadiness.totalProjectedPatrolFuel(),
                distributedReadiness.totalProjectedPatrolFuel());
        assertTrue(distributedReadiness.futureReadyPatrolCount()
                > concentratedReadiness.futureReadyPatrolCount());
        HorizonAwarePlanEvaluation concentratedValue = equalSemi(concentratedReadiness, "a");
        HorizonAwarePlanEvaluation distributedValue = equalSemi(distributedReadiness, "b");
        assertTrue(distributedValue.betterThan(concentratedValue));
    }

    @Test
    void currentSemiCollectionDominatesSubstantiallyBetterFutureReadiness() {
        SemiCommitmentAwarePlanEvaluation currentWinner = semiEvaluation(2, 4, 8, "a");
        SemiCommitmentAwarePlanEvaluation futureWinner = semiEvaluation(2, 3, 8, "b");
        assertTrue(new HorizonAwarePlanEvaluation(currentWinner, syntheticReadiness(0, 0, 0))
                .betterThan(new HorizonAwarePlanEvaluation(futureWinner, syntheticReadiness(5, 8, 8))));
    }

    @Test
    void equalCurrentSemiValueUsesFutureReadinessOnNonFinalDay() {
        SemiCommitmentAwarePlanEvaluation a = semiEvaluation(2, 3, 8, "a");
        SemiCommitmentAwarePlanEvaluation b = semiEvaluation(2, 3, 8, "b");
        assertTrue(new HorizonAwarePlanEvaluation(b, syntheticReadiness(3, 5, 5))
                .betterThan(new HorizonAwarePlanEvaluation(a, syntheticReadiness(1, 1, 1))));
    }

    @Test
    void finalDayFutureFieldsAreExactlyNeutralAndMatchM121Ordering() {
        DayState lowFuelFinalDay = finalDayState(0);
        DayState highFuelFinalDay = finalDayState(5);
        TeamPlan lowFuelPlan = waitPlan(lowFuelFinalDay);
        TeamPlan highFuelPlan = highFuelWaitPlan(highFuelFinalDay);
        ValidDaySimulationResult lowFuelResult = (ValidDaySimulationResult) new DaySimulator()
                .simulate(lowFuelFinalDay, lowFuelPlan);
        ValidDaySimulationResult highFuelResult = (ValidDaySimulationResult) new DaySimulator()
                .simulate(highFuelFinalDay, highFuelPlan);
        assertNotEquals(lowFuelResult.finalAgents().getFirst().fuel(), highFuelResult.finalAgents().getFirst().fuel());
        TeamFutureReadiness aFuture = readiness(lowFuelFinalDay, lowFuelPlan);
        TeamFutureReadiness bFuture = readiness(highFuelFinalDay, highFuelPlan);
        assertEquals(0, aFuture.remainingFutureDays());
        assertEquals(0, bFuture.remainingFutureDays());
        assertEquals(0, aFuture.futureReadyPatrolCount());
        assertEquals(0, bFuture.totalReachableOpportunitySpots());
        SemiCommitmentAwarePlanEvaluation a = semiEvaluation(2, 3, 8, "a");
        SemiCommitmentAwarePlanEvaluation b = semiEvaluation(2, 3, 8, "b");
        HorizonAwarePlanEvaluation ah = new HorizonAwarePlanEvaluation(a, aFuture);
        HorizonAwarePlanEvaluation bh = new HorizonAwarePlanEvaluation(b, bFuture);
        int horizonSign = Integer.signum(HorizonAwarePlanEvaluation.preference().compare(ah, bh));
        int m121Sign = Integer.signum(SemiCommitmentAwarePlanEvaluation.preference().compare(a, b));
        assertEquals(m121Sign, horizonSign);
    }

    @Test
    void m121ModeIsolatedAndM13StaysWithinProductionBounds() {
        DayState state = twelveByTwelveHealthyFuelState();
        AnytimePlanResult oldResult = new SemiCommitmentAwareStratifiedPlanner().planWithStats(state);
        AnytimePlanResult oldRepeat = new SemiCommitmentAwareStratifiedPlanner().planWithStats(state);
        AnytimePlanResult horizon = new HorizonAwareSemiCommitmentPlanner().planWithStats(state);
        StratifiedSearchStats depth = horizon.stratifiedSearchStats().orElseThrow();

        assertEquals(actionsOf(oldResult.plan()), actionsOf(oldRepeat.plan()));
        assertEquals(oldResult.evaluation(), oldRepeat.evaluation());
        assertEquals(oldResult.semiCommitmentAwareEvaluation(), oldRepeat.semiCommitmentAwareEvaluation());
        assertEquals(oldResult.stats(), oldRepeat.stats());
        assertTrue(oldResult.horizonAwareEvaluation().isEmpty());
        assertTrue(horizon.semiCommitmentAwareEvaluation().isEmpty());
        assertTrue(horizon.horizonAwareEvaluation().isPresent());
        assertTrue(horizon.stats().expandedStates() <= 64);
        assertTrue(depth.frontierPeak() <= 48);
        assertTrue(depth.strategiesQualified() <= 8);
        assertTrue(horizon.stats().budgetExhausted());
        assertEquals(64, horizon.stats().expandedStates());
        assertEquals(64, depth.totalExpansions());
        assertEquals(64, depth.discoveryExpansions() + depth.qualificationExpansions()
                + depth.exploitationExpansions());
    }

    @Test
    void horizonPlanningIsDeterministicIncludingBoundedDiagnostics() {
        DayState state = sixteenBySixteenLongRouteState();
        Run first = runHorizon(state);
        Run second = runHorizon(state);
        assertEquals(first.actions(), second.actions());
        assertEquals(first.evaluation(), second.evaluation());
        assertEquals(first.stats(), second.stats());
        assertEquals(first.stratified(), second.stratified());
        assertEquals(first.logs(), second.logs());
        assertEquals(first.horizon(), second.horizon());
        assertEquals(first.horizon().futureReadiness(), second.horizon().futureReadiness());
    }

    @Test
    void diagnosticsExposeRequiredBoundedLiveFields() {
        Run run = runHorizon(sixteenBySixteenLongRouteState());
        assertTrue(run.logs().contains("HORIZON_FUEL_SUMMARY"));
        assertTrue(run.logs().contains("TEAM_REFUEL_HORIZON_ASSIGN"));
        assertTrue(run.logs().contains("ANYTIME_STRATIFIED_HORIZON_START"));
        assertTrue(run.logs().contains("ANYTIME_STRATIFIED_HORIZON_DONE"));
        for (String field : List.of("day=0", "remainingFutureDays=3", "patrolAgents=5",
                "projectedTotalPatrolFuel=", "futureReadyPatrolCount=", "reachableOpportunitySpots=",
                "reachableOpportunityBrands=", "minimumPatrolReadiness=")) {
            assertTrue(run.logs().contains(field), "Missing horizon summary field " + field);
        }
        for (String field : List.of("refuelAgent=", "patrolAgent=", "currentBrandDelta=0",
                "currentSemiCollectionDelta=0", "futureReadyPatrolDelta=", "futureReachableSpotDelta=",
                "futureReachableBrandDelta=", "fuelRestored=", "arrivalStep=")) {
            assertTrue(run.logs().contains(field), "Missing REFUEL diagnostic field " + field);
        }
        for (String field : List.of("incumbentSemiBrands=", "incumbentSemiScore=",
                "incumbentSemiCollections=", "incumbentRawUdon=", "incumbentFutureReadyPatrols=",
                "incumbentFutureReachableSpots=", "incumbentFutureReachableBrands=", "budget=64",
                "discoveryBudget=16", "qualificationBudget=24", "exploitationBudget=24")) {
            assertTrue(run.logs().contains(field), "Missing START field " + field);
        }
        for (String field : List.of("semiBrands=", "semiScore=", "semiCollections=", "rawUdon=",
                "projectedFinalPatrolFuel=", "futureReadyPatrols=", "futureReachableSpots=",
                "futureReachableBrands=", "minimumPatrolReadiness=", "expanded=", "completedPlans=",
                "improvements=", "strategiesDiscovered=", "strategiesQualified=", "strategiesWithAtLeast2Expansions=",
                "strategiesWithAtLeast3Expansions=", "maxStrategyExpansionCount=", "discoveryExpansions=",
                "qualificationExpansions=", "exploitationExpansions=", "frontierPeak=", "budgetExhausted=")) {
            assertTrue(run.logs().contains(field), "Missing DONE field " + field);
        }
        for (String prefix : List.of("HORIZON_FUEL_SUMMARY ", "TEAM_REFUEL_HORIZON_ASSIGN ",
                "ANYTIME_STRATIFIED_HORIZON_START ", "ANYTIME_STRATIFIED_HORIZON_DONE ")) {
            System.out.println(run.logs().lines()
                    .filter(line -> line.startsWith(prefix)).findFirst().orElseThrow());
        }
    }

    @Test
    void reverseRouteCacheIsBuiltPerStaticOpportunityAndNeverPerChallenger() {
        DayState state = sixteenBySixteenLongRouteState();
        FutureReadinessCalculator calculator = FutureReadinessCalculator.forState(state);
        TeamFutureReadiness first = calculator.evaluate(readinessSimulation(state));
        TeamFutureReadiness second = calculator.evaluate(readinessSimulation(state));
        assertEquals(state.matchData().udonSpots().size(), calculator.pathfindingExecutions());
        assertEquals(calculator.routeCostCacheEntries(), first.routeCostCacheEntries());
        assertEquals(calculator.pathfindingExecutions(), first.routeCostPathfindingExecutions());
        assertEquals(first, second);
        assertEquals(state.matchData().udonSpots().size(), calculator.pathfindingExecutions(),
                "Repeated complete challengers do not trigger new path searches");
    }

    private static Run runHorizon(DayState state) {
        HorizonAwareSemiCommitmentPlanner planner = new HorizonAwareSemiCommitmentPlanner(
                AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.defaults(), true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        AnytimePlanResult result;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            result = planner.planWithStats(state);
        } finally {
            System.setOut(original);
        }
        return new Run(actionsOf(result.plan()), result.evaluation(), result.stats(),
                result.stratifiedSearchStats().orElseThrow(), output.toString(StandardCharsets.UTF_8),
                result.horizonAwareEvaluation().orElseThrow());
    }

    private static TeamFutureReadiness readiness(DayState state, TeamPlan plan) {
        return FutureReadinessCalculator.forState(state).evaluate(
                (ValidDaySimulationResult) new DaySimulator().simulate(state, plan));
    }

    private static ValidDaySimulationResult readinessSimulation(DayState state) {
        return (ValidDaySimulationResult) new DaySimulator().simulate(state, waitPlan(state));
    }

    private static TeamPlan waitPlan(DayState state) {
        return new WaitDayPlanner().plan(state);
    }

    private static TeamPlan highFuelWaitPlan(DayState state) {
        return waitPlan(state);
    }

    private static boolean hasRefuel(DayState state, TeamPlan plan) {
        return ((ValidDaySimulationResult) new DaySimulator().simulate(state, plan)).events().stream()
                .anyMatch(event -> event.getClass().getSimpleName().equals("RefueledEvent"));
    }

    private static HorizonAwarePlanEvaluation equalSemi(
            TeamFutureReadiness readiness, String signature) {
        return new HorizonAwarePlanEvaluation(semiEvaluation(1, 1, 1, signature), readiness);
    }

    private static SemiCommitmentAwarePlanEvaluation semiEvaluation(
            int brands, int collections, int score, String signature) {
        PlanEvaluation base = new PlanEvaluation(brands, collections, 1, 10, 1, signature);
        return new SemiCommitmentAwarePlanEvaluation(base,
                new SemiCommitmentAdjustedCollectionScore(score), brands, collections,
                collections, collections, 0, 0, 0, 0, 0, 0);
    }

    private static TeamFutureReadiness syntheticReadiness(int ready, int brands, int spots) {
        List<PatrolFutureReadiness> patrols = IntStream.range(0, Math.max(1, ready))
                .mapToObj(index -> new PatrolFutureReadiness(new AgentId(index), new Position(0), 10,
                        1, Set.of(new Position(index + 1)), Set.of(new BrandId("b" + index)), 1, 9))
                .toList();
        return new TeamFutureReadiness(1, patrols, ready, spots, brands, ready == 0 ? 0 : 1,
                ready * 10, 0, 0);
    }

    private static String capture(Runnable action) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static Map<Integer, List<String>> actionsOf(TeamPlan plan) {
        Map<Integer, List<String>> result = new TreeMap<>();
        plan.actionsByAgent().forEach((agent, actions) -> result.put(
                agent.value(), actions.stream().map(Object::toString).toList()));
        return result;
    }

    private static DayState eightByEightHealthyFuelState() {
        return state(8, 8, new int[] {6, 19, 19, 19}, 30,
                List.of(AgentState.patrol(new AgentId(0), new Position(27), 30),
                        AgentState.patrol(new AgentId(1), new Position(28), 30),
                        AgentState.patrol(new AgentId(2), new Position(36), 30),
                        AgentState.refuel(new AgentId(3), new Position(35))),
                List.of(spot("A", 18), spot("B", 19), spot("C", 20), spot("D", 26),
                        spot("A", 29), spot("B", 34), spot("C", 37), spot("D", 45)));
    }

    private static DayState twelveByTwelveHealthyFuelState() {
        List<AgentState> agents = new ArrayList<>(IntStream.range(0, 5)
                .mapToObj(index -> AgentState.patrol(new AgentId(index), new Position(62 + index), 30))
                .toList());
        agents.add(AgentState.refuel(new AgentId(5), new Position(76)));
        return state(12, 12, new int[] {24, 24, 24}, 30,
                agents,
                List.of(spot("A", 55), spot("B", 56), spot("C", 67), spot("D", 68),
                        spot("A", 79), spot("B", 80), spot("C", 91), spot("D", 92)));
    }

    private static DayState sixteenBySixteenLongRouteState() {
        return state(16, 16, new int[] {3, 20, 20, 20}, 30,
                List.of(AgentState.patrol(new AgentId(0), new Position(136), 0),
                        AgentState.patrol(new AgentId(1), new Position(137), 0),
                        AgentState.patrol(new AgentId(2), new Position(120), 0),
                        AgentState.patrol(new AgentId(3), new Position(121), 0),
                        AgentState.patrol(new AgentId(4), new Position(122), 0),
                        AgentState.refuel(new AgentId(5), new Position(135))),
                List.of(spot("A", 0), spot("B", 4), spot("C", 8), spot("D", 12),
                        spot("A", 240), spot("B", 244), spot("C", 248), spot("D", 252)));
    }

    private static DayState fuelDistributionState(List<Integer> fuel) {
        return state(12, 1, new int[] {6, 6}, 10,
                List.of(AgentState.patrol(new AgentId(0), new Position(0), fuel.get(0)),
                        AgentState.patrol(new AgentId(1), new Position(11), fuel.get(1))),
                List.of(spot("A", 1), spot("B", 10)));
    }

    private static DayState finalDayState(int fuel) {
        return stateAtDay(1, 8, 8, new int[] {6, 6}, 30,
                List.of(AgentState.patrol(new AgentId(0), new Position(27), fuel)),
                List.of(spot("A", 18), spot("B", 45)));
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), new Position(position), 1);
    }

    private static DayState state(
            int width, int height, int[] budgets, int capacity,
            List<AgentState> agents, List<UdonSpot> spots) {
        return stateAtDay(0, width, height, budgets, capacity, agents, spots);
    }

    private static DayState stateAtDay(
            int day, int width, int height, int[] budgets, int capacity,
            List<AgentState> agents, List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[width * height];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new HashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(width, height, terrain),
                new DayStepBudgets(budgets), List.of(), new FuelCapacity(capacity), spots);
        return new DayState(match, new DayIndex(day), agents, Map.of(), stock);
    }

    private record Run(
            Map<Integer, List<String>> actions,
            PlanEvaluation evaluation,
            AnytimeSearchStats stats,
            StratifiedSearchStats stratified,
            String logs,
            HorizonAwarePlanEvaluation horizon) { }
}