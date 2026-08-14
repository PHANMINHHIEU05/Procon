package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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

class TeamCoordinatorPlannerTest {

    private static final AgentId PATROL_0 = new AgentId(0);
    private static final AgentId PATROL_1 = new AgentId(1);
    private static final AgentId PATROL_2 = new AgentId(2);
    private static final AgentId REFUEL = new AgentId(3);
    private final TeamCoordinatorPlanner planner = new TeamCoordinatorPlanner();

    @Test
    void globalSelectionRemovesSequentialAgentOrderBias() {
        DayState state = state(
                new Terrain[] {Terrain.ROAD, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN},
                4,
                1,
                2,
                5,
                List.of(
                        AgentState.patrol(PATROL_0, position(2), 5),
                        AgentState.patrol(PATROL_1, position(0), 5)),
                List.of(spot("a", 1), spot("b", 3)),
                Map.of(position(0), TrafficStatus.CLEAR));

        ValidDaySimulationResult sequential = simulate(state, new BrandAwarePlanner().plan(state));
        TeamPlan coordinatedPlan = planner.plan(state);
        ValidDaySimulationResult coordinated = simulate(state, coordinatedPlan);

        assertEquals(1, sequential.brandsCollected().size());
        assertEquals(Set.of(new BrandId("a"), new BrandId("b")), coordinated.brandsCollected());
        assertEquals(List.of(new MoveAction(Direction.RIGHT)), coordinatedPlan.actionsFor(PATROL_0));
        assertEquals(List.of(new MoveAction(Direction.RIGHT), new WaitAction(1)),
                coordinatedPlan.actionsFor(PATROL_1));
        assertEquals(position(3), finalAgent(coordinated, PATROL_0).position());
        assertEquals(position(1), finalAgent(coordinated, PATROL_1).position());
        assertEquals(new FiniteFuel(4), finalAgent(coordinated, PATROL_0).fuel());
        assertEquals(new FiniteFuel(3), finalAgent(coordinated, PATROL_1).fuel());
    }

