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
import vn.ptit.procon.engine.AgentActivity;
import vn.ptit.procon.engine.AgentStepUsage;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.MoveStartedEvent;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.SimulationEvent;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

class RefuelProbePlannerTest {

    private static final AgentId PATROL = new AgentId(1);
    private static final AgentId REFUEL = new AgentId(3);
    private final RefuelProbePlanner planner = new RefuelProbePlanner();

    @Test
    void choosesReachableLowFuelPatrol() {
        DayState state = basicState();

        TeamPlan plan = planner.plan(state);

        assertEquals(List.of(
                new WaitAction(2),
                new MoveAction(Direction.RIGHT),
                new WaitAction(2)), plan.actionsFor(PATROL));
        assertEquals(List.of(
                new MoveAction(Direction.LEFT),
                new WaitAction(4)), plan.actionsFor(REFUEL));
    }

    @Test
    void patrolWaitsUntilRefuelArrival() {
        DayState state = basicState();

        ValidDaySimulationResult result = simulate(state, planner.plan(state));

        assertEquals(AgentActivity.WAITING, result.timeline().get(0).agents().get(PATROL).activity());
        assertEquals(AgentActivity.WAITING, result.timeline().get(1).agents().get(PATROL).activity());
        assertEquals(new Position(0), result.timeline().get(1).agents().get(PATROL).position());
        assertEquals(new Position(0), result.timeline().get(1).agents().get(REFUEL).position());
    }

    @Test
    void daySimulatorEmitsRefueledEventForProbe() {
        DayState state = basicState();

        ValidDaySimulationResult result = simulate(state, planner.plan(state));

        RefueledEvent event = assertInstanceOf(
                RefueledEvent.class,
                result.events().stream().filter(RefueledEvent.class::isInstance)
                        .findFirst().orElseThrow());
        assertEquals(2, event.step());
        assertEquals(PATROL, event.patrolId());
        assertEquals(new Position(0), event.position());
        assertEquals(0, event.before());
        assertEquals(5, event.after());
        assertEquals(List.of(REFUEL), event.refuelAgents());
    }

    @Test
    void patrolMovementStartsAfterRefillEvent() {
        DayState state = basicState();

        ValidDaySimulationResult result = simulate(state, planner.plan(state));

        int refillIndex = eventIndex(result.events(), RefueledEvent.class, PATROL);
        int moveIndex = eventIndex(result.events(), MoveStartedEvent.class, PATROL);
        MoveStartedEvent move = (MoveStartedEvent) result.events().get(moveIndex);
        assertTrue(moveIndex > refillIndex);
        assertEquals(2, move.step());
        assertEquals(new Position(0), move.source());
        assertEquals(new Position(1), move.destination());
    }

