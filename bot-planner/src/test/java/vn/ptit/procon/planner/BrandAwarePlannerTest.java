package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.AgentStepUsage;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

class BrandAwarePlannerTest {

    private final BrandAwarePlanner planner = new BrandAwarePlanner();

    @Test
    void patrolVisitsMultipleSpotsAndExplicitlyFillsTheDay() {
        DayState state = state(
                4,
                1,
                7,
                AgentState.patrol(new AgentId(0), new Position(0), 10),
                List.of(
                        spot("a", 1, 1),
                        spot("b", 2, 1),
                        spot("c", 3, 1)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(
                new MoveAction(Direction.RIGHT),
                new MoveAction(Direction.RIGHT),
                new MoveAction(Direction.RIGHT),
                new WaitAction(1)), plan.actionsFor(new AgentId(0)));
        assertEquals(3, result.portionsCollectedByAgent().get(new AgentId(0)));
        assertEquals(3, result.brandsCollected().size());
        assertEquals(new AgentStepUsage(7), result.stepUsage().get(new AgentId(0)));
    }

    @Test
    void patrolBrandDiversityBeatsPositionAfterTheFirstTarget() {
        DayState state = state(
                4,
                2,
                6,
                AgentState.patrol(new AgentId(0), new Position(0), 5),
                List.of(
                        spot("a", 1, 1),
                        spot("a", 5, 1),
                        spot("b", 7, 1)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(3, moveDirections(plan.actionsFor(new AgentId(0))).size());
        assertEquals(Direction.RIGHT, moveDirections(plan.actionsFor(new AgentId(0))).getFirst());
        assertEquals(new Position(7), result.finalAgents().getFirst().position());
        assertEquals(2, result.brandsCollected().size());
        assertEquals(1, result.remainingSpotStock().get(new Position(5)));
    }

    @Test
    void patrolsShareProjectedStockAndPreferTeamBrandCoverage() {
        DayState state = state(
                3,
                2,
                2,
                AgentState.patrol(new AgentId(1), new Position(0), 5),
                List.of(
                        spot("a", 1, 2),
                        spot("b", 4, 1)),
                AgentState.patrol(new AgentId(0), new Position(0), 5));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(Direction.RIGHT),
                moveDirections(plan.actionsFor(new AgentId(0))));
        assertEquals(List.of(Direction.DOWN_RIGHT),
                moveDirections(plan.actionsFor(new AgentId(1))));
        assertEquals(1, result.remainingSpotStock().get(new Position(1)));
        assertEquals(0, result.remainingSpotStock().get(new Position(4)));
        assertEquals(2, result.brandsCollected().size());
    }

    @Test
    void patrolDoesNotSelectTheSameSpotTwiceEvenWhenStockRemains() {
        DayState state = state(
                2,
                1,
                10,
                AgentState.patrol(new AgentId(0), new Position(0), 10),
                List.of(spot("only", 1, 3)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new MoveAction(Direction.RIGHT), new WaitAction(8)),
                plan.actionsFor(new AgentId(0)));
        assertEquals(1, result.portionsCollectedByAgent().get(new AgentId(0)));
        assertEquals(2, result.remainingSpotStock().get(new Position(1)));
    }

    @Test
    void stockOneReservedByLowerAgentIdIsUnavailableToNextPatrol() {
        DayState state = state(
                2,
                1,
                2,
                AgentState.patrol(new AgentId(1), new Position(0), 5),
                List.of(spot("single", 1, 1)),
                AgentState.patrol(new AgentId(0), new Position(0), 5));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new MoveAction(Direction.RIGHT)),
                plan.actionsFor(new AgentId(0)));
        assertEquals(List.of(new WaitAction(2)), plan.actionsFor(new AgentId(1)));
        assertEquals(1, result.portionsCollectedByAgent().get(new AgentId(0)));
        assertEquals(0, result.portionsCollectedByAgent().get(new AgentId(1)));
        assertEquals(0, result.remainingSpotStock().get(new Position(1)));
    }

    @Test
    void secondTargetIsSkippedWhenItsWholeRouteDoesNotFitRemainingSteps() {
        DayState state = state(
                3,
                1,
                3,
                AgentState.patrol(new AgentId(0), new Position(0), 5),
                List.of(spot("a", 1, 1), spot("b", 2, 1)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new MoveAction(Direction.RIGHT), new WaitAction(1)),
                plan.actionsFor(new AgentId(0)));
        assertEquals(1, result.portionsCollectedByAgent().get(new AgentId(0)));
        assertEquals(1, result.remainingSpotStock().get(new Position(2)));
        assertEquals(new AgentStepUsage(3), result.stepUsage().get(new AgentId(0)));
    }

