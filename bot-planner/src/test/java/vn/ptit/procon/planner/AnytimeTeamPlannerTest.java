package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.AgentStepUsage;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

class AnytimeTeamPlannerTest {

    private static final AgentId PATROL_0 = new AgentId(0);
    private static final AgentId PATROL_1 = new AgentId(1);
    private static final AgentId REFUEL = new AgentId(2);

    @Test
    void greedyTrapSearchBeatsTeamCoordinator() {
        DayState state = greedyTrapState("a", "b", "c");
        TeamPlan m7 = new TeamCoordinatorPlanner().plan(state);
        AnytimePlanResult anytime = planner(16).planWithStats(state);

        ValidDaySimulationResult m7Result = simulate(state, m7);
        ValidDaySimulationResult m8Result = simulate(state, anytime.plan());

        assertEquals(1, m7Result.brandsCollected().size());
        assertEquals(2, m8Result.brandsCollected().size());
        assertEquals(2, totalUdon(m8Result));
        assertTrue(Set.of(position(5), position(10))
                .contains(finalAgent(m8Result, PATROL_0).position()));
        assertTrue(anytime.stats().incumbentImprovements() > 0);
    }

    @Test
    void brandCountRemainsHigherPriorityThanUdonTotal() {
        PlanEvaluation fourBrands = new PlanEvaluation(4, 8, 1, 0, 10, "x");
        PlanEvaluation moreUdon = new PlanEvaluation(3, 12, 2, 10, 2, "y");

        assertTrue(fourBrands.betterThan(moreUdon));
    }

    @Test
    void evaluationUsesEveryDocumentedTieBreakInOrder() {
        PlanEvaluation baseline = new PlanEvaluation(4, 8, 1, 5, 8, "b");

        assertTrue(new PlanEvaluation(5, 1, 1, 0, 20, "z").betterThan(baseline));
        assertTrue(new PlanEvaluation(4, 9, 1, 0, 20, "z").betterThan(baseline));
        assertTrue(new PlanEvaluation(4, 8, 2, 0, 20, "z").betterThan(baseline));
        assertTrue(new PlanEvaluation(4, 8, 1, 6, 20, "z").betterThan(baseline));
        assertTrue(new PlanEvaluation(4, 8, 1, 5, 7, "z").betterThan(baseline));
        assertTrue(new PlanEvaluation(4, 8, 1, 5, 8, "a").betterThan(baseline));
    }

    @Test
    void udonTotalIsTheSecondaryObjective() {
        DayState state = greedyTrapState("same", "same", "same");
        AnytimePlanResult result = planner(16).planWithStats(state);

        assertEquals(1, result.evaluation().teamBrandCount());
        assertEquals(2, result.evaluation().udonTotal());
        assertEquals(1, simulate(state, new TeamCoordinatorPlanner().plan(state))
                .portionsCollectedByAgent().get(PATROL_0));
    }

    @Test
    void zeroBudgetReturnsTheExactValidatedM7Incumbent() {
        DayState state = greedyTrapState("a", "b", "c");
        TeamPlan m7 = new TeamCoordinatorPlanner().plan(state);
        AnytimePlanResult result = planner(0).planWithStats(state);

        assertEquals(m7.actionsByAgent(), result.plan().actionsByAgent());
        assertEquals(0, result.stats().expandedStates());
        assertTrue(result.stats().budgetExhausted());
        assertTrue(new PlanValidator().validate(state, result.plan()).valid());
    }

    @Test
    void boundedWorkFindsImprovementOnlyAfterEnoughExpansions() {
        DayState state = greedyTrapState("a", "b", "c");
        TeamPlan m7 = new TeamCoordinatorPlanner().plan(state);

        AnytimePlanResult small = planner(3).planWithStats(state);
        AnytimePlanResult larger = planner(4).planWithStats(state);

        assertEquals(m7.actionsByAgent(), small.plan().actionsByAgent());
        assertTrue(small.stats().budgetExhausted());
        assertTrue(larger.evaluation().betterThan(small.evaluation()));
        assertEquals(2, larger.evaluation().teamBrandCount());
    }

    @Test
    void repeatedSearchProducesIdenticalPlanAndStatistics() {
        DayState state = greedyTrapState("a", "b", "c");
        AnytimeTeamPlanner planner = planner(16);
        AnytimePlanResult expected = planner.planWithStats(state);

        for (int iteration = 0; iteration < 15; iteration++) {
            AnytimePlanResult actual = planner.planWithStats(state);
            assertEquals(expected.plan().actionsByAgent(), actual.plan().actionsByAgent());
            assertEquals(expected.evaluation(), actual.evaluation());
            assertEquals(expected.stats(), actual.stats());
        }
    }

