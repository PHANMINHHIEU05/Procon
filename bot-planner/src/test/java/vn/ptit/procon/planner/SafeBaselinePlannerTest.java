package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
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
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;

class SafeBaselinePlannerTest {

    private final WeightedRouteFinder routeFinder = new WeightedRouteFinder();
    private final SafeBaselinePlanner planner = new SafeBaselinePlanner(routeFinder, new PlanValidator());

    @Test
    void weightedSearchChoosesLowerStepCostEvenWhenItUsesMoreHexEdges() {
        DayState state = state(
                new Terrain[] {
                    Terrain.ROAD, Terrain.ROAD, Terrain.PLAIN, Terrain.PLAIN,
                    Terrain.ROAD, Terrain.MOUNTAIN, Terrain.PLAIN, Terrain.PLAIN
                },
                4,
                2,
                AgentState.patrol(new AgentId(0), new Position(4), 20),
                List.of(new UdonSpot(new BrandId("weighted"), new Position(6), 1)),
                traffic(0, 1, 4));

        Route route = routeFinder.find(state, state.agents().get(0), new Position(6)).orElseThrow();

        assertEquals(List.of(Direction.UP_RIGHT, Direction.RIGHT, Direction.DOWN_RIGHT), route.directions());
        assertEquals(3, route.stepsUsed());
        assertEquals(6, route.fuelUsed());
        assertValid(state, planner.plan(state));
    }

    @Test
    void sourceCellTerrainDeterminesCost() {
        DayState state = state(
                new Terrain[] {Terrain.MOUNTAIN, Terrain.PLAIN},
                2,
                1,
                AgentState.patrol(new AgentId(0), new Position(0), 5),
                List.of(new UdonSpot(new BrandId("source"), new Position(1), 1)),
                Map.of());

        Route route = routeFinder.find(state, state.agents().get(0), new Position(1)).orElseThrow();

        assertEquals(3, route.stepsUsed());
        assertEquals(2, route.fuelUsed());
        assertValid(state, planner.plan(state));
    }

    @Test
    void fuelConstraintRejectsRoadShortcutAndUsesPlainDetour() {
        DayState state = state(
                new Terrain[] {
                    Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN,
                    Terrain.ROAD, Terrain.ROAD, Terrain.ROAD, Terrain.ROAD, Terrain.PLAIN
                },
                5,
                2,
                AgentState.patrol(new AgentId(0), new Position(5), 6),
                List.of(new UdonSpot(new BrandId("fuel"), new Position(9), 1)),
                traffic(5, 6, 7, 8));

        Route route = routeFinder.find(state, state.agents().get(0), new Position(9)).orElseThrow();

        assertEquals(9, route.stepsUsed());
        assertEquals(6, route.fuelUsed());
        assertTrue(route.directions().size() > 4);
        assertValid(state, planner.plan(state));
    }

    @Test
    void routeSearchRetainsDifferentFuelStatesAtTheSamePosition() {
        DayState state = state(
                new Terrain[] {
                    Terrain.PLAIN, Terrain.POND, Terrain.ROAD, Terrain.PLAIN,
                    Terrain.ROAD, Terrain.ROAD, Terrain.PLAIN, Terrain.PLAIN,
                    Terrain.PLAIN, Terrain.POND, Terrain.PLAIN, Terrain.ROAD,
                    Terrain.ROAD, Terrain.POND, Terrain.ROAD, Terrain.PLAIN
                },
                4,
                4,
                AgentState.patrol(new AgentId(0), new Position(3), 5),
                List.of(new UdonSpot(new BrandId("states"), new Position(8), 1)),
                traffic(2, 4, 5, 11, 12, 14));

        Route route = routeFinder.find(state, state.agents().get(0), new Position(8)).orElseThrow();

        assertEquals(7, route.stepsUsed());
        assertEquals(5, route.fuelUsed());
        assertEquals(List.of(Direction.DOWN_LEFT, Direction.LEFT, Direction.LEFT, Direction.DOWN_LEFT),
                route.directions());
        assertValid(state, planner.plan(state));
    }

