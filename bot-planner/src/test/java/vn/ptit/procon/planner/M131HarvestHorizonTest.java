package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

class M131HarvestHorizonTest {

    @Test
    void multiSpotDpUsesAuthoritativeNextDayBudgetAndOfficialTerrainFuel() {
        DayState state = state(0, 8, 1, new int[] {3, 8}, 10,
                List.of(AgentState.patrol(id(0), pos(0), 4)),
                List.of(spot("A", 0), spot("B", 2), spot("C", 4), spot("D", 6)));

        TeamNextDayHarvestCapacity team = capacity(state, SafePlanFactory.waitAll(state));
        PatrolNextDayHarvestCapacity patrol = team.patrols().getFirst();

        assertEquals(8, team.nextDayStepBudget());
        assertEquals(3, patrol.maxReachableDistinctSpots());
        assertEquals(3, patrol.maxReachableDistinctBrands());
        assertEquals(0, patrol.bestRemainingFuelAtMaxSpotCount());
        assertTrue(patrol.dpStatesEvaluated() > 0);
    }

    @Test
    void eightByEightHealthyFuelDoesNotCauseFuelOnlyProactiveRefuel() {
        DayState state = withEmptyCurrentStock(healthyState(8, 3, 30, 20));
        String logs = capture(() -> new HarvestHorizonAwareTeamCoordinatorPlanner(
                new WeightedRouteFinder(), new RefuelRouteFinder(),
                new vn.ptit.procon.engine.PlanValidator(), true).plan(state));
        TeamPlan plan = new HarvestHorizonAwareTeamCoordinatorPlanner().plan(state);
        TeamNextDayHarvestCapacity capacity = capacity(state, plan);

        assertEquals(3, capacity.patrols().size());
        assertTrue(capacity.minimumPatrolDistinctSpots() > 1);
        assertFalse(hasRefuel(state, plan));
        assertTrue(logs.contains("TEAM_REFUEL_HARVEST_HORIZON_NO_ASSIGN"));
        assertTrue(logs.contains("reason=NO_HARVEST_CAPACITY_GAIN"));

        AnytimePlannerConfig none = new AnytimePlannerConfig(0, 48, 4);
        StratifiedSearchConfig noStages = StratifiedSearchConfig.forBudget(0);
        SemiCommitmentAwarePlanEvaluation old = new SemiCommitmentAwareStratifiedPlanner(
                none, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), noStages, false).planWithStats(state)
                .semiCommitmentAwareEvaluation().orElseThrow();
        SemiCommitmentAwarePlanEvaluation next = new HarvestHorizonAwareSemiCommitmentPlanner(
                none, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), noStages, false).planWithStats(state)
                .harvestHorizonAwareEvaluation().orElseThrow().semiCommitment();
        assertEquals(old.semiCommitmentRealizableBrandCount(),
                next.semiCommitmentRealizableBrandCount());
        assertEquals(old.semiCommitmentRealizableCollections(),
                next.semiCommitmentRealizableCollections());
    }

    @Test
    void twelveByTwelveZeroFuelStationaryPatrolIsStrictlyWeaker() {
        List<AgentState> agents = new ArrayList<>();
        agents.add(AgentState.patrol(id(0), pos(65), 0));
        for (int index = 1; index < 5; index++) {
            agents.add(AgentState.patrol(id(index), pos(65 + index), 30));
        }
        DayState state = state(0, 12, 12, new int[] {3, 24}, 30, agents,
                clusteredSpots(12, 5, 5));

        TeamNextDayHarvestCapacity team = capacity(state, SafePlanFactory.waitAll(state));
        PatrolNextDayHarvestCapacity stranded = team.patrols().getFirst();
        PatrolNextDayHarvestCapacity healthy = team.patrols().get(1);

        assertTrue(stranded.stationaryOpportunityAvailable());
        assertEquals(1, stranded.maxReachableDistinctSpots());
        assertEquals(1, stranded.maxReachableDistinctBrands());
        assertTrue(healthy.maxReachableDistinctSpots() > stranded.maxReachableDistinctSpots());
        assertEquals(stranded.ordinal(), team.weakestFirstCapacityVector().getFirst());
    }

    @Test
    void sixteenBySixteenOldReadyBooleanSaturatesButCapacityVectorExposesWeakAgents() {
        List<UdonSpot> spots = cornerSpots();
        DayState state = state(0, 16, 16, new int[] {3, 24}, 30,
                List.of(
                        AgentState.patrol(id(0), pos(0), 0),
                        AgentState.patrol(id(1), pos(4), 0),
                        AgentState.patrol(id(2), pos(8), 0),
                        AgentState.patrol(id(3), pos(240), 30),
                        AgentState.patrol(id(4), pos(252), 30)), spots);
        ValidDaySimulationResult simulation = simulate(state, SafePlanFactory.waitAll(state));
        TeamFutureReadiness old = FutureReadinessCalculator.forState(state).evaluate(simulation);
        TeamNextDayHarvestCapacity next = NextDayHarvestCapacityCalculator.forState(state).evaluate(simulation);

        assertEquals(5, old.futureReadyPatrolCount());
        assertEquals(List.of(1, 1, 1), next.weakestFirstCapacityVector().stream()
                .limit(3).map(HarvestCapacityOrdinal::distinctSpots).toList());
        assertTrue(next.weakestFirstCapacityVector().getLast().distinctSpots() > 1);
    }

    @Test
    void searchDestroysHorizonRegressionInvertsOldM13SoftScoreChoice() {
        SemiCommitmentAwarePlanEvaluation highScore = semi(4, 20, 67, "a");
        SemiCommitmentAwarePlanEvaluation highCapacity = semi(4, 20, 65, "b");
        TeamFutureReadiness oldPoor = oldReadiness(5, 11, 19);
        TeamFutureReadiness oldGood = oldReadiness(5, 14, 26);
        TeamNextDayHarvestCapacity poor = syntheticCapacity(1, 1, 5);
        TeamNextDayHarvestCapacity good = syntheticCapacity(4, 4, 5);

        assertTrue(new HorizonAwarePlanEvaluation(highScore, oldPoor)
                .betterThan(new HorizonAwarePlanEvaluation(highCapacity, oldGood)));
        assertTrue(new HarvestHorizonAwarePlanEvaluation(highCapacity, good)
                .betterThan(new HarvestHorizonAwarePlanEvaluation(highScore, poor)));
    }

    @Test
    void extraCurrentSemiCollectionStillDominatesExcellentFutureCapacity() {
        HarvestHorizonAwarePlanEvaluation current = new HarvestHorizonAwarePlanEvaluation(
                semi(4, 21, 60, "a"), syntheticCapacity(1, 1, 5));
        HarvestHorizonAwarePlanEvaluation future = new HarvestHorizonAwarePlanEvaluation(
                semi(4, 20, 80, "b"), syntheticCapacity(8, 4, 5));

        assertTrue(current.betterThan(future));
    }

    @Test
    void equalTotalFuelPrefersDistributedHarvestCapacityVector() {
        List<UdonSpot> spots = clusteredSpots(12, 0, 0);
        DayState concentrated = state(0, 12, 12, new int[] {3, 20}, 30,
                fuelAgents(List.of(0, 0, 0, 15, 15)), spots);
        DayState distributed = state(0, 12, 12, new int[] {3, 20}, 30,
                fuelAgents(List.of(6, 6, 6, 6, 6)), spots);
        TeamNextDayHarvestCapacity a = capacity(concentrated, SafePlanFactory.waitAll(concentrated));
        TeamNextDayHarvestCapacity b = capacity(distributed, SafePlanFactory.waitAll(distributed));

        assertEquals(a.totalProjectedPatrolFuel(), b.totalProjectedPatrolFuel());
        assertTrue(b.betterStructuralCapacityThan(a));
        assertTrue(b.minimumPatrolDistinctSpots() > a.minimumPatrolDistinctSpots());
    }

    @Test
    void capacityImprovingLegalRefillAssignsWhileOldCoordinatorDoesNot() {
        DayState state = longRouteRefuelState();
        String oldLogs = capture(() -> new TeamCoordinatorPlanner().plan(state));
        TeamPlan oldPlan = new TeamCoordinatorPlanner().plan(state);
        String newLogs = capture(() -> new HarvestHorizonAwareTeamCoordinatorPlanner(
                new WeightedRouteFinder(), new RefuelRouteFinder(),
                new vn.ptit.procon.engine.PlanValidator(), true).plan(state));
        TeamPlan newPlan = new HarvestHorizonAwareTeamCoordinatorPlanner().plan(state);
        TeamNextDayHarvestCapacity without = capacity(state, oldPlan);
        TeamNextDayHarvestCapacity with = capacity(state, newPlan);

        assertTrue(oldLogs.contains("TEAM_REFUEL_NO_ASSIGN day=0 reason=NO_POSITIVE_TEAM_VALUE"));
        assertFalse(hasRefuel(state, oldPlan));
        assertTrue(hasRefuel(state, newPlan));
        assertTrue(with.betterStructuralCapacityThan(without));
        assertTrue(newLogs.contains("TEAM_REFUEL_HARVEST_HORIZON_ASSIGN"));
        assertTrue(newLogs.contains("currentSemiBrandDelta=0 currentSemiCollectionDelta=0"));
    }

    @Test
    void fuelOnlyRefillWithIdenticalHarvestCapacityIsRejected() {
        DayState state = state(0, 4, 1, new int[] {3, 1}, 10,
                List.of(AgentState.patrol(id(0), pos(0), 0), AgentState.refuel(id(1), pos(1))),
                List.of(spot("A", 0), spot("B", 3)));
        TeamPlan oldPlan = new TeamCoordinatorPlanner().plan(state);
        String logs = capture(() -> new HarvestHorizonAwareTeamCoordinatorPlanner(
                new WeightedRouteFinder(), new RefuelRouteFinder(),
                new vn.ptit.procon.engine.PlanValidator(), true).plan(state));
        TeamPlan plan = new HarvestHorizonAwareTeamCoordinatorPlanner().plan(state);

        assertEquals(capacity(state, oldPlan).weakestFirstCapacityVector(),
                capacity(state, plan).weakestFirstCapacityVector());
        assertFalse(hasRefuel(state, plan));
        assertTrue(logs.contains("reason=NO_HARVEST_CAPACITY_GAIN"));
    }

    @Test
    void finalDayCapacityIsZeroAndCannotChangeM121Ordering() {
        DayState state = withEmptyCurrentStock(state(1, 8, 8, new int[] {3, 20}, 30,
                List.of(AgentState.patrol(id(0), pos(27), 0), AgentState.refuel(id(1), pos(28))),
                clusteredSpots(8, 2, 2)));
        TeamNextDayHarvestCapacity zero = capacity(state, SafePlanFactory.waitAll(state));
        HarvestHorizonAwarePlanEvaluation highScore = new HarvestHorizonAwarePlanEvaluation(
                semi(2, 4, 12, "a"), zero);
        HarvestHorizonAwarePlanEvaluation lowScore = new HarvestHorizonAwarePlanEvaluation(
                semi(2, 4, 10, "b"), zero);
        TeamPlan plan = new HarvestHorizonAwareTeamCoordinatorPlanner().plan(state);

        assertEquals(0, zero.nextDayStepBudget());
        assertEquals(0, zero.totalPatrolDistinctSpotCapacity());
        assertTrue(highScore.betterThan(lowScore));
        assertFalse(hasRefuel(state, plan));
    }

    @Test
    void finalDayStillAllowsImmediateSameDayPositiveRefuelValue() {
        DayState state = state(1, 4, 1, new int[] {3, 4}, 5,
                List.of(AgentState.patrol(id(0), pos(0), 0), AgentState.refuel(id(1), pos(1))),
                List.of(spot("A", 1)));

        TeamPlan plan = new HarvestHorizonAwareTeamCoordinatorPlanner().plan(state);

        assertTrue(hasRefuel(state, plan));
        assertEquals(1, simulate(state, plan).portionsCollectedByAgent().get(id(0)));
        assertEquals(0, capacity(state, plan).totalPatrolDistinctSpotCapacity());
    }

    @Test
    void oldM121AndM13ModesRemainIsolatedAndDeterministic() {
        DayState state = healthyState(12, 5, 30, 24);
        AnytimePlannerConfig none = new AnytimePlannerConfig(0, 48, 4);
        StratifiedSearchConfig stages = StratifiedSearchConfig.forBudget(0);
        AnytimePlanResult m121a = new SemiCommitmentAwareStratifiedPlanner(
                none, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), stages, false).planWithStats(state);
        AnytimePlanResult m121b = new SemiCommitmentAwareStratifiedPlanner(
                none, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), stages, false).planWithStats(state);
        AnytimePlanResult m13a = new HorizonAwareSemiCommitmentPlanner(
                none, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), stages, false).planWithStats(state);
        AnytimePlanResult m13b = new HorizonAwareSemiCommitmentPlanner(
                none, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), stages, false).planWithStats(state);

        assertEquals(actionsOf(m121a.plan()), actionsOf(m121b.plan()));
        assertEquals(m121a.semiCommitmentAwareEvaluation(), m121b.semiCommitmentAwareEvaluation());
        assertEquals(m121a.stats(), m121b.stats());
        assertTrue(m121a.harvestHorizonAwareEvaluation().isEmpty());
        assertEquals(actionsOf(m13a.plan()), actionsOf(m13b.plan()));
        assertEquals(m13a.horizonAwareEvaluation(), m13b.horizonAwareEvaluation());
        assertEquals(m13a.stats(), m13b.stats());
        assertTrue(m13a.harvestHorizonAwareEvaluation().isEmpty());
    }

    @Test
    void productionSearchBoundsDeterminismDiagnosticsAndCacheAccountingHold() {
        DayState state = healthyState(12, 5, 30, 24);
        HarvestHorizonAwareSemiCommitmentPlanner planner = new HarvestHorizonAwareSemiCommitmentPlanner(
                AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.defaults(), true);
        Run first = captureRun(planner, state);
        Run second = captureRun(planner, state);
        StratifiedSearchStats depth = first.result.stratifiedSearchStats().orElseThrow();
        TeamNextDayHarvestCapacity capacity = first.result.harvestHorizonAwareEvaluation()
                .orElseThrow().nextDayHarvestCapacity();

        assertEquals(actionsOf(first.result.plan()), actionsOf(second.result.plan()));
        assertEquals(first.result.harvestHorizonAwareEvaluation(), second.result.harvestHorizonAwareEvaluation());
        assertEquals(first.result.stats(), second.result.stats());
        assertEquals(first.result.stratifiedSearchStats(), second.result.stratifiedSearchStats());
        assertEquals(first.logs, second.logs);
        assertTrue(first.result.stats().expandedStates() <= 64);
        assertTrue(depth.frontierPeak() <= 48);
        assertTrue(depth.strategiesQualified() <= 8);
        if (first.result.stats().budgetExhausted()) {
            assertEquals(64, depth.totalExpansions());
            assertEquals(16, depth.discoveryExpansions());
            assertEquals(24, depth.qualificationExpansions());
            assertEquals(24, depth.exploitationExpansions());
        }
        assertEquals(8, capacity.pathfindingExecutions());
        assertEquals(8, NextDayHarvestCapacityCalculator.forState(state).opportunityCount());
        assertTrue(capacity.routeCostCacheEntries() > 0);
        assertTrue(capacity.totalDpStatesEvaluated() > 0);
        assertTrue(first.logs.contains("NEXT_DAY_HARVEST_CAPACITY_SUMMARY"));
        assertTrue(first.logs.contains("PATROL_NEXT_DAY_HARVEST_CAPACITY"));
        assertTrue(first.logs.contains("TEAM_REFUEL_HARVEST_HORIZON_NO_ASSIGN"));
        assertTrue(first.logs.contains("ANYTIME_STRATIFIED_HARVEST_HORIZON_START"));
        assertTrue(first.logs.contains("ANYTIME_STRATIFIED_HARVEST_HORIZON_DONE"));
        assertTrue(first.logs.contains("nextDayStepBudget=24"));
        assertTrue(first.logs.contains("routeCostCacheEntries="));
        assertTrue(first.logs.contains("pathfindingExecutions=8"));
    }

    private static Run captureRun(HarvestHorizonAwareSemiCommitmentPlanner planner, DayState state) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            return new Run(planner.planWithStats(state), output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    private static TeamNextDayHarvestCapacity capacity(DayState state, TeamPlan plan) {
        return NextDayHarvestCapacityCalculator.forState(state).evaluate(simulate(state, plan));
    }

    private static ValidDaySimulationResult simulate(DayState state, TeamPlan plan) {
        return (ValidDaySimulationResult) new DaySimulator().simulate(state, plan);
    }

    private static boolean hasRefuel(DayState state, TeamPlan plan) {
        return simulate(state, plan).events().stream().anyMatch(RefueledEvent.class::isInstance);
    }

    private static Map<Integer, List<String>> actionsOf(TeamPlan plan) {
        Map<Integer, List<String>> actions = new java.util.TreeMap<>();
        plan.actionsByAgent().forEach((agent, values) -> actions.put(
                agent.value(), values.stream().map(Object::toString).toList()));
        return actions;
    }

    private static DayState withEmptyCurrentStock(DayState state) {
        Map<Position, Integer> empty = new HashMap<>();
        state.matchData().udonSpots().forEach(spot -> empty.put(spot.position(), 0));
        return new DayState(state.matchData(), state.day(), state.agents(), state.roadTraffic(), empty);
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

    private static SemiCommitmentAwarePlanEvaluation semi(
            int brands, int collections, int score, String signature) {
        return new SemiCommitmentAwarePlanEvaluation(
                new PlanEvaluation(brands, collections, 1, 10, 1, signature),
                new SemiCommitmentAdjustedCollectionScore(score), brands, collections,
                collections, collections, 0, 0, 0, 0, 0, 0);
    }

    private static TeamFutureReadiness oldReadiness(int ready, int brands, int spots) {
        List<PatrolFutureReadiness> patrols = java.util.stream.IntStream.range(0, ready)
                .mapToObj(index -> new PatrolFutureReadiness(
                        id(index), pos(index), 10, 1, java.util.Set.of(pos(index)),
                        java.util.Set.of(new BrandId("B" + index)), 0, 10)).toList();
        return new TeamFutureReadiness(1, patrols, ready, spots, brands, 1, ready * 10, 0, 0);
    }

    private static TeamNextDayHarvestCapacity syntheticCapacity(int spots, int brands, int patrols) {
        List<PatrolNextDayHarvestCapacity> values = java.util.stream.IntStream.range(0, patrols)
                .mapToObj(index -> new PatrolNextDayHarvestCapacity(
                        id(index), pos(index), 10, 1, false, spots, brands, 1, 1)).toList();
        return TeamNextDayHarvestCapacity.aggregate(1, 20, values, 0, 0);
    }

    private static DayState healthyState(int size, int patrols, int fuel, int nextBudget) {
        List<AgentState> agents = new ArrayList<>();
        int center = size * (size / 2) + size / 2;
        for (int index = 0; index < patrols; index++) {
            agents.add(AgentState.patrol(id(index), pos(center + index), fuel));
        }
        agents.add(AgentState.refuel(id(patrols), pos(center - 1)));
        return state(0, size, size, new int[] {6, nextBudget, nextBudget}, fuel,
                agents, clusteredSpots(size, size / 2 - 1, size / 2 - 1));
    }

    private static DayState longRouteRefuelState() {
        return state(0, 16, 16, new int[] {3, 24, 24, 24}, 30,
                List.of(
                        AgentState.patrol(id(0), pos(136), 0),
                        AgentState.patrol(id(1), pos(137), 0),
                        AgentState.patrol(id(2), pos(120), 0),
                        AgentState.patrol(id(3), pos(121), 0),
                        AgentState.patrol(id(4), pos(122), 0),
                        AgentState.refuel(id(5), pos(135))), cornerSpots());
    }

    private static List<AgentState> fuelAgents(List<Integer> fuel) {
        return java.util.stream.IntStream.range(0, fuel.size())
                .mapToObj(index -> AgentState.patrol(id(index), pos(index), fuel.get(index))).toList();
    }

    private static List<UdonSpot> cornerSpots() {
        return List.of(spot("A", 0), spot("B", 4), spot("C", 8), spot("D", 12),
                spot("A", 240), spot("B", 244), spot("C", 248), spot("D", 252));
    }

    private static List<UdonSpot> clusteredSpots(int width, int row, int column) {
        int base = row * width + column;
        return List.of(spot("A", base), spot("B", base + 1), spot("C", base + 2),
                spot("D", base + width), spot("A", base + width + 1),
                spot("B", base + width + 2), spot("C", base + 2 * width),
                spot("D", base + 2 * width + 1));
    }

    private static DayState state(
            int day, int width, int height, int[] budgets, int capacity,
            List<AgentState> agents, List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[width * height];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new HashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        return new DayState(new StaticMatchData(
                new HexMap(width, height, terrain), new DayStepBudgets(budgets), List.of(),
                new FuelCapacity(capacity), spots), new DayIndex(day), agents, Map.of(), stock);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), pos(position), 1);
    }

    private static AgentId id(int value) {
        return new AgentId(value);
    }

    private static Position pos(int value) {
        return new Position(value);
    }

    private record Run(AnytimePlanResult result, String logs) { }
}