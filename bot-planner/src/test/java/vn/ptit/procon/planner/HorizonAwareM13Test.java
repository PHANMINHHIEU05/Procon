package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

class HorizonAwareM13Test {

    private static final AgentId PATROL_0 = new AgentId(0);
    private static final AgentId PATROL_1 = new AgentId(1);

    @Test
    void remainingFutureDaysUseAuthoritativeDayCountAndFinalDayIsNeutral() {
        DayState dayZero = state(0, new int[] {3, 4, 5}, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 3)));
        FutureReadinessCalculator calculator = FutureReadinessCalculator.forState(dayZero);
        ValidDaySimulationResult simulation = simulate(dayZero);

        TeamFutureReadiness future = calculator.evaluate(simulation);
        assertEquals(2, future.remainingFutureDays());
        assertEquals(1, future.futureReadyPatrolCount());
        assertTrue(future.totalReachableOpportunitySpots() > 0);

        DayState finalDay = state(2, new int[] {3, 4, 5}, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 3)));
        TeamFutureReadiness finalFuture = FutureReadinessCalculator.forState(finalDay)
                .evaluate(simulate(finalDay));
        assertEquals(0, finalFuture.remainingFutureDays());
        assertEquals(0, finalFuture.futureReadyPatrolCount());
        assertEquals(0, finalFuture.totalReachableOpportunitySpots());
        assertEquals(0, finalFuture.totalReachableOpportunityBrands());
        assertEquals(0, finalFuture.totalProjectedPatrolFuel());
    }

    @Test
    void staticOpportunityCacheRunsOncePerSpotAndNotPerPatrol() {
        DayState state = state(0, new int[] {3, 4}, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 3),
                AgentState.patrol(PATROL_1, new Position(1), 3)));

        FutureReadinessCalculator calculator = FutureReadinessCalculator.forState(state);
        TeamFutureReadiness readiness = calculator.evaluate(simulate(state));

        assertEquals(state.matchData().udonSpots().size(), calculator.pathfindingExecutions());
        assertTrue(calculator.routeCostCacheEntries() >= calculator.pathfindingExecutions());
        assertEquals(calculator.routeCostCacheEntries(), readiness.routeCostCacheEntries());
        assertEquals(calculator.pathfindingExecutions(), readiness.routeCostPathfindingExecutions());
        assertEquals(2, readiness.patrols().size());
    }

    @Test
    void currentDaySemiCommitmentDominatesFutureReadiness() {
        PlanEvaluation base = new PlanEvaluation(2, 3, 1, 4, 2, "a");
        SemiCommitmentAwarePlanEvaluation currentTwo = semi(base, 2, 2, 5);
        SemiCommitmentAwarePlanEvaluation currentOne = semi(
                new PlanEvaluation(1, 3, 1, 4, 2, "b"), 1, 1, 5);
        HorizonAwarePlanEvaluation weakerCurrent = new HorizonAwarePlanEvaluation(
                currentOne, readiness(3, 3, 3, 5));
        HorizonAwarePlanEvaluation strongerCurrent = new HorizonAwarePlanEvaluation(
                currentTwo, readiness(0, 0, 0, 0));

        assertTrue(strongerCurrent.betterThan(weakerCurrent));
        assertTrue(!weakerCurrent.betterThan(strongerCurrent));
    }

    @Test
    void horizonPlannerPublishesOptionalEvaluationWithoutChangingPlanValidity() {
        DayState state = state(0, new int[] {3, 4}, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 3)));

        HorizonAwareSemiCommitmentPlanner planner = new HorizonAwareSemiCommitmentPlanner(
                new AnytimePlannerConfig(0, 8, 2),
                OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(0),
                false);
        AnytimePlanResult result = planner.planWithStats(state);

        assertTrue(result.horizonAwareEvaluation().isPresent());
        assertEquals(result.evaluation(),
                result.horizonAwareEvaluation().orElseThrow().semiCommitment().base());
    }

    @Test
    void proactiveRefuelIsSelectedOnlyWhenItImprovesFutureReadiness() {
        AgentId refuel = new AgentId(2);
        DayState state = state(0, new int[] {3, 4}, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 0),
                AgentState.refuel(refuel, new Position(1))));

        TeamPlan plan = new HorizonAwareTeamCoordinatorPlanner().plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertTrue(result.events().stream().anyMatch(RefueledEvent.class::isInstance));
        assertEquals(0, result.portionsCollectedByAgent().get(PATROL_0));
    }

    @Test
    void finalDayDoesNotTakeZeroCurrentGainProactiveRefuel() {
        AgentId refuel = new AgentId(2);
        DayState state = state(1, new int[] {3, 4}, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 0),
                AgentState.refuel(refuel, new Position(1))));

        TeamPlan plan = new HorizonAwareTeamCoordinatorPlanner().plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertTrue(result.events().stream().noneMatch(RefueledEvent.class::isInstance));
    }

    private static SemiCommitmentAwarePlanEvaluation semi(
            PlanEvaluation base, int brands, int collections, int score) {
        return new SemiCommitmentAwarePlanEvaluation(
                base, new SemiCommitmentAdjustedCollectionScore(score), brands, collections,
                collections, 0, 0, 0, 0, 0, 0, 0);
    }

    private static TeamFutureReadiness readiness(
            int ready, int brands, int spots, int fuel) {
        List<PatrolFutureReadiness> patrols = java.util.stream.IntStream
                .range(0, Math.max(1, ready))
                .mapToObj(index -> new PatrolFutureReadiness(
                        new AgentId(index), new Position(0), fuel, 1,
                        Set.of(), Set.of(), 0, 0))
                .toList();
        return new TeamFutureReadiness(1, patrols, ready, spots, brands, 0, fuel, 0, 0);
    }

    private static ValidDaySimulationResult simulate(DayState state) {
        return (ValidDaySimulationResult) new DaySimulator().simulate(
                state, SafePlanFactory.waitAll(state));
    }

    private static ValidDaySimulationResult simulate(DayState state, TeamPlan plan) {
        return (ValidDaySimulationResult) new DaySimulator().simulate(state, plan);
    }

    private static DayState state(int day, int[] budgets, List<AgentState> agents) {
        List<UdonSpot> spots = List.of(
                new UdonSpot(new BrandId("a"), new Position(2), 1),
                new UdonSpot(new BrandId("b"), new Position(3), 1));
        Terrain[] terrain = new Terrain[4];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new HashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(4, 1, terrain), new DayStepBudgets(budgets), List.of(),
                new FuelCapacity(5), spots);
        return new DayState(match, new DayIndex(day), agents, Map.of(), stock);
    }
}