    @Test
    void completeTeamPlanConsumesExactlyDaySteps() {
        AgentId otherPatrol = new AgentId(5);
        AgentId otherRefuel = new AgentId(7);
        DayState state = lineState(
                5,
                7,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 0),
                        AgentState.refuel(REFUEL, new Position(1)),
                        AgentState.patrol(otherPatrol, new Position(3), 2),
                        AgentState.refuel(otherRefuel, new Position(4))),
                List.of(spot("probe", 2), spot("other", 4)));

        ValidDaySimulationResult result = simulate(state, planner.plan(state));

        for (AgentState agent : state.agents()) {
            assertEquals(new AgentStepUsage(7), result.stepUsage().get(agent.id()));
        }
    }

    @Test
    void refuelRouteIsLegalAndStepWeighted() {
        Terrain[] terrain = {
            Terrain.ROAD, Terrain.ROAD, Terrain.PLAIN, Terrain.PLAIN,
            Terrain.ROAD, Terrain.MOUNTAIN, Terrain.PLAIN, Terrain.PLAIN
        };
        DayState state = state(
                terrain,
                4,
                2,
                5,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(6), 0),
                        AgentState.refuel(REFUEL, new Position(4))),
                List.of(spot("weighted", 7)),
                traffic(0, 1, 4));

        TeamPlan plan = planner.plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(List.of(Direction.UP_RIGHT, Direction.RIGHT, Direction.DOWN_RIGHT),
                moveDirections(plan.actionsFor(REFUEL)));
        assertEquals(List.of(new WaitAction(3), new MoveAction(Direction.RIGHT)),
                plan.actionsFor(PATROL));
        RefueledEvent event = (RefueledEvent) result.events().stream()
                .filter(RefueledEvent.class::isInstance).findFirst().orElseThrow();
        assertEquals(3, event.step());
    }

    @Test
    void postRefillMovementPrefersReachableStockedUdon() {
        Terrain[] terrain = new Terrain[9];
        Arrays.fill(terrain, Terrain.PLAIN);
        DayState state = state(
                terrain,
                3,
                3,
                5,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(4), 0),
                        AgentState.refuel(REFUEL, new Position(3))),
                List.of(spot("preferred", 5)),
                Map.of());

        TeamPlan plan = planner.plan(state);

        assertEquals(List.of(new WaitAction(2), new MoveAction(Direction.RIGHT), new WaitAction(1)),
                plan.actionsFor(PATROL));
    }

    @Test
    void pairSelectionFollowsDocumentedTieBreakers() {
        AgentId farLowFuel = new AgentId(1);
        AgentId nearHigherFuel = new AgentId(4);
        DayState shortestRouteState = lineState(
                5,
                6,
                5,
                List.of(
                        AgentState.patrol(farLowFuel, new Position(0), 0),
                        AgentState.patrol(nearHigherFuel, new Position(3), 1),
                        AgentState.refuel(new AgentId(9), new Position(2))),
                List.of(spot("far", 1), spot("near", 4)));
        assertEquals(nearHigherFuel, firstRefueledPatrol(shortestRouteState));

        AgentId higherFuel = new AgentId(2);
        AgentId lowerFuel = new AgentId(4);
        DayState fuelState = lineState(
                5,
                6,
                5,
                List.of(
                        AgentState.patrol(higherFuel, new Position(0), 1),
                        AgentState.patrol(lowerFuel, new Position(4), 0),
                        AgentState.refuel(new AgentId(9), new Position(2))),
                List.of(spot("left", 1), spot("right", 3)));
        assertEquals(lowerFuel, firstRefueledPatrol(fuelState));

        AgentId lowerPatrolId = new AgentId(2);
        AgentId higherPatrolId = new AgentId(4);
        AgentId lowerRefuelId = new AgentId(3);
        AgentId higherRefuelId = new AgentId(7);
        DayState idState = lineState(
                3,
                4,
                5,
                List.of(
                        AgentState.patrol(higherPatrolId, new Position(1), 0),
                        AgentState.refuel(higherRefuelId, new Position(0)),
                        AgentState.patrol(lowerPatrolId, new Position(1), 0),
                        AgentState.refuel(lowerRefuelId, new Position(2))),
                List.of(spot("id", 2)));
        TeamPlan idPlan = planner.plan(idState);
        assertEquals(List.of(new WaitAction(2), new MoveAction(Direction.RIGHT)),
                idPlan.actionsFor(lowerPatrolId));
        assertEquals(List.of(new WaitAction(4)), idPlan.actionsFor(higherPatrolId));
        assertEquals(List.of(new MoveAction(Direction.LEFT), new WaitAction(2)),
                idPlan.actionsFor(lowerRefuelId));
        assertEquals(List.of(new WaitAction(4)), idPlan.actionsFor(higherRefuelId));
    }

    @Test
    void noFeasibleRendezvousFallsBackToBrandAware() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN, Terrain.POND, Terrain.PLAIN},
                4,
                1,
                4,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 4),
                        AgentState.refuel(REFUEL, new Position(3))),
                List.of(spot("fallback", 1)),
                Map.of());

        TeamPlan plan = planner.plan(state);
        TeamPlan brandAware = new BrandAwarePlanner().plan(state);
        ValidDaySimulationResult result = simulate(state, plan);

        assertEquals(brandAware.actionsByAgent(), plan.actionsByAgent());
        assertTrue(result.events().stream().noneMatch(RefueledEvent.class::isInstance));
    }

    @Test
    void unavailableBrandAwareFallbackUsesValidatedWaitPlan() {
        WeightedRouteFinder unavailableFinder = new WeightedRouteFinder() {
            @Override
            public Optional<Route> find(DayState state, AgentState agent, Position goal) {
                throw new IllegalStateException("unavailable for test");
            }
        };
        RefuelProbePlanner failClosed = new RefuelProbePlanner(
                unavailableFinder, new RefuelRouteFinder(), new DaySimulator());
        DayState state = lineState(
                3,
                4,
                5,
                List.of(AgentState.patrol(PATROL, new Position(0), 4)),
                List.of(spot("fallback", 1)));

        TeamPlan plan = failClosed.plan(state);

        assertEquals(List.of(new WaitAction(4)), plan.actionsFor(PATROL));
        assertTrue(new PlanValidator().validate(state, plan).valid());
    }

    private AgentId firstRefueledPatrol(DayState state) {
        ValidDaySimulationResult result = simulate(state, planner.plan(state));
        return ((RefueledEvent) result.events().stream()
                .filter(RefueledEvent.class::isInstance).findFirst().orElseThrow()).patrolId();
    }

    private static int eventIndex(
            List<SimulationEvent> events,
            Class<? extends SimulationEvent> type,
            AgentId agentId) {
        for (int index = 0; index < events.size(); index++) {
            SimulationEvent event = events.get(index);
            if (type.isInstance(event)
                    && (event instanceof RefueledEvent refueled && refueled.patrolId().equals(agentId)
                            || event instanceof MoveStartedEvent move && move.agentId().equals(agentId))) {
                return index;
            }
        }
        throw new AssertionError("Expected event " + type.getSimpleName() + " for " + agentId);
    }

    private static DayState basicState() {
        return lineState(
                4,
                6,
                5,
                List.of(
                        AgentState.patrol(PATROL, new Position(0), 0),
                        AgentState.refuel(REFUEL, new Position(1))),
                List.of(spot("probe", 2)));
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

    private static Map<Position, TrafficStatus> traffic(int... positions) {
        Map<Position, TrafficStatus> result = new HashMap<>();
        for (int position : positions) {
            result.put(new Position(position), TrafficStatus.CLEAR);
        }
        return result;
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
}