    @Test
    void dayBudgetAllowsExactCostAndRejectsOneStepBeyond() {
        DayState exact = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN}, 2, 1,
                AgentState.patrol(new AgentId(0), new Position(0), 2),
                List.of(new UdonSpot(new BrandId("budget"), new Position(1), 1)), Map.of(), 2);
        DayState shortBudget = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN}, 2, 1,
                AgentState.patrol(new AgentId(0), new Position(0), 2),
                List.of(new UdonSpot(new BrandId("budget"), new Position(1), 1)), Map.of(), 1);

        assertTrue(routeFinder.find(exact, exact.agents().get(0), new Position(1)).isPresent());
        assertTrue(routeFinder.find(shortBudget, shortBudget.agents().get(0), new Position(1)).isEmpty());
        TeamPlan exactPlan = planner.plan(exact);
        TeamPlan shortPlan = planner.plan(shortBudget);
        assertInstanceOf(MoveAction.class, exactPlan.actionsFor(new AgentId(0)).get(0));
        assertInstanceOf(WaitAction.class, shortPlan.actionsFor(new AgentId(0)).get(0));
        assertValid(exact, exactPlan);
        assertValid(shortBudget, shortPlan);
    }

    @Test
    void roadTrafficChangesRouteCostAndMissingTrafficIsRejected() {
        for (TrafficStatus status : TrafficStatus.values()) {
            DayState state = state(
                    new Terrain[] {Terrain.ROAD, Terrain.PLAIN}, 2, 1,
                    AgentState.patrol(new AgentId(0), new Position(0), 5),
                    List.of(new UdonSpot(new BrandId("road"), new Position(1), 1)),
                    Map.of(new Position(0), status));
            Route route = routeFinder.find(state, state.agents().get(0), new Position(1)).orElseThrow();
            assertEquals(status == TrafficStatus.CLEAR ? 1 : status == TrafficStatus.CONGESTED ? 2 : 4,
                    route.stepsUsed());
        }
        DayState missing = state(
                new Terrain[] {Terrain.ROAD, Terrain.PLAIN}, 2, 1,
                AgentState.patrol(new AgentId(0), new Position(0), 5),
                List.of(new UdonSpot(new BrandId("road"), new Position(1), 1)), Map.of());
        assertTrue(routeFinder.find(missing, missing.agents().get(0), new Position(1)).isEmpty());
    }

    @Test
    void pondIsNeverEntered() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.POND, Terrain.PLAIN}, 3, 1,
                AgentState.patrol(new AgentId(0), new Position(0), 10),
                List.of(new UdonSpot(new BrandId("pond"), new Position(2), 1)), Map.of());

        assertTrue(routeFinder.find(state, state.agents().get(0), new Position(2)).isEmpty());
        TeamPlan plan = planner.plan(state);
        assertInstanceOf(WaitAction.class, plan.actionsFor(new AgentId(0)).get(0));
        assertValid(state, plan);
    }

    @Test
    void plannerIsDeterministicAndSelectsNearestStockedSpot() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN}, 4, 1,
                AgentState.patrol(new AgentId(0), new Position(0), 10),
                List.of(
                        new UdonSpot(new BrandId("far"), new Position(3), 1),
                        new UdonSpot(new BrandId("near"), new Position(1), 1)), Map.of());

        TeamPlan first = planner.plan(state);
        TeamPlan second = planner.plan(state);

        assertEquals(first.actionsByAgent(), second.actionsByAgent());
        assertEquals(List.of(Direction.RIGHT), moveDirections(first.actionsFor(new AgentId(0))));
        assertValid(state, first);

        DayState tied = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN}, 3, 1,
                AgentState.patrol(new AgentId(0), new Position(1), 10),
                List.of(
                        new UdonSpot(new BrandId("larger"), new Position(2), 1),
                        new UdonSpot(new BrandId("smaller"), new Position(0), 1)), Map.of());
        TeamPlan tiedPlan = planner.plan(tied);
        assertEquals(List.of(Direction.LEFT), moveDirections(tiedPlan.actionsFor(new AgentId(0))));
        assertValid(tied, tiedPlan);
    }

    @Test
    void zeroStockIsIgnoredAndStockIsReservedInAscendingAgentOrder() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN}, 3, 1,
                AgentState.patrol(new AgentId(1), new Position(2), 10),
                List.of(
                        new UdonSpot(new BrandId("empty"), new Position(0), 1),
                        new UdonSpot(new BrandId("only"), new Position(1), 1)), Map.of(),
                3,
                AgentState.patrol(new AgentId(0), new Position(0), 10));
        state = new DayState(state.matchData(), state.day(), List.of(
                AgentState.patrol(new AgentId(1), new Position(2), 10),
                AgentState.patrol(new AgentId(0), new Position(0), 10)), Map.of(),
                Map.of(new Position(0), 0, new Position(1), 1));

        TeamPlan plan = planner.plan(state);

        assertEquals(List.of(Direction.RIGHT), moveDirections(plan.actionsFor(new AgentId(0))));
        assertInstanceOf(WaitAction.class, plan.actionsFor(new AgentId(1)).get(0));
        assertValid(state, plan);
    }

    @Test
    void refuelAndZeroFuelPatrolWait() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN}, 2, 1,
                AgentState.refuel(new AgentId(0), new Position(0)),
                List.of(new UdonSpot(new BrandId("wait"), new Position(1), 1)), Map.of(),
                3,
                AgentState.patrol(new AgentId(1), new Position(0), 0));

        TeamPlan plan = planner.plan(state);

        assertEquals(List.of(new WaitAction(3)), plan.actionsFor(new AgentId(0)));
        assertEquals(List.of(new WaitAction(3)), plan.actionsFor(new AgentId(1)));
        assertValid(state, plan);
    }

    @Test
    void generatedPlanPassesValidationAndSimulatorCollectsUdon() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN}, 2, 1,
                AgentState.patrol(new AgentId(0), new Position(0), 5),
                List.of(new UdonSpot(new BrandId("collect"), new Position(1), 1)), Map.of(), 2);
        TeamPlan plan = planner.plan(state);

        ValidDaySimulationResult result = assertInstanceOf(
                ValidDaySimulationResult.class, new DaySimulator().simulate(state, plan));

        assertEquals(0, result.remainingSpotStock().get(new Position(1)));
        assertEquals(1, result.portionsCollectedByAgent().get(new AgentId(0)));
        assertTrue(result.events().stream().anyMatch(event -> event instanceof UdonCollectedEvent collected
                && collected.position().equals(new Position(1))));
        assertValid(state, plan);
    }

    @Test
    void invalidBaselineFallsBackToValidatedWaitPlan() {
        WeightedRouteFinder invalidFinder = new WeightedRouteFinder() {
            @Override
            public Optional<Route> find(DayState state, AgentState agent, Position goal) {
                return Optional.of(new Route(agent.position(), goal, List.of(Direction.LEFT), 1, 0));
            }
        };
        SafeBaselinePlanner invalidPlanner = new SafeBaselinePlanner(invalidFinder, new PlanValidator());
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN}, 2, 1,
                AgentState.patrol(new AgentId(0), new Position(0), 5),
                List.of(new UdonSpot(new BrandId("fallback"), new Position(1), 1)), Map.of(), 2);

        TeamPlan plan = invalidPlanner.plan(state);

        assertEquals(List.of(new WaitAction(2)), plan.actionsFor(new AgentId(0)));
        assertValid(state, plan);
    }

    private static void assertValid(DayState state, TeamPlan plan) {
        assertTrue(new PlanValidator().validate(state, plan).valid());
    }

    private static List<Direction> moveDirections(List<AgentAction> actions) {
        return actions.stream().map(action -> ((MoveAction) action).direction()).toList();
    }

    private static Map<Position, TrafficStatus> traffic(int... positions) {
        Map<Position, TrafficStatus> result = new HashMap<>();
        for (int position : positions) {
            result.put(new Position(position), TrafficStatus.CLEAR);
        }
        return result;
    }

    private static DayState state(
            Terrain[] terrains,
            int width,
            int height,
            AgentState agent,
            List<UdonSpot> spots,
            Map<Position, TrafficStatus> traffic) {
        return state(terrains, width, height, agent, spots, traffic, 20);
    }

    private static DayState state(
            Terrain[] terrains,
            int width,
            int height,
            AgentState agent,
            List<UdonSpot> spots,
            Map<Position, TrafficStatus> traffic,
            int budget,
            AgentState... additionalAgents) {
        List<AgentState> agents = new java.util.ArrayList<>();
        agents.add(agent);
        agents.addAll(Arrays.asList(additionalAgents));
        Map<Position, Integer> stock = new HashMap<>();
        for (UdonSpot spot : spots) {
            stock.put(spot.position(), spot.stockCapacity());
        }
        StaticMatchData matchData = new StaticMatchData(
                new HexMap(width, height, terrains),
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new vn.ptit.procon.domain.agent.FuelCapacity(20),
                spots);
        return new DayState(matchData, new DayIndex(0), agents, traffic, stock);
    }
}