    @Test
    void siblingBranchesKeepSharedStockIsolated() {
        DayState state = lineState(
                3,
                4,
                5,
                List.of(AgentState.patrol(PATROL_0, position(0), 5)),
                List.of(spot("a", 1), spot("b", 2)));

        AnytimePlanResult result = planner(8).planWithStats(state);
        ValidDaySimulationResult simulation = simulate(state, result.plan());

        assertEquals(2, totalUdon(simulation));
        assertEquals(0, simulation.remainingSpotStock().get(position(1)));
        assertEquals(0, simulation.remainingSpotStock().get(position(2)));
        assertTrue(result.stats().generatedStates() >= 3);
    }

    @Test
    void routePassThroughCollectionUpdatesSearchStateBeforeNextChild() {
        DayState state = lineState(
                4,
                6,
                6,
                List.of(AgentState.patrol(PATROL_0, position(0), 6)),
                List.of(spot("a", 1), spot("b", 2), spot("c", 3)));

        AnytimePlanResult result = planner(12).planWithStats(state);
        ValidDaySimulationResult simulation = simulate(state, result.plan());

        assertEquals(3, totalUdon(simulation));
        assertEquals(Set.of(new BrandId("a"), new BrandId("b"), new BrandId("c")),
                simulation.brandsCollected());
    }

    @Test
    void fuelInfeasibleBranchesAreNeverReturned() {
        DayState state = lineState(
                4,
                6,
                1,
                List.of(AgentState.patrol(PATROL_0, position(0), 1)),
                List.of(spot("near", 1), spot("far", 3)));

        AnytimePlanResult result = planner(16).planWithStats(state);
        ValidDaySimulationResult simulation = simulate(state, result.plan());

        assertEquals(1, totalUdon(simulation));
        assertEquals(position(1), finalAgent(simulation, PATROL_0).position());
        assertEquals(new FiniteFuel(0), finalAgent(simulation, PATROL_0).fuel());
        assertEquals(1, simulation.remainingSpotStock().get(position(3)));
    }

