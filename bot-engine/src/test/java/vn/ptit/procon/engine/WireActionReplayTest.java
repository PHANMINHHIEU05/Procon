package vn.ptit.procon.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.agent.InitialAgent;
import vn.ptit.procon.domain.agent.UnlimitedFuel;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.traffic.TrafficStatus;

class WireActionReplayTest {

    private static final AgentId PATROL_ID = new AgentId(0);
    private static final AgentId REFUEL_ID = new AgentId(1);
    private static final AgentId OTHER_PATROL_ID = new AgentId(2);

    @Test
    void m4771ShapeRefuelBeforeFirstMoveAcceptsAndMatchesDaySimulator() {
        DayState state = lineState(
                62,
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(61), 0),
                        AgentState.refuel(REFUEL_ID, new Position(60))),
                4);
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(2), new MoveAction(Direction.LEFT)),
                REFUEL_ID, List.of(new MoveAction(Direction.RIGHT), new WaitAction(2))));
        List<List<Integer>> encoded = List.of(
                List.of(-2, Direction.LEFT.code()),
                List.of(Direction.RIGHT.code(), -2));

        WireActionReplay.AgentReplayResult isolated =
                WireActionReplay.replayAgent(state, state.agents().getFirst(), encoded.getFirst(), state.stepBudget());
        assertFalse(isolated.valid());

        ValidDaySimulationResult simulated = valid(new DaySimulator().simulate(state, plan));
        WireActionReplay.TeamReplayResult replay =
                WireActionReplay.replayTeam(state, encoded, state.stepBudget());

        assertTrue(replay.allValid(), replay.firstRejection());
        assertMatchesSimulation(simulated, replay, PATROL_ID);
        assertEquals(new Position(60), replay.resultFor(PATROL_ID).finalPosition());
        assertEquals(new FiniteFuel(4), replay.resultFor(PATROL_ID).finalFuel());
    }

    @Test
    void noRefuelNegativeControlRejectsBeforeSubmission() {
        DayState state = lineState(
                62,
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(61), 0),
                        AgentState.refuel(REFUEL_ID, new Position(60))),
                4);
        List<List<Integer>> encoded = List.of(
                List.of(-2, Direction.LEFT.code()),
                List.of(new WaitAction(4).steps() * -1));

        WireActionReplay.TeamReplayResult replay =
                WireActionReplay.replayTeam(state, encoded, state.stepBudget());

        assertFalse(replay.allValid());
        assertTrue(replay.firstRejection().contains("cannot afford"));
    }

    @Test
    void lateRefuelDoesNotRetroactivelyLegalizeEarlierMovement() {
        DayState state = lineState(
                3,
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(2), 0),
                        AgentState.refuel(REFUEL_ID, new Position(0))),
                4);
        List<List<Integer>> encoded = List.of(
                List.of(Direction.LEFT.code(), -2),
                List.of(Direction.RIGHT.code(), Direction.RIGHT.code()));

        WireActionReplay.TeamReplayResult replay =
                WireActionReplay.replayTeam(state, encoded, state.stepBudget());

        assertFalse(replay.allValid());
        assertTrue(replay.firstRejection().contains("cannot afford"));
    }

    @Test
    void waitingCoLocatedRefillMatchesDaySimulator() {
        DayState state = lineState(
                2,
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 0),
                        AgentState.refuel(REFUEL_ID, new Position(0))),
                3);
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(1), new MoveAction(Direction.RIGHT)),
                REFUEL_ID, List.of(new WaitAction(3))));
        List<List<Integer>> encoded = List.of(
                List.of(-1, Direction.RIGHT.code()),
                List.of(-3));

        assertMatchesSimulation(
                valid(new DaySimulator().simulate(state, plan)),
                WireActionReplay.replayTeam(state, encoded, state.stepBudget()),
                PATROL_ID);
    }

    @Test
    void refuelArrivalTimingMatchesDaySimulator() {
        DayState state = lineState(
                2,
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 0),
                        AgentState.refuel(REFUEL_ID, new Position(1))),
                4);
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(2), new MoveAction(Direction.RIGHT)),
                REFUEL_ID, List.of(new MoveAction(Direction.LEFT), new WaitAction(2))));
        List<List<Integer>> encoded = List.of(
                List.of(-2, Direction.RIGHT.code()),
                List.of(Direction.LEFT.code(), -2));

        assertMatchesSimulation(
                valid(new DaySimulator().simulate(state, plan)),
                WireActionReplay.replayTeam(state, encoded, state.stepBudget()),
                PATROL_ID);
    }

    @Test
    void incidentalRefillUsesActualTimelineNotPlannerAssignment() {
        DayState state = lineState(
                4,
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 1),
                        AgentState.refuel(REFUEL_ID, new Position(1)),
                        AgentState.patrol(OTHER_PATROL_ID, new Position(3), 5)),
                8);
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new MoveAction(Direction.RIGHT), new MoveAction(Direction.RIGHT), new WaitAction(4)),
                REFUEL_ID, List.of(new WaitAction(8)),
                OTHER_PATROL_ID, List.of(new WaitAction(8))));
        List<List<Integer>> encoded = List.of(
                List.of(Direction.RIGHT.code(), Direction.RIGHT.code(), -4),
                List.of(-8),
                List.of(-8));

        WireActionReplay.TeamReplayResult replay =
                WireActionReplay.replayTeam(state, encoded, state.stepBudget());

        assertTrue(replay.allValid(), replay.firstRejection());
        assertMatchesSimulation(valid(new DaySimulator().simulate(state, plan)), replay, PATROL_ID);
    }

    @Test
    void movingRefuelDoesNotCreatePhantomSourceRefill() {
        DayState state = lineState(
                2,
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(1), 1),
                        AgentState.refuel(REFUEL_ID, new Position(1))),
                2);
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(2)),
                REFUEL_ID, List.of(new MoveAction(Direction.LEFT))));
        List<List<Integer>> encoded = List.of(
                List.of(-2),
                List.of(Direction.LEFT.code()));

        ValidDaySimulationResult simulated = valid(new DaySimulator().simulate(state, plan));
        WireActionReplay.TeamReplayResult replay =
                WireActionReplay.replayTeam(state, encoded, state.stepBudget());

        assertTrue(replay.allValid(), replay.firstRejection());
        assertMatchesSimulation(simulated, replay, PATROL_ID);
        assertEquals(new FiniteFuel(1), replay.resultFor(PATROL_ID).finalFuel());
    }

    @Test
    void structuralReplayStillRejectsPondDestinationWithRefuelPresent() {
        DayState state = state(
                3,
                new Terrain[] {Terrain.PLAIN, Terrain.POND, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 5),
                        AgentState.refuel(REFUEL_ID, new Position(2))),
                3,
                Map.of());
        List<List<Integer>> encoded = List.of(
                List.of(Direction.RIGHT.code(), -1),
                List.of(-3));

        WireActionReplay.TeamReplayResult replay =
                WireActionReplay.replayTeam(state, encoded, state.stepBudget());

        assertFalse(replay.allValid());
        assertTrue(replay.firstRejection().contains("destination is POND"));
    }

    @Test
    void structuralReplayStillRejectsRoadMoveWithoutAuthoritativeTraffic() {
        DayState state = state(
                2,
                new Terrain[] {Terrain.ROAD, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 5),
                        AgentState.refuel(REFUEL_ID, new Position(1))),
                2,
                Map.of());
        List<List<Integer>> encoded = List.of(
                List.of(Direction.RIGHT.code()),
                List.of(-2));

        WireActionReplay.TeamReplayResult replay =
                WireActionReplay.replayTeam(state, encoded, state.stepBudget());

        assertFalse(replay.allValid());
        assertTrue(replay.firstRejection().contains("Missing authoritative traffic"));
    }

    @Test
    void structuralReplayRejectsInvalidDirectionCode() {
        DayState state = lineState(
                2,
                List.of(AgentState.patrol(PATROL_ID, new Position(0), 5)),
                2);

        assertInvalidContains(state, List.of(List.of(6)), "Invalid direction code");
    }

    @Test
    void structuralReplayRejectsMoveLeavingMap() {
        DayState state = lineState(
                2,
                List.of(AgentState.patrol(PATROL_ID, new Position(0), 5)),
                2);

        assertInvalidContains(state, List.of(List.of(Direction.LEFT.code())), "leaves the map");
    }

    @Test
    void structuralReplayRejectsWaitOverflow() {
        DayState state = lineState(
                1,
                List.of(AgentState.patrol(PATROL_ID, new Position(0), 5)),
                2);

        assertInvalidContains(state, List.of(List.of(-3)), "ends at step 3 > dayBudget 2");
    }

    @Test
    void structuralReplayRejectsMoveOverflow() {
        DayState state = lineState(
                2,
                List.of(AgentState.patrol(PATROL_ID, new Position(0), 5)),
                1);

        assertInvalidContains(state, List.of(List.of(Direction.RIGHT.code())), "ends at step 2 > dayBudget 1");
    }

    @Test
    void teamReplayRejectsMissingEncodedAgentActions() {
        DayState state = lineState(
                1,
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 5),
                        AgentState.refuel(REFUEL_ID, new Position(0))),
                1);

        assertInvalidContains(state, List.of(List.of(-1)), "required day steps");
    }

    @Test
    void teamReplayRejectsSurplusCommandStartingAtBudget() {
        DayState state = lineState(
                1,
                List.of(AgentState.patrol(PATROL_ID, new Position(0), 5)),
                2);

        assertInvalidContains(state, List.of(List.of(-2, -1)), "actions remaining after the day step budget");
    }

    @Test
    void roadClearSourceDurationAndFuelMatchDaySimulator() {
        DayState state = state(
                2,
                new Terrain[] {Terrain.ROAD, Terrain.PLAIN},
                List.of(AgentState.patrol(PATROL_ID, new Position(0), 5)),
                2,
                Map.of(new Position(0), TrafficStatus.CLEAR));
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new MoveAction(Direction.RIGHT), new WaitAction(1))));
        List<List<Integer>> encoded = List.of(List.of(Direction.RIGHT.code(), -1));

        assertMatchesSimulation(
                valid(new DaySimulator().simulate(state, plan)),
                WireActionReplay.replayTeam(state, encoded, state.stepBudget()),
                PATROL_ID);
    }

    @Test
    void roadCongestedSourceDurationAndFuelMatchDaySimulator() {
        DayState state = state(
                2,
                new Terrain[] {Terrain.ROAD, Terrain.PLAIN},
                List.of(AgentState.patrol(PATROL_ID, new Position(0), 5)),
                3,
                Map.of(new Position(0), TrafficStatus.CONGESTED));
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new MoveAction(Direction.RIGHT), new WaitAction(1))));
        List<List<Integer>> encoded = List.of(List.of(Direction.RIGHT.code(), -1));

        assertMatchesSimulation(
                valid(new DaySimulator().simulate(state, plan)),
                WireActionReplay.replayTeam(state, encoded, state.stepBudget()),
                PATROL_ID);
    }

    @Test
    void roadJammedSourceDurationAndFuelMatchDaySimulator() {
        DayState state = state(
                2,
                new Terrain[] {Terrain.ROAD, Terrain.PLAIN},
                List.of(AgentState.patrol(PATROL_ID, new Position(0), 5)),
                5,
                Map.of(new Position(0), TrafficStatus.JAMMED));
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new MoveAction(Direction.RIGHT), new WaitAction(1))));
        List<List<Integer>> encoded = List.of(List.of(Direction.RIGHT.code(), -1));

        assertMatchesSimulation(
                valid(new DaySimulator().simulate(state, plan)),
                WireActionReplay.replayTeam(state, encoded, state.stepBudget()),
                PATROL_ID);
    }

    @Test
    void refuelMountainMovementDurationConsumesNoFuelAndMatchesDaySimulator() {
        DayState state = state(
                2,
                new Terrain[] {Terrain.MOUNTAIN, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(1), 5),
                        AgentState.refuel(REFUEL_ID, new Position(0))),
                4,
                Map.of());
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(4)),
                REFUEL_ID, List.of(new MoveAction(Direction.RIGHT), new WaitAction(1))));
        List<List<Integer>> encoded = List.of(
                List.of(-4),
                List.of(Direction.RIGHT.code(), -1));

        assertMatchesSimulation(
                valid(new DaySimulator().simulate(state, plan)),
                WireActionReplay.replayTeam(state, encoded, state.stepBudget()),
                REFUEL_ID);
    }

    @Test
    void simultaneousPatrolAndRefuelArrivalsRefillAtDestination() {
        DayState state = lineState(
                3,
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 3),
                        AgentState.refuel(REFUEL_ID, new Position(2))),
                2);
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new MoveAction(Direction.RIGHT)),
                REFUEL_ID, List.of(new MoveAction(Direction.LEFT))));
        List<List<Integer>> encoded = List.of(
                List.of(Direction.RIGHT.code()),
                List.of(Direction.LEFT.code()));

        assertMatchesSimulation(
                valid(new DaySimulator().simulate(state, plan)),
                WireActionReplay.replayTeam(state, encoded, state.stepBudget()),
                PATROL_ID);
    }

    @Test
    void fullFuelPatrolDoesNotReceiveSpuriousFuelChangeWhenCoLocated() {
        DayState state = lineState(
                1,
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 5),
                        AgentState.refuel(REFUEL_ID, new Position(0))),
                2);
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(2)),
                REFUEL_ID, List.of(new WaitAction(2))));
        List<List<Integer>> encoded = List.of(List.of(-2), List.of(-2));

        assertMatchesSimulation(
                valid(new DaySimulator().simulate(state, plan)),
                WireActionReplay.replayTeam(state, encoded, state.stepBudget()),
                PATROL_ID);
    }

    @Test
    void patrolCanBeRefilledAgainAfterReturningToRefuelCell() {
        DayState state = lineState(
                2,
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 0),
                        AgentState.refuel(REFUEL_ID, new Position(0))),
                6);
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(
                        new WaitAction(1),
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.LEFT),
                        new WaitAction(1)),
                REFUEL_ID, List.of(new WaitAction(6))));
        List<List<Integer>> encoded = List.of(
                List.of(-1, Direction.RIGHT.code(), Direction.LEFT.code(), -1),
                List.of(-6));

        assertMatchesSimulation(
                valid(new DaySimulator().simulate(state, plan)),
                WireActionReplay.replayTeam(state, encoded, state.stepBudget()),
                PATROL_ID);
    }

    private static void assertMatchesSimulation(
            ValidDaySimulationResult simulated,
            WireActionReplay.TeamReplayResult replay,
            AgentId agentId) {
        AgentState simulatedAgent = simulated.finalAgents().stream()
                .filter(agent -> agent.id().equals(agentId))
                .findFirst()
                .orElseThrow();
        WireActionReplay.AgentReplayResult replayAgent = replay.resultFor(agentId);

        assertTrue(replay.allValid(), replay.firstRejection());
        assertEquals(simulated.stepUsage().get(agentId).totalSteps(), replayAgent.totalDuration());
        assertEquals(simulatedAgent.position(), replayAgent.finalPosition());
        assertEquals(simulatedAgent.fuel(), replayAgent.finalFuel());
    }

    private static void assertInvalidContains(
            DayState state,
            List<List<Integer>> encoded,
            String expectedReasonPart) {
        WireActionReplay.TeamReplayResult replay =
                WireActionReplay.replayTeam(state, encoded, state.stepBudget());

        assertFalse(replay.allValid());
        assertTrue(replay.firstRejection().contains(expectedReasonPart), replay.firstRejection());
    }

    private static ValidDaySimulationResult valid(DaySimulationResult result) {
        return assertInstanceOf(ValidDaySimulationResult.class, result);
    }

    private static TeamPlan plans(Map<AgentId, ? extends List<? extends AgentAction>> plans) {
        return new TeamPlan(plans);
    }

    private static DayState lineState(
            int width,
            List<AgentState> agents,
            int steps) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        return state(width, terrain, agents, steps, Map.of());
    }

    private static DayState state(
            int width,
            Terrain[] terrain,
            List<AgentState> agents,
            int steps,
            Map<Position, TrafficStatus> traffic) {
        List<InitialAgent> initialAgents = agents.stream()
                .map(agent -> new InitialAgent(agent.id(), agent.position()))
                .toList();
        StaticMatchData matchData = new StaticMatchData(
                new HexMap(width, terrain.length / width, terrain),
                new DayStepBudgets(new int[] {steps}),
                initialAgents,
                new FuelCapacity(5),
                List.of());
        return new DayState(matchData, new DayIndex(0), agents, traffic, Map.of());
    }
}