    @Test
    void uncoveredTeamBrandBeatsNearerAlreadyCoveredBrand() {
        DayState state = lineState(
                4,
                4,
                6,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 6),
                        AgentState.patrol(PATROL_1, position(1), 6)),
                List.of(spot("a", 0), spot("a", 2), spot("b", 3)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new WaitAction(4)), plan.actionsFor(PATROL_0));
        assertEquals(List.of(
                new MoveAction(Direction.RIGHT),
                new MoveAction(Direction.RIGHT)), plan.actionsFor(PATROL_1));
        assertEquals(Set.of(new BrandId("a"), new BrandId("b")), result.brandsCollected());
    }

    @Test
    void sharedStockPreventsDuplicateProjectedRoute() {
        DayState state = lineState(
                2,
                2,
                5,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 5),
                        AgentState.patrol(PATROL_1, position(0), 5)),
                List.of(spot("single", 1)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new MoveAction(Direction.RIGHT)), plan.actionsFor(PATROL_0));
        assertEquals(List.of(new WaitAction(2)), plan.actionsFor(PATROL_1));
        assertEquals(1, result.portionsCollectedByAgent().get(PATROL_0));
        assertEquals(0, result.portionsCollectedByAgent().get(PATROL_1));
        assertEquals(0, result.remainingSpotStock().get(position(1)));
    }

    @Test
    void routePassThroughCollectionImmediatelyUpdatesSharedStock() {
        DayState state = lineState(
                3,
                4,
                5,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 5),
                        AgentState.patrol(PATROL_1, position(0), 5)),
                List.of(spot("a", 1), spot("b", 2)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(
                new MoveAction(Direction.RIGHT),
                new MoveAction(Direction.RIGHT)), plan.actionsFor(PATROL_0));
        assertEquals(List.of(new WaitAction(4)), plan.actionsFor(PATROL_1));
        assertEquals(2, result.portionsCollectedByAgent().get(PATROL_0));
        assertEquals(0, result.remainingSpotStock().get(position(1)));
        assertEquals(0, result.remainingSpotStock().get(position(2)));
    }

    @Test
    void globalLoopDeterministicallyInterleavesThreePatrols() {
        Terrain[] terrain = new Terrain[25];
        Arrays.fill(terrain, Terrain.PLAIN);
        for (int position : List.of(5, 6, 7, 8, 9, 15, 16, 17, 18, 19)) {
            terrain[position] = Terrain.POND;
        }
        DayState state = state(
                terrain,
                5,
                5,
                8,
                10,
                List.of(
                        AgentState.patrol(PATROL_0, position(2), 10),
                        AgentState.patrol(PATROL_1, position(10), 10),
                        AgentState.patrol(PATROL_2, position(20), 10)),
                List.of(
                        spot("a", 1),
                        spot("b", 4),
                        spot("c", 12),
                        spot("d", 24)),
                Map.of());

        CapturedPlan captured = capturePlan(planner, state);
        ValidDaySimulationResult result = simulate(state, captured.plan());

        List<String> selections = captured.output().lines()
                .filter(line -> line.startsWith("TEAM_TARGET_SELECT"))
                .toList();
        assertEquals(4, selections.size());
        assertTrue(selections.get(0).contains("iteration=1 agent=0 target=1"));
        assertTrue(selections.get(1).contains("iteration=2 agent=1 target=12"));
        assertTrue(selections.get(2).contains("iteration=3 agent=0 target=4"));
        assertTrue(selections.get(3).contains("iteration=4 agent=2 target=24"));
        assertEquals(4, result.brandsCollected().size());
    }

    @Test
    void candidateThatDoesNotFitRemainingStepsIsSkipped() {
        DayState state = lineState(
                3,
                2,
                5,
                List.of(AgentState.patrol(PATROL_0, position(0), 5)),
                List.of(spot("covered", 0), spot("covered", 1), spot("unreachable", 2)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new MoveAction(Direction.RIGHT)), plan.actionsFor(PATROL_0));
        assertEquals(1, result.remainingSpotStock().get(position(2)));
        assertEquals(Set.of(new BrandId("covered")), result.brandsCollected());
    }

    @Test
    void fuelInfeasibleCandidateIsSkippedForFeasibleLowerValueCandidate() {
        DayState state = lineState(
                3,
                4,
                5,
                List.of(AgentState.patrol(PATROL_0, position(0), 1)),
                List.of(spot("covered", 0), spot("covered", 1), spot("unreachable", 2)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new MoveAction(Direction.RIGHT), new WaitAction(2)),
                plan.actionsFor(PATROL_0));
        assertEquals(new FiniteFuel(0), finalAgent(result, PATROL_0).fuel());
        assertEquals(1, result.remainingSpotStock().get(position(2)));
    }

    @Test
    void usefulRefuelIsSelectedFromPositiveTeamBrandBenefit() {
        DayState state = lineState(
                5,
                6,
                5,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 0),
                        AgentState.patrol(PATROL_1, position(3), 0),
                        AgentState.patrol(PATROL_2, position(4), 0),
                        AgentState.refuel(REFUEL, position(1))),
                List.of(spot("c", 2), spot("a", 3), spot("b", 4)));

        ValidDaySimulationResult withoutRefill = simulate(
                state, new BrandAwarePlanner().plan(state));
        CapturedPlan captured = capturePlan(planner, state);
        ValidDaySimulationResult withRefill = simulate(state, captured.plan());

        assertEquals(Set.of(new BrandId("a"), new BrandId("b")), withoutRefill.brandsCollected());
        assertEquals(Set.of(new BrandId("a"), new BrandId("b"), new BrandId("c")),
                withRefill.brandsCollected());
        assertTrue(captured.output().contains(
                "TEAM_REFUEL_ASSIGN day=0 refuelAgent=3 patrolAgent=0"
                        + " projectedBrandGain=1 projectedCollectionGain=1 arrivalStep=2"));
        assertEquals(List.of(
                new WaitAction(2),
                new MoveAction(Direction.RIGHT),
                new MoveAction(Direction.RIGHT)), captured.plan().actionsFor(PATROL_0));
        assertEquals(List.of(new MoveAction(Direction.LEFT), new WaitAction(4)),
                captured.plan().actionsFor(REFUEL));
        assertTrue(withRefill.events().stream().anyMatch(event -> event instanceof RefueledEvent refueled
                && refueled.patrolId().equals(PATROL_0)));
        assertEquals(new FiniteFuel(3), finalAgent(withRefill, PATROL_0).fuel());
    }

    @Test
    void lowFuelWithoutAdditionalTeamValueLeavesRefuelUnassigned() {
        DayState state = lineState(
                2,
                6,
                5,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 0),
                        AgentState.refuel(REFUEL, position(1))),
                List.of(spot("start", 0)));

        CapturedPlan captured = capturePlan(planner, state);
        ValidDaySimulationResult result = simulate(state, captured.plan());

        assertEquals(List.of(new WaitAction(6)), captured.plan().actionsFor(PATROL_0));
        assertEquals(List.of(new WaitAction(6)), captured.plan().actionsFor(REFUEL));
        assertTrue(captured.output().contains(
                "TEAM_REFUEL_NO_ASSIGN day=0 reason=NO_POSITIVE_TEAM_VALUE"));
        assertTrue(result.events().stream().noneMatch(RefueledEvent.class::isInstance));
    }

    @Test
    void incidentalRefillRemainsAuthoritativeSimulatorBehavior() {
        DayState state = lineState(
                2,
                2,
                5,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 1),
                        AgentState.refuel(REFUEL, position(1))),
                List.of(spot("arrival", 1)));

        CapturedPlan captured = capturePlan(planner, state);
        ValidDaySimulationResult result = simulate(state, captured.plan());

        assertTrue(captured.output().contains("TEAM_REFUEL_NO_ASSIGN"));
        assertEquals(List.of(new MoveAction(Direction.RIGHT)), captured.plan().actionsFor(PATROL_0));
        assertEquals(List.of(new WaitAction(2)), captured.plan().actionsFor(REFUEL));
        assertTrue(result.events().stream().anyMatch(event -> event instanceof RefueledEvent refueled
                && refueled.patrolId().equals(PATROL_0)
                && refueled.position().equals(position(1))));
        assertEquals(new FiniteFuel(5), finalAgent(result, PATROL_0).fuel());
    }

    @Test
    void fullPlanSimulationUsesEveryStepAndProducesExpectedTeamState() {
        DayState state = lineState(
                5,
                6,
                5,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 0),
                        AgentState.patrol(PATROL_1, position(3), 0),
                        AgentState.patrol(PATROL_2, position(4), 0),
                        AgentState.refuel(REFUEL, position(1))),
                List.of(spot("c", 2), spot("a", 3), spot("b", 4)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        for (AgentState agent : state.agents()) {
            assertEquals(new AgentStepUsage(6), result.stepUsage().get(agent.id()));
        }
        assertEquals(3, result.brandsCollected().size());
        assertEquals(3, result.portionsCollectedByAgent().values().stream()
                .mapToInt(Integer::intValue).sum());
        assertEquals(position(2), finalAgent(result, PATROL_0).position());
        assertEquals(position(3), finalAgent(result, PATROL_1).position());
        assertEquals(position(4), finalAgent(result, PATROL_2).position());
        assertEquals(position(0), finalAgent(result, REFUEL).position());
        assertEquals(new FiniteFuel(3), finalAgent(result, PATROL_0).fuel());
    }

    @Test
    void repeatedPlanningIsByteForDomainDeterministic() {
        DayState state = lineState(
                5,
                6,
                5,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 0),
                        AgentState.patrol(PATROL_1, position(3), 0),
                        AgentState.patrol(PATROL_2, position(4), 0),
                        AgentState.refuel(REFUEL, position(1))),
                List.of(spot("c", 2), spot("a", 3), spot("b", 4)));

        Map<AgentId, List<AgentAction>> expected = planner.plan(state).actionsByAgent();

        for (int iteration = 0; iteration < 20; iteration++) {
            TeamPlan actual = planner.plan(state);
            assertEquals(expected, actual.actionsByAgent());
            assertTrue(new PlanValidator().validate(state, actual).valid());
        }
    }

    @Test
    void invalidCoordinatedCandidateFallsBackToIndependentlyValidatedPlan() {
        WeightedRouteFinder invalidFinder = new WeightedRouteFinder() {
            @Override
            public Optional<Route> find(DayState state, AgentState agent, Position goal) {
                return Optional.of(new Route(
                        agent.position(), goal, List.of(Direction.LEFT), 2, 1));
            }
        };
        TeamCoordinatorPlanner failClosed = new TeamCoordinatorPlanner(
                invalidFinder, new RefuelRouteFinder(), new PlanValidator());
        DayState state = lineState(
                2,
                2,
                5,
                List.of(AgentState.patrol(PATROL_0, position(0), 5)),
                List.of(spot("fallback", 1)));

        CapturedPlan captured = capturePlan(failClosed, state);

        assertEquals(List.of(new WaitAction(2)), captured.plan().actionsFor(PATROL_0));
        assertTrue(new PlanValidator().validate(state, captured.plan()).valid());
        assertTrue(captured.output().contains("TEAM_PLAN_FALLBACK"));
    }

    private static ValidDaySimulationResult simulate(DayState state, TeamPlan plan) {
        assertTrue(new PlanValidator().validate(state, plan).valid());
        return assertInstanceOf(
                ValidDaySimulationResult.class, new DaySimulator().simulate(state, plan));
    }

    private static AgentState finalAgent(ValidDaySimulationResult result, AgentId id) {
        return result.finalAgents().stream()
                .filter(agent -> agent.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static CapturedPlan capturePlan(DayPlanner planner, DayState state) {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            return new CapturedPlan(planner.plan(state), output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
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

    private record CapturedPlan(TeamPlan plan, String output) {
    }
}