    @Test
    void secondTargetIsSkippedWhenRemainingFuelCannotPayForItsRoute() {
        DayState state = state(
                3,
                1,
                10,
                AgentState.patrol(new AgentId(0), new Position(0), 1),
                List.of(spot("a", 1, 1), spot("b", 2, 1)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new MoveAction(Direction.RIGHT), new WaitAction(8)),
                plan.actionsFor(new AgentId(0)));
        assertEquals(1, result.portionsCollectedByAgent().get(new AgentId(0)));
        assertEquals(1, result.remainingSpotStock().get(new Position(2)));
        assertEquals(0, ((FiniteFuel) result.finalAgents().getFirst().fuel()).amount());
    }

    @Test
    void everyAgentPlanExplicitlyConsumesTheCompleteDayBudget() {
        DayState state = state(
                4,
                1,
                7,
                AgentState.patrol(new AgentId(2), new Position(3), 5),
                List.of(spot("a", 1, 1), spot("b", 2, 1)),
                AgentState.refuel(new AgentId(1), new Position(0)),
                AgentState.patrol(new AgentId(0), new Position(0), 5));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        for (AgentState agent : state.agents()) {
            assertEquals(new AgentStepUsage(7), result.stepUsage().get(agent.id()));
        }
        assertEquals(List.of(new WaitAction(7)), plan.actionsFor(new AgentId(1)));
    }

    @Test
    void repeatedPlanningFromSameStateIsDeterministic() {
        DayState state = state(
                4,
                1,
                7,
                AgentState.patrol(new AgentId(0), new Position(0), 10),
                List.of(spot("a", 1, 1), spot("b", 2, 1), spot("c", 3, 1)));

        TeamPlan first = planner.plan(state);
        TeamPlan second = planner.plan(state);

        assertEquals(first.actionsByAgent(), second.actionsByAgent());
        simulate(state, first);
        simulate(state, second);
    }

    @Test
    void refuelWaitsAndZeroFuelPatrolCanOnlyCollectAtItsStartingSpot() {
        DayState state = state(
                2,
                1,
                6,
                AgentState.refuel(new AgentId(1), new Position(1)),
                List.of(spot("start", 0, 1), spot("away", 1, 1)),
                AgentState.patrol(new AgentId(0), new Position(0), 0));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new WaitAction(6)), plan.actionsFor(new AgentId(0)));
        assertEquals(List.of(new WaitAction(6)), plan.actionsFor(new AgentId(1)));
        assertEquals(1, result.portionsCollectedByAgent().get(new AgentId(0)));
        assertEquals(1, result.remainingSpotStock().get(new Position(1)));
    }

    @Test
    void invalidGeneratedPlanFallsBackToValidatedAllWait() {
        WeightedRouteFinder inaccurateFinder = new WeightedRouteFinder() {
            @Override
            public Optional<Route> find(DayState state, AgentState agent, Position goal) {
                return Optional.of(new Route(
                        agent.position(), goal, List.of(Direction.RIGHT), 1, 0));
            }
        };
        BrandAwarePlanner invalidPlanner = new BrandAwarePlanner(inaccurateFinder, new PlanValidator());
        DayState state = state(
                2,
                1,
                2,
                AgentState.patrol(new AgentId(0), new Position(0), 5),
                List.of(spot("fallback", 1, 1)));

        TeamPlan plan = invalidPlanner.plan(state);

        assertEquals(List.of(new WaitAction(2)), plan.actionsFor(new AgentId(0)));
        assertTrue(new PlanValidator().validate(state, plan).valid());
    }

    private static ValidDaySimulationResult simulate(DayState state, TeamPlan plan) {
        assertTrue(new PlanValidator().validate(state, plan).valid());
        return assertInstanceOf(
                ValidDaySimulationResult.class, new DaySimulator().simulate(state, plan));
    }

    private static List<Direction> moveDirections(List<AgentAction> actions) {
        return actions.stream()
                .filter(MoveAction.class::isInstance)
                .map(MoveAction.class::cast)
                .map(MoveAction::direction)
                .toList();
    }

    private static UdonSpot spot(String brand, int position, int capacity) {
        return new UdonSpot(new BrandId(brand), new Position(position), capacity);
    }

    private static DayState state(
            int width,
            int height,
            int budget,
            AgentState firstAgent,
            List<UdonSpot> spots,
            AgentState... additionalAgents) {
        Terrain[] terrain = new Terrain[Math.multiplyExact(width, height)];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<AgentState> agents = new ArrayList<>();
        agents.add(firstAgent);
        agents.addAll(Arrays.asList(additionalAgents));
        Map<Position, Integer> stock = new HashMap<>();
        for (UdonSpot spot : spots) {
            stock.put(spot.position(), spot.stockCapacity());
        }
        StaticMatchData matchData = new StaticMatchData(
                new HexMap(width, height, terrain),
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new FuelCapacity(20),
                spots);
        return new DayState(matchData, new DayIndex(0), agents, Map.of(), stock);
    }
}