    @Test
    void stepBudgetIsNeverExceededAndEveryPlanIsExplicitlyCompleted() {
        DayState state = lineState(
                4,
                4,
                6,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 6),
                        AgentState.patrol(PATROL_1, position(3), 6)),
                List.of(spot("a", 1), spot("b", 2)));

        AnytimePlanResult result = planner(16).planWithStats(state);
        ValidDaySimulationResult simulation = simulate(state, result.plan());

        for (AgentState agent : state.agents()) {
            assertEquals(new AgentStepUsage(4), simulation.stepUsage().get(agent.id()));
        }
    }

    @Test
    void usefulRefuelRootCanRetainMoreUdonAtEqualOrBetterBrands() {
        DayState state = lineState(
                5,
                6,
                5,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 0),
                        AgentState.patrol(PATROL_1, position(4), 0),
                        AgentState.refuel(REFUEL, position(1))),
                List.of(spot("new", 2), spot("start", 4)));

        AnytimePlanResult result = planner(20).planWithStats(state);
        ValidDaySimulationResult simulation = simulate(state, result.plan());

        assertEquals(2, simulation.brandsCollected().size());
        assertEquals(2, totalUdon(simulation));
        assertTrue(simulation.events().stream().anyMatch(RefueledEvent.class::isInstance));
    }

    @Test
    void uselessRefuelDoesNotReplaceTheM7Incumbent() {
        DayState state = lineState(
                2,
                4,
                5,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 0),
                        AgentState.refuel(REFUEL, position(1))),
                List.of(spot("start", 0)));
        TeamPlan m7 = new TeamCoordinatorPlanner().plan(state);

        AnytimePlanResult result = planner(20).planWithStats(state);
        ValidDaySimulationResult simulation = simulate(state, result.plan());

        assertEquals(m7.actionsByAgent(), result.plan().actionsByAgent());
        assertTrue(simulation.events().stream().noneMatch(RefueledEvent.class::isInstance));
    }

    @Test
    void everyReturnedPlanValidatesAndSimulatesSuccessfully() {
        DayState state = greedyTrapState("a", "b", "c");

        AnytimePlanResult result = planner(16).planWithStats(state);

        assertTrue(new PlanValidator().validate(state, result.plan()).valid());
        assertInstanceOf(ValidDaySimulationResult.class,
                new DaySimulator().simulate(state, result.plan()));
        assertNotEquals("", result.evaluation().deterministicSignature());
    }

    @Test
    void invalidInjectedM7IncumbentFallsBackToValidatedWait() {
        DayState state = lineState(
                2,
                2,
                5,
                List.of(AgentState.patrol(PATROL_0, position(0), 5)),
                List.of(spot("target", 1)));
        TeamPlan invalid = new TeamPlan(Map.of(
                PATROL_0, List.of(new MoveAction(Direction.LEFT))));
        AnytimeTeamPlanner failClosed = new AnytimeTeamPlanner(
                new AnytimePlannerConfig(0, 4, 2),
                new WeightedRouteFinder(),
                new RefuelRouteFinder(),
                new PlanValidator(),
                new DaySimulator(),
                ignored -> invalid);

        AnytimePlanResult result = failClosed.planWithStats(state);

        assertEquals(List.of(new WaitAction(2)), result.plan().actionsFor(PATROL_0));
        assertTrue(new PlanValidator().validate(state, result.plan()).valid());
        assertInstanceOf(ValidDaySimulationResult.class,
                new DaySimulator().simulate(state, result.plan()));
    }

    @Test
    void routeFinderFailureReturnsTheValidatedIncumbent() {
        DayState state = greedyTrapState("a", "b", "c");
        TeamPlan m7 = new TeamCoordinatorPlanner().plan(state);
        WeightedRouteFinder failingFinder = new WeightedRouteFinder() {
            @Override
            public Optional<Route> find(DayState ignored, AgentState agent, Position target) {
                throw new IllegalStateException("injected route failure");
            }
        };
        AnytimeTeamPlanner failClosed = new AnytimeTeamPlanner(
                new AnytimePlannerConfig(8, 8, 3),
                failingFinder,
                new RefuelRouteFinder(),
                new PlanValidator(),
                new DaySimulator(),
                ignored -> m7);

        AnytimePlanResult result = failClosed.planWithStats(state);

        assertEquals(m7.actionsByAgent(), result.plan().actionsByAgent());
        assertTrue(new PlanValidator().validate(state, result.plan()).valid());
    }

    private static AnytimeTeamPlanner planner(int maxExpandedStates) {
        return new AnytimeTeamPlanner(new AnytimePlannerConfig(maxExpandedStates, 32, 4));
    }

    private static DayState greedyTrapState(String nearBrand, String clusterBrand, String endBrand) {
        Terrain[] terrain = new Terrain[25];
        Arrays.fill(terrain, Terrain.PLAIN);
        return state(
                terrain,
                5,
                5,
                6,
                8,
                List.of(AgentState.patrol(PATROL_0, position(1), 8)),
                List.of(
                        spot(nearBrand, 2),
                        spot(clusterBrand, 5),
                        spot(endBrand, 10)),
                Map.of());
    }

    private static ValidDaySimulationResult simulate(DayState state, TeamPlan plan) {
        assertTrue(new PlanValidator().validate(state, plan).valid());
        return assertInstanceOf(
                ValidDaySimulationResult.class,
                new DaySimulator().simulate(state, plan));
    }

    private static int totalUdon(ValidDaySimulationResult result) {
        return result.portionsCollectedByAgent().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    private static AgentState finalAgent(ValidDaySimulationResult result, AgentId id) {
        return result.finalAgents().stream()
                .filter(agent -> agent.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static DayState lineState(
            int width,
            int budget,
            int capacity,
            List<AgentState> agents,
            List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        return state(terrain, width, 1, budget, capacity, agents, spots, Map.of());
    }

    private static DayState state(
            Terrain[] terrain,
            int width,
            int height,
            int budget,
            int capacity,
            List<AgentState> agents,
            List<UdonSpot> spots,
            Map<Position, TrafficStatus> traffic) {
        Map<Position, Integer> stock = new HashMap<>();
        for (UdonSpot spot : spots) {
            stock.put(spot.position(), spot.stockCapacity());
        }
        StaticMatchData matchData = new StaticMatchData(
                new HexMap(width, height, terrain),
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new FuelCapacity(capacity),
                spots);
        return new DayState(
                matchData,
                new DayIndex(0),
                new ArrayList<>(agents),
                traffic,
                stock);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), position(position), 1);
    }

    private static Position position(int value) {
        return new Position(value);
    }
}