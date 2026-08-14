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
import vn.ptit.procon.engine.AgentActivity;
import vn.ptit.procon.engine.AgentStepUsage;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.MoveStartedEvent;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;

class RefuelAwarePlannerTest {

    private static final AgentId PATROL = new AgentId(0);
    private static final AgentId REFUEL = new AgentId(1);
    private final RefuelAwarePlanner planner = new RefuelAwarePlanner();

    @Test
    void basicRendezvousRefillsThenCollectsOtherwiseUnreachableUdon() {
        DayState state = lineState(
                4,
                6,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 0),
                        AgentState.refuel(REFUEL, new Position(1))),
                List.of(spot("useful", 2)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(
                new WaitAction(2),
                new MoveAction(Direction.RIGHT),
                new MoveAction(Direction.RIGHT)), plan.actionsFor(PATROL));
        assertEquals(List.of(
                new MoveAction(Direction.LEFT),
                new WaitAction(4)), plan.actionsFor(REFUEL));

        RefueledEvent refueled = assertInstanceOf(
                RefueledEvent.class,
                result.events().stream().filter(RefueledEvent.class::isInstance)
                        .findFirst().orElseThrow());
        assertEquals(2, refueled.step());
        assertEquals(new Position(0), refueled.position());
        assertEquals(0, refueled.before());
        assertEquals(5, refueled.after());
        assertEquals(1, result.events().stream().filter(RefueledEvent.class::isInstance).count());
        assertEquals(new Position(0), result.timeline().get(1).agents().get(PATROL).position());
        assertEquals(new Position(0), result.timeline().get(1).agents().get(REFUEL).position());
        assertEquals(AgentActivity.WAITING,
                result.timeline().get(1).agents().get(PATROL).activity());
        assertEquals(new FiniteFuel(5), result.timeline().get(1).agents().get(PATROL).fuel());
        assertTrue(result.events().stream().anyMatch(event -> event instanceof MoveStartedEvent move
                && move.agentId().equals(PATROL) && move.step() == 2));
        assertTrue(result.events().stream().anyMatch(event -> event instanceof UdonCollectedEvent collected
                && collected.agentId().equals(PATROL) && collected.step() == 6));
        assertEquals(new Position(2), result.finalAgents().getFirst().position());
        assertEquals(new FiniteFuel(3), result.finalAgents().getFirst().fuel());
        assertEquals(1, result.portionsCollectedByAgent().get(PATROL));
        assertEquals(0, result.remainingSpotStock().get(new Position(2)));
    }

    @Test
    void refillCreatesCollectionValueThatBrandAwareAloneCannotReach() {
        DayState state = lineState(
                4,
                6,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 0),
                        AgentState.refuel(REFUEL, new Position(1))),
                List.of(spot("unlocked", 2)));

        ValidDaySimulationResult withoutRefill = simulate(
                state, new BrandAwarePlanner().plan(state));
        ValidDaySimulationResult withRefill = simulate(state, planner.plan(state));

        assertEquals(0, withoutRefill.portionsCollectedByAgent().get(PATROL));
        assertEquals(1, withRefill.portionsCollectedByAgent().get(PATROL));
        assertTrue(withRefill.events().stream().anyMatch(RefueledEvent.class::isInstance));
    }

    @Test
    void lowFuelIsNotServedWhenTravelLeavesNoTimeForUsefulCollection() {
        DayState state = lineState(
                4,
                5,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 0),
                        AgentState.refuel(REFUEL, new Position(1))),
                List.of(spot("too-late", 2)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new WaitAction(5)), plan.actionsFor(PATROL));
        assertEquals(List.of(new WaitAction(5)), plan.actionsFor(REFUEL));
        assertTrue(result.events().stream().noneMatch(RefueledEvent.class::isInstance));
        assertEquals(1, result.remainingSpotStock().get(new Position(2)));
    }

    @Test
    void fullFuelPatrolIsNotSelectedForService() {
        DayState state = lineState(
                3,
                4,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 5),
                        AgentState.refuel(REFUEL, new Position(2))),
                List.of(spot("normal", 1)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new MoveAction(Direction.RIGHT), new WaitAction(2)),
                plan.actionsFor(PATROL));
        assertEquals(List.of(new WaitAction(4)), plan.actionsFor(REFUEL));
        assertTrue(result.events().stream().noneMatch(RefueledEvent.class::isInstance));
        assertEquals(1, result.portionsCollectedByAgent().get(PATROL));
    }

    @Test
    void coLocatedStartUsesOneExplicitSharedStepBeforePatrolMoves() {
        DayState state = lineState(
                2,
                3,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 0),
                        AgentState.refuel(REFUEL, new Position(0))),
                List.of(spot("near", 1)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new WaitAction(1), new MoveAction(Direction.RIGHT)),
                plan.actionsFor(PATROL));
        assertEquals(List.of(new WaitAction(3)), plan.actionsFor(REFUEL));
        RefueledEvent event = (RefueledEvent) result.events().stream()
                .filter(RefueledEvent.class::isInstance).findFirst().orElseThrow();
        assertEquals(1, event.step());
        assertTrue(result.events().stream().anyMatch(candidate -> candidate instanceof MoveStartedEvent move
                && move.agentId().equals(PATROL) && move.step() == 1));
        assertEquals(new Position(1), result.finalAgents().getFirst().position());
        assertEquals(1, result.portionsCollectedByAgent().get(PATROL));
    }

    @Test
    void equalBenefitCandidatesSelectLowerPatrolIdDeterministically() {
        AgentId otherPatrol = new AgentId(2);
        DayState state = lineState(
                5,
                6,
                5,
                List.of(
                        AgentState.patrol(otherPatrol, new Position(4), 0),
                        AgentState.refuel(REFUEL, new Position(2)),
                        AgentState.patrol(PATROL, new Position(0), 0)),
                List.of(spot("left", 1), spot("right", 3)));

        TeamPlan first = planner.plan(state);
        TeamPlan second = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, first);

        assertEquals(first.actionsByAgent(), second.actionsByAgent());
        assertEquals(List.of(new WaitAction(4), new MoveAction(Direction.RIGHT)),
                first.actionsFor(PATROL));
        assertEquals(List.of(new WaitAction(6)), first.actionsFor(otherPatrol));
        RefueledEvent event = (RefueledEvent) result.events().stream()
                .filter(RefueledEvent.class::isInstance).findFirst().orElseThrow();
        assertEquals(PATROL, event.patrolId());
    }

    @Test
    void unservedPatrolStillRunsNormalBrandAwareRoute() {
        AgentId unserved = new AgentId(2);
        DayState state = lineState(
                5,
                6,
                5,
                List.of(
                        AgentState.patrol(unserved, new Position(3), 1),
                        AgentState.refuel(REFUEL, new Position(1)),
                        AgentState.patrol(PATROL, new Position(0), 0)),
                List.of(spot("refilled", 2), spot("normal", 4)));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(Direction.RIGHT, Direction.RIGHT), moveDirections(plan.actionsFor(PATROL)));
        assertEquals(List.of(Direction.RIGHT), moveDirections(plan.actionsFor(unserved)));
        assertEquals(1, result.portionsCollectedByAgent().get(PATROL));
        assertEquals(1, result.portionsCollectedByAgent().get(unserved));
        assertEquals(2, result.brandsCollected().size());
    }

    @Test
    void everyAgentPlanExplicitlyConsumesTheWholeDay() {
        AgentId unserved = new AgentId(2);
        AgentId extraRefuel = new AgentId(3);
        DayState state = lineState(
                5,
                7,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 0),
                        AgentState.refuel(REFUEL, new Position(1)),
                        AgentState.patrol(unserved, new Position(3), 1),
                        AgentState.refuel(extraRefuel, new Position(4))),
                List.of(spot("refilled", 2), spot("normal", 4)));

        ValidDaySimulationResult result = simulate(state, planner.plan(state));

        for (AgentState agent : state.agents()) {
            assertEquals(new AgentStepUsage(7), result.stepUsage().get(agent.id()));
        }
    }

    @Test
    void invalidCoordinationFallsBackToIndependentlyValidBrandAwarePlan() {
        RefuelRouteFinder invalidFinder = new RefuelRouteFinder() {
            @Override
            public Optional<Route> find(DayState state, AgentState refuel, Position goal) {
                return Optional.of(new Route(
                        refuel.position(), goal, List.of(Direction.RIGHT), 2, 0));
            }
        };
        RefuelAwarePlanner invalidPlanner = new RefuelAwarePlanner(
                new WeightedRouteFinder(), invalidFinder, new PlanValidator());
        DayState state = lineState(
                4,
                6,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 0),
                        AgentState.refuel(REFUEL, new Position(1))),
                List.of(spot("fallback", 2)));

        TeamPlan plan = invalidPlanner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(new WaitAction(6)), plan.actionsFor(PATROL));
        assertEquals(List.of(new WaitAction(6)), plan.actionsFor(REFUEL));
        assertTrue(result.events().stream().noneMatch(RefueledEvent.class::isInstance));
    }

    @Test
    void noPositiveBenefitLogsCandidateProjectionAndSummary() {
        RefuelAwarePlanner diagnosticPlanner = new RefuelAwarePlanner(
                new WeightedRouteFinder(), new RefuelRouteFinder(), new PlanValidator(), true);
        DayState state = lineState(
                4,
                5,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 0),
                        AgentState.refuel(REFUEL, new Position(1))),
                List.of(spot("too-late", 2)));

        CapturedPlan captured = capturePlan(diagnosticPlanner, state);

        assertEquals(List.of(new WaitAction(5)), captured.plan().actionsFor(PATROL));
        assertTrue(captured.output().contains(
                "REFUEL_CANDIDATE day=0 refuelAgent=1 patrolAgent=0 patrolFuel=0"
                        + " refuelTravelSteps=2 projectedGainWithoutRefill=0"
                        + " projectedGainWithRefill=0 projectedNewBrandsWithoutRefill=0"
                        + " projectedNewBrandsWithRefill=0 selected=false"));
        assertTrue(captured.output().contains(
                "REFUEL_NO_ASSIGN day=0 reason=NO_POSITIVE_BENEFIT candidates=1"));
    }

    @Test
    void candidateDiagnosticsDoNotChangeRefuelAwareSelection() {
        DayState state = lineState(
                4,
                6,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 0),
                        AgentState.refuel(REFUEL, new Position(1))),
                List.of(spot("useful", 2)));
        RefuelAwarePlanner quiet = new RefuelAwarePlanner(
                new WeightedRouteFinder(), new RefuelRouteFinder(), new PlanValidator(), false);
        RefuelAwarePlanner diagnostic = new RefuelAwarePlanner(
                new WeightedRouteFinder(), new RefuelRouteFinder(), new PlanValidator(), true);

        TeamPlan quietPlan = quiet.plan(state);
        CapturedPlan captured = capturePlan(diagnostic, state);

        assertEquals(quietPlan.actionsByAgent(), captured.plan().actionsByAgent());
        assertTrue(captured.output().contains("selected=true"));
        assertTrue(new PlanValidator().validate(state, captured.plan()).valid());
    }

    private static ValidDaySimulationResult simulate(DayState state, TeamPlan plan) {
        assertTrue(new PlanValidator().validate(state, plan).valid());
        return assertInstanceOf(
                ValidDaySimulationResult.class, new DaySimulator().simulate(state, plan));
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

    private static List<Direction> moveDirections(List<AgentAction> actions) {
        return actions.stream()
                .filter(MoveAction.class::isInstance)
                .map(MoveAction.class::cast)
                .map(MoveAction::direction)
                .toList();
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), new Position(position), 1);
    }

    private static DayState lineState(
            int width,
            int budget,
            int capacity,
            List<AgentState> agents,
            List<UdonSpot> spots) {
        Terrain[] terrains = new Terrain[width];
        Arrays.fill(terrains, Terrain.PLAIN);
        Map<Position, Integer> stock = new HashMap<>();
        for (UdonSpot spot : spots) {
            stock.put(spot.position(), spot.stockCapacity());
        }
        StaticMatchData matchData = new StaticMatchData(
                new HexMap(width, 1, terrains),
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new FuelCapacity(capacity),
                spots);
        return new DayState(
                matchData,
                new DayIndex(0),
                new ArrayList<>(agents),
                Map.of(),
                stock);
    }

    private record CapturedPlan(TeamPlan plan, String output) {
    }
}
