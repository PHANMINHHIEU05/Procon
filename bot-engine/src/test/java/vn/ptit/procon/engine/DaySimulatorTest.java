package vn.ptit.procon.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;

class DaySimulatorTest {

    private static final AgentId PATROL_ID = new AgentId(0);
    private static final AgentId REFUEL_ID = new AgentId(1);

    @ParameterizedTest
    @MethodSource("officialMoveCases")
    void executesOfficialMovementCosts(
            Terrain sourceTerrain,
            TrafficStatus traffic,
            int budget,
            int initialFuel,
            int expectedSteps,
            int expectedFuel) {
        DayState state = state(
                new Terrain[] {sourceTerrain, Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), initialFuel),
                budget,
                traffic == null ? Map.of() : Map.of(new Position(0), traffic),
                Map.of());

        DaySimulationResult result = simulate(state, plan(PATROL_ID, new MoveAction(Direction.RIGHT)));

        ValidDaySimulationResult valid = valid(result);
        assertEquals(new Position(1), valid.finalAgents().getFirst().position());
        assertEquals(new FiniteFuel(expectedFuel), valid.finalAgents().getFirst().fuel());
        assertEquals(expectedSteps, valid.stepUsage().get(PATROL_ID).explicitSteps());
    }

    @Test
    void sourceTerrainDeterminesMovementCost() {
        DayState mountainState = state(
                new Terrain[] {Terrain.MOUNTAIN, Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 5),
                3,
                Map.of(),
                Map.of());
        DayState plainState = state(
                new Terrain[] {Terrain.PLAIN, Terrain.MOUNTAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 5),
                2,
                Map.of(),
                Map.of());

        ValidDaySimulationResult mountainResult = valid(
                simulate(mountainState, plan(PATROL_ID, new MoveAction(Direction.RIGHT))));
        ValidDaySimulationResult plainResult = valid(
                simulate(plainState, plan(PATROL_ID, new MoveAction(Direction.RIGHT))));

        assertEquals(new FiniteFuel(3), mountainResult.finalAgents().getFirst().fuel());
        assertEquals(new FiniteFuel(4), plainResult.finalAgents().getFirst().fuel());
        assertEquals(3, mountainResult.stepUsage().get(PATROL_ID).explicitSteps());
        assertEquals(2, plainResult.stepUsage().get(PATROL_ID).explicitSteps());
    }

    @Test
    void timelineKeepsSourceUntilMoveCompletionThenArrives() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 5),
                5,
                Map.of(),
                Map.of());

        ValidDaySimulationResult result = valid(
                simulate(state, plan(PATROL_ID,
                        new MoveAction(Direction.RIGHT), new WaitAction(3))));

        assertEquals(new Position(0), result.timeline().get(0).agents().get(PATROL_ID).position());
        assertEquals(new Position(1), result.timeline().get(1).agents().get(PATROL_ID).position());
        assertEquals(new Position(1), result.timeline().get(4).agents().get(PATROL_ID).position());
        assertEquals(5, result.stepUsage().get(PATROL_ID).explicitSteps());
        assertEquals(AgentActivity.WAITING,
                result.timeline().get(4).agents().get(PATROL_ID).activity());
    }

    @Test
    void explicitWaitPreservesPositionFuelAndRoadOccupancy() {
        DayState state = state(
                new Terrain[] {Terrain.ROAD},
                AgentState.patrol(PATROL_ID, new Position(0), 4),
                4,
                Map.of(new Position(0), TrafficStatus.CLEAR),
                Map.of());

        ValidDaySimulationResult result = valid(
                simulate(state, plan(PATROL_ID, new WaitAction(4))));

        AgentState finalAgent = result.finalAgents().getFirst();
        assertEquals(new Position(0), finalAgent.position());
        assertEquals(new FiniteFuel(4), finalAgent.fuel());
        assertEquals(new AgentStepUsage(4), result.stepUsage().get(PATROL_ID));
        assertEquals(4, result.roadStoppedSteps().get(new Position(0)));
        assertEquals(4, result.events().stream().filter(WaitStepEvent.class::isInstance).count());
    }

    @Test
    void liveBugShapeRejectsUnderfilledPlanAndValidatorAgrees() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 5),
                30,
                Map.of(),
                Map.of());
        TeamPlan underfilled = plan(PATROL_ID, new MoveAction(Direction.RIGHT));

        InvalidDaySimulationResult result = invalid(simulate(state, underfilled));

        assertEquals(SimulationFailureCode.STEP_UNDERFLOW, result.failure().code());
        assertEquals(2, result.failure().step());
        PlanValidation validation = new PlanValidator().validate(state, underfilled);
        assertFalse(validation.valid());
        assertEquals(SimulationFailureCode.STEP_UNDERFLOW,
                validation.failure().orElseThrow().code());
    }

    @Test
    void explicitlyPaddedPlanConsumesThirtyStepsAndRetainsMoveOutcome() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 5),
                30,
                Map.of(),
                Map.of());

        ValidDaySimulationResult result = valid(simulate(
                state,
                plan(PATROL_ID, new MoveAction(Direction.RIGHT), new WaitAction(28))));

        assertEquals(30, result.timeline().size());
        assertEquals(new Position(1), result.finalAgents().getFirst().position());
        assertEquals(new FiniteFuel(4), result.finalAgents().getFirst().fuel());
        assertEquals(new AgentStepUsage(30), result.stepUsage().get(PATROL_ID));
        assertEquals(28, result.events().stream().filter(WaitStepEvent.class::isInstance).count());
    }

    @Test
    void explicitDurationBeyondThirtyStepsRemainsOverflow() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 5),
                30,
                Map.of(),
                Map.of());

        assertFailure(state, plan(PATROL_ID, new WaitAction(31)),
                SimulationFailureCode.STEP_OVERFLOW);
    }

    @Test
    void invalidMovesFailClosed() {
        assertFailure(
                state(new Terrain[] {Terrain.PLAIN},
                        AgentState.patrol(PATROL_ID, new Position(0), 5), 2, Map.of(), Map.of()),
                plan(PATROL_ID, new MoveAction(Direction.RIGHT)),
                SimulationFailureCode.NOT_ADJACENT);
        assertFailure(
                state(new Terrain[] {Terrain.PLAIN, Terrain.POND},
                        AgentState.patrol(PATROL_ID, new Position(0), 5), 2, Map.of(), Map.of()),
                plan(PATROL_ID, new MoveAction(Direction.RIGHT)),
                SimulationFailureCode.POND_DESTINATION);
        assertFailure(
                state(new Terrain[] {Terrain.PLAIN, Terrain.PLAIN},
                        AgentState.patrol(PATROL_ID, new Position(0), 0), 2, Map.of(), Map.of()),
                plan(PATROL_ID, new MoveAction(Direction.RIGHT)),
                SimulationFailureCode.NO_FUEL);
        assertFailure(
                state(new Terrain[] {Terrain.PLAIN, Terrain.PLAIN},
                        AgentState.patrol(PATROL_ID, new Position(0), 5), 1, Map.of(), Map.of()),
                plan(PATROL_ID, new MoveAction(Direction.RIGHT)),
                SimulationFailureCode.STEP_OVERFLOW);
        assertFailure(
                state(new Terrain[] {Terrain.ROAD, Terrain.PLAIN},
                        AgentState.patrol(PATROL_ID, new Position(0), 5), 1, Map.of(), Map.of()),
                plan(PATROL_ID, new MoveAction(Direction.RIGHT)),
                SimulationFailureCode.MISSING_TRAFFIC);
    }

    @Test
    void invalidPlanDoesNotExposePartiallyExecutedFinalState() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 5),
                3,
                Map.of(),
                Map.of());
        TeamPlan malformed = new TeamPlan(Map.of(
                PATROL_ID,
                List.of(new WaitAction(2), new MoveAction(Direction.RIGHT))));

        DaySimulationResult result = new DaySimulator().simulate(state, malformed);

        InvalidDaySimulationResult invalid = invalid(result);
        assertEquals(SimulationFailureCode.STEP_OVERFLOW, invalid.failure().code());
        assertEquals(2, invalid.events().size());
    }

    @Test
    void malformedPlanStructureIsRejected() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 0),
                1,
                Map.of(),
                Map.of());

        InvalidDaySimulationResult missing = invalid(
                new DaySimulator().simulate(state, new TeamPlan(Map.of())));
        InvalidDaySimulationResult unknown = invalid(
                new DaySimulator().simulate(
                        state,
                        new TeamPlan(Map.of(new AgentId(9), List.of(new WaitAction(1))))));

        assertEquals(SimulationFailureCode.MISSING_AGENT_PLAN, missing.failure().code());
        assertEquals(SimulationFailureCode.UNKNOWN_AGENT, unknown.failure().code());
    }

    @Test
    void udonUsesFirstVisitPerPatrolAndSharedStock() {
        BrandId brandA = new BrandId("brand-a");
        BrandId brandB = new BrandId("brand-b");
        List<UdonSpot> spots = List.of(
                new UdonSpot(brandA, new Position(1), 2),
                new UdonSpot(brandB, new Position(2), 1));
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 5),
                10,
                Map.of(),
                Map.of(new Position(1), 2, new Position(2), 1),
                spots);
        TeamPlan plan = plan(PATROL_ID,
                new MoveAction(Direction.RIGHT),
                new MoveAction(Direction.RIGHT),
                new MoveAction(Direction.LEFT),
                new MoveAction(Direction.LEFT),
                new MoveAction(Direction.RIGHT));

        ValidDaySimulationResult result = valid(new DaySimulator().simulate(state, plan));

        assertEquals(2, result.portionsCollectedByAgent().get(PATROL_ID));
        assertEquals(Set.of(brandA, brandB), result.brandsCollected());
        assertEquals(Map.of(new Position(1), 1, new Position(2), 0), result.remainingSpotStock());
        assertEquals(2, result.events().stream().filter(UdonCollectedEvent.class::isInstance).count());
    }

    @Test
    void roadContributionUsesEndOfStepOccupiedCell() {
        DayState enteringRoad = state(
                new Terrain[] {Terrain.PLAIN, Terrain.ROAD},
                AgentState.patrol(PATROL_ID, new Position(0), 5),
                4,
                Map.of(new Position(1), TrafficStatus.CLEAR),
                Map.of());
        ValidDaySimulationResult entered = valid(simulate(
                enteringRoad, plan(PATROL_ID,
                        new MoveAction(Direction.RIGHT), new WaitAction(2))));

        assertEquals(3, entered.roadStoppedSteps().get(new Position(1)));

        DayState leavingRoad = state(
                new Terrain[] {Terrain.ROAD, Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 5),
                3,
                Map.of(new Position(0), TrafficStatus.CONGESTED),
                Map.of());
        ValidDaySimulationResult left = valid(simulate(
                leavingRoad, plan(PATROL_ID,
                        new MoveAction(Direction.RIGHT), new WaitAction(1))));

        assertEquals(1, left.roadStoppedSteps().get(new Position(0)));
    }

    @Test
    void refuelArrivesAndRefillsZeroFuelPatrolBeforeLaterMove() {
        DayState state = state(
                new Terrain[] {Terrain.ROAD, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 0),
                        AgentState.refuel(REFUEL_ID, new Position(1))),
                3,
                Map.of(new Position(0), TrafficStatus.CLEAR),
                Map.of());
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(2), new MoveAction(Direction.RIGHT)),
                REFUEL_ID, List.of(new MoveAction(Direction.LEFT), new WaitAction(1))));

        ValidDaySimulationResult result = valid(new DaySimulator().simulate(state, plan));

        assertEquals(new Position(1), result.finalAgents().get(0).position());
        assertEquals(new FiniteFuel(3), result.finalAgents().get(0).fuel());
        assertEquals(UnlimitedFuel.INSTANCE, result.finalAgents().get(1).fuel());
        assertEquals(1, result.events().stream().filter(RefueledEvent.class::isInstance).count());
        assertEquals(new Position(0), result.timeline().get(1).agents().get(REFUEL_ID).position());
        assertEquals(new Position(1), result.timeline().get(2).agents().get(PATROL_ID).position());
    }

    @Test
    void multiStepArrivalRefillsExactlyOnCompletionStep() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 4),
                        AgentState.refuel(REFUEL_ID, new Position(1))),
                2,
                Map.of(),
                Map.of());
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new MoveAction(Direction.RIGHT)),
                REFUEL_ID, List.of(new WaitAction(2))));

        ValidDaySimulationResult result = valid(simulate(state, plan));

        assertEquals(new Position(0), result.timeline().get(0).agents().get(PATROL_ID).position());
        assertEquals(new FiniteFuel(3), result.timeline().get(0).agents().get(PATROL_ID).fuel());
        assertEquals(new Position(1), result.timeline().get(1).agents().get(PATROL_ID).position());
        assertEquals(new FiniteFuel(5), result.timeline().get(1).agents().get(PATROL_ID).fuel());
        RefueledEvent refuel = assertInstanceOf(
                RefueledEvent.class,
                result.events().stream().filter(RefueledEvent.class::isInstance)
                        .findFirst().orElseThrow());
        assertEquals(2, refuel.step());
        assertEquals(3, refuel.before());
        assertEquals(5, refuel.after());
    }

    @Test
    void movingRefuelDoesNotRefillPatrolFromRetainedSource() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(1), 1),
                        AgentState.refuel(REFUEL_ID, new Position(1))),
                2,
                Map.of(),
                Map.of());
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(2)),
                REFUEL_ID, List.of(new MoveAction(Direction.LEFT))));

        ValidDaySimulationResult result = valid(simulate(state, plan));

        assertEquals(new Position(1), result.timeline().get(0).agents().get(REFUEL_ID).position());
        assertEquals(AgentActivity.MOVING,
                result.timeline().get(0).agents().get(REFUEL_ID).activity());
        assertEquals(new FiniteFuel(1), result.finalAgents().getFirst().fuel());
        assertEquals(0, result.events().stream().filter(RefueledEvent.class::isInstance).count());
    }

    @Test
    void simultaneousPatrolAndRefuelArrivalsCanRefillAtDestination() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 3),
                        AgentState.refuel(REFUEL_ID, new Position(2))),
                2,
                Map.of(),
                Map.of());
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new MoveAction(Direction.RIGHT)),
                REFUEL_ID, List.of(new MoveAction(Direction.LEFT))));

        ValidDaySimulationResult result = valid(simulate(state, plan));

        RefueledEvent refuel = result.events().stream()
                .filter(RefueledEvent.class::isInstance)
                .map(RefueledEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(2, refuel.step());
        assertEquals(new Position(1), refuel.position());
        assertEquals(new FiniteFuel(5), result.finalAgents().getFirst().fuel());
    }

    @Test
    void incidentalPatrolArrivalMatchesLiveFuelArithmetic() {
        AgentId intendedPatrol = new AgentId(2);
        Terrain[] terrain = {
            Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN,
            Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN
        };
        List<AgentState> agents = List.of(
                AgentState.patrol(PATROL_ID, new Position(0), 23),
                AgentState.refuel(REFUEL_ID, new Position(1)),
                AgentState.patrol(intendedPatrol, new Position(1), 42));
        StaticMatchData matchData = new StaticMatchData(
                new HexMap(terrain.length, 1, terrain),
                new DayStepBudgets(new int[] {18}),
                agents.stream().map(agent -> new InitialAgent(agent.id(), agent.position())).toList(),
                new FuelCapacity(60),
                List.of());
        DayState state = new DayState(
                matchData, new DayIndex(0), agents, Map.of(), Map.of());
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.RIGHT)),
                REFUEL_ID, List.of(new WaitAction(18)),
                intendedPatrol, List.of(new WaitAction(18))));

        ValidDaySimulationResult result = valid(simulate(state, plan));

        RefueledEvent incidentalRefill = result.events().stream()
                .filter(RefueledEvent.class::isInstance)
                .map(RefueledEvent.class::cast)
                .filter(event -> event.patrolId().equals(PATROL_ID))
                .findFirst()
                .orElseThrow();
        assertEquals(2, incidentalRefill.step());
        assertEquals(new Position(1), incidentalRefill.position());
        assertEquals(22, incidentalRefill.before());
        assertEquals(60, incidentalRefill.after());
        assertEquals(new FiniteFuel(57), result.timeline().get(7).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(55), result.timeline().get(11).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(52), result.timeline().get(17).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(52), result.finalAgents().getFirst().fuel());
        assertTrue(new PlanValidator().validate(state, plan).valid());
    }

    @Test
    void patrolIsRefilledOnceThenConsumesFuelThroughoutMultiStepDeparture() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 0),
                        AgentState.refuel(REFUEL_ID, new Position(1))),
                4,
                Map.of(),
                Map.of());
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(2), new MoveAction(Direction.RIGHT)),
                REFUEL_ID, List.of(new MoveAction(Direction.LEFT), new WaitAction(2))));

        ValidDaySimulationResult result = valid(simulate(state, plan));

        assertEquals(new FiniteFuel(0), result.timeline().get(0).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(5), result.timeline().get(1).agents().get(PATROL_ID).fuel());
        assertEquals(AgentActivity.WAITING,
                result.timeline().get(1).agents().get(PATROL_ID).activity());
        assertEquals(new FiniteFuel(4), result.timeline().get(2).agents().get(PATROL_ID).fuel());
        assertEquals(AgentActivity.MOVING,
                result.timeline().get(2).agents().get(PATROL_ID).activity());
        assertEquals(new Position(0), result.timeline().get(2).agents().get(PATROL_ID).position());
        assertEquals(new FiniteFuel(4), result.timeline().get(3).agents().get(PATROL_ID).fuel());
        assertEquals(new Position(1), result.timeline().get(3).agents().get(PATROL_ID).position());
        assertEquals(1, result.events().stream().filter(RefueledEvent.class::isInstance).count());
    }

    @Test
    void liveShapeLeavesMaximumMinusActualMovementFuel() {
        DayState state = state(
                new Terrain[] {Terrain.MOUNTAIN, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 2),
                        AgentState.refuel(REFUEL_ID, new Position(1))),
                5,
                Map.of(),
                Map.of());
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(2), new MoveAction(Direction.RIGHT)),
                REFUEL_ID, List.of(new MoveAction(Direction.LEFT), new WaitAction(3))));

        ValidDaySimulationResult result = valid(simulate(state, plan));

        assertEquals(new FiniteFuel(5), result.timeline().get(1).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(3), result.timeline().get(2).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(3), result.timeline().get(3).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(3), result.timeline().get(4).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(3), result.finalAgents().getFirst().fuel());
        assertEquals(1, result.events().stream().filter(RefueledEvent.class::isInstance).count());
    }

    @Test
    void patrolCanBeRefilledAgainWhenItReturnsToRefuelCell() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 0),
                        AgentState.refuel(REFUEL_ID, new Position(0))),
                6,
                Map.of(),
                Map.of());
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(
                        new WaitAction(1),
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.LEFT),
                        new WaitAction(1)),
                REFUEL_ID, List.of(new WaitAction(6))));

        ValidDaySimulationResult result = valid(simulate(state, plan));

        List<RefueledEvent> refuels = result.events().stream()
                .filter(RefueledEvent.class::isInstance)
                .map(RefueledEvent.class::cast)
                .toList();
        assertEquals(List.of(1, 5), refuels.stream().map(RefueledEvent::step).toList());
        assertEquals(new FiniteFuel(4), result.timeline().get(1).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(3), result.timeline().get(3).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(5), result.timeline().get(4).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(5), result.timeline().get(5).agents().get(PATROL_ID).fuel());
        assertEquals(new FiniteFuel(5), result.finalAgents().getFirst().fuel());
    }

    @Test
    void multipleWaitsAndWaitAfterMovementUseExactStepAccounting() {
        DayState state = state(
                new Terrain[] {Terrain.ROAD, Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 5),
                4,
                Map.of(new Position(0), TrafficStatus.CLEAR),
                Map.of());

        ValidDaySimulationResult result = valid(simulate(
                state,
                plan(
                        PATROL_ID,
                        new WaitAction(1),
                        new MoveAction(Direction.RIGHT),
                        new WaitAction(2))));

        assertEquals(new AgentStepUsage(4), result.stepUsage().get(PATROL_ID));
        assertEquals(new Position(1), result.finalAgents().getFirst().position());
        assertEquals(new FiniteFuel(3), result.finalAgents().getFirst().fuel());
        assertEquals(1, result.roadStoppedSteps().get(new Position(0)));
    }

    @Test
    void zeroStockProducesNoCollectionAndRefuelNeverCollects() {
        BrandId brand = new BrandId("brand");
        UdonSpot spot = new UdonSpot(brand, new Position(1), 1);
        DayState state = state(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 5),
                        AgentState.refuel(REFUEL_ID, new Position(0))),
                2,
                Map.of(),
                Map.of(new Position(1), 0),
                List.of(spot));
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new MoveAction(Direction.RIGHT)),
                REFUEL_ID, List.of(new MoveAction(Direction.RIGHT))));

        ValidDaySimulationResult result = valid(simulate(state, plan));

        assertEquals(0, result.portionsCollectedByAgent().get(PATROL_ID));
        assertEquals(0, result.portionsCollectedByAgent().get(REFUEL_ID));
        assertTrue(result.brandsCollected().isEmpty());
        assertEquals(0, result.remainingSpotStock().get(new Position(1)));
    }

    @Test
    void oneRefuelCanRestoreMultiplePatrolsWithoutLosingFuel() {
        AgentId secondPatrol = new AgentId(2);
        DayState state = state(
                new Terrain[] {Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 0),
                        AgentState.refuel(REFUEL_ID, new Position(0)),
                        AgentState.patrol(secondPatrol, new Position(0), 2)),
                2,
                Map.of(),
                Map.of());
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(2)),
                REFUEL_ID, List.of(new WaitAction(2)),
                secondPatrol, List.of(new WaitAction(2))));

        ValidDaySimulationResult result = valid(simulate(state, plan));

        assertEquals(new FiniteFuel(5), result.finalAgents().get(0).fuel());
        assertEquals(UnlimitedFuel.INSTANCE, result.finalAgents().get(1).fuel());
        assertEquals(new FiniteFuel(5), result.finalAgents().get(2).fuel());
        assertEquals(2, result.events().stream().filter(RefueledEvent.class::isInstance).count());
    }

    @Test
    void multipleRefuelAgentsRestoreOnePatrolOncePerDepletion() {
        AgentId secondRefuel = new AgentId(2);
        DayState state = state(
                new Terrain[] {Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 1),
                        AgentState.refuel(REFUEL_ID, new Position(0)),
                        AgentState.refuel(secondRefuel, new Position(0))),
                1,
                Map.of(),
                Map.of());

        ValidDaySimulationResult result = valid(simulate(
                state,
                plans(Map.of(
                        PATROL_ID, List.of(new WaitAction(1)),
                        REFUEL_ID, List.of(new WaitAction(1)),
                        secondRefuel, List.of(new WaitAction(1))))));

        RefueledEvent event = assertInstanceOf(
                RefueledEvent.class,
                result.events().stream().filter(RefueledEvent.class::isInstance).findFirst().orElseThrow());
        assertEquals(new FiniteFuel(5), result.finalAgents().getFirst().fuel());
        assertEquals(List.of(REFUEL_ID, secondRefuel), event.refuelAgents());
        assertEquals(1, result.events().stream().filter(RefueledEvent.class::isInstance).count());
    }

    @Test
    void refuelDirectionalMovementConsumesNoFuel() {
        DayState state = state(
                new Terrain[] {Terrain.MOUNTAIN, Terrain.PLAIN},
                AgentState.refuel(REFUEL_ID, new Position(0)),
                3,
                Map.of(),
                Map.of());

        ValidDaySimulationResult result = valid(simulate(
                state, plan(REFUEL_ID, new MoveAction(Direction.RIGHT))));

        assertEquals(new Position(1), result.finalAgents().getFirst().position());
        assertSame(UnlimitedFuel.INSTANCE, result.finalAgents().getFirst().fuel());
        assertTrue(result.events().stream().noneMatch(FuelConsumedEvent.class::isInstance));
    }

    @Test
    void multiplePatrolsUseSharedStockInAgentIdOrder() {
        AgentId lowerId = new AgentId(1);
        AgentId higherId = new AgentId(2);
        BrandId brand = new BrandId("brand");
        DayState state = state(
                new Terrain[] {Terrain.PLAIN},
                List.of(
                        AgentState.patrol(higherId, new Position(0), 0),
                        AgentState.patrol(lowerId, new Position(0), 0)),
                1,
                Map.of(),
                Map.of(new Position(0), 1),
                List.of(new UdonSpot(brand, new Position(0), 1)));

        ValidDaySimulationResult result = valid(new DaySimulator().simulate(
                state,
                plans(Map.of(
                        lowerId, List.of(new WaitAction(1)),
                        higherId, List.of(new WaitAction(1))))));

        assertEquals(1, result.portionsCollectedByAgent().get(lowerId));
        assertEquals(0, result.portionsCollectedByAgent().get(higherId));
        assertEquals(0, result.remainingSpotStock().get(new Position(0)));
    }

    @Test
    void refuelDoesNotCollectAndFullPatrolDoesNotReceiveDuplicateEvents() {
        BrandId brand = new BrandId("brand");
        DayState state = state(
                new Terrain[] {Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 5),
                        AgentState.refuel(REFUEL_ID, new Position(0))),
                3,
                Map.of(),
                Map.of(new Position(0), 1),
                List.of(new UdonSpot(brand, new Position(0), 1)));

        ValidDaySimulationResult result = valid(new DaySimulator().simulate(
                state,
                plans(Map.of(
                        PATROL_ID, List.of(new WaitAction(3)),
                        REFUEL_ID, List.of(new WaitAction(3))))));

        assertEquals(0, result.portionsCollectedByAgent().get(REFUEL_ID));
        assertEquals(1, result.portionsCollectedByAgent().get(PATROL_ID));
        assertEquals(0, result.events().stream().filter(RefueledEvent.class::isInstance).count());
    }

    @Test
    void validatorDelegatesToSimulatorAndSafeWaitPlanIsValid() {
        DayState state = state(
                new Terrain[] {Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 0),
                3,
                Map.of(),
                Map.of());
        PlanValidator validator = new PlanValidator();

        assertTrue(validator.validate(state, SafePlanFactory.waitAll(state)).valid());
        PlanValidation invalid = validator.validate(
                state, plan(PATROL_ID, new MoveAction(Direction.RIGHT)));
        assertFalse(invalid.valid());
        assertEquals(SimulationFailureCode.NOT_ADJACENT, invalid.failure().orElseThrow().code());
    }

    @Test
    void resultsAndPlansAreImmutableAndInputsRemainUnchanged() {
        List<AgentAction> actions = new ArrayList<>(List.of(new WaitAction(1)));
        Map<AgentId, List<AgentAction>> inputPlans = new HashMap<>();
        inputPlans.put(PATROL_ID, actions);
        TeamPlan plan = new TeamPlan(inputPlans);
        actions.clear();
        inputPlans.clear();

        DayState state = state(
                new Terrain[] {Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 2),
                1,
                Map.of(),
                Map.of());
        ValidDaySimulationResult result = valid(new DaySimulator().simulate(state, plan));

        assertEquals(List.of(new WaitAction(1)), plan.actionsFor(PATROL_ID));
        assertThrows(UnsupportedOperationException.class, () -> plan.actionsFor(PATROL_ID).add(new WaitAction(1)));
        assertThrows(UnsupportedOperationException.class, () -> result.timeline().add(null));
        assertThrows(UnsupportedOperationException.class, () -> result.roadStoppedSteps().put(new Position(0), 1));
        assertNotSame(state.agents(), result.finalAgents());
        assertEquals(new FiniteFuel(2), state.agents().getFirst().fuel());
    }

    @Test
    void dayStateDefensivelyCopiesCallerCollections() {
        List<AgentState> agents = new ArrayList<>(List.of(
                AgentState.patrol(PATROL_ID, new Position(0), 2)));
        Map<Position, TrafficStatus> traffic = new HashMap<>();
        traffic.put(new Position(1), TrafficStatus.CLEAR);
        Map<Position, Integer> stock = new HashMap<>();
        stock.put(new Position(0), 1);
        UdonSpot spot = new UdonSpot(new BrandId("brand"), new Position(0), 1);
        StaticMatchData matchData = new StaticMatchData(
                new HexMap(2, 1, new Terrain[] {Terrain.PLAIN, Terrain.ROAD}),
                new DayStepBudgets(new int[] {2}),
                List.of(new InitialAgent(PATROL_ID, new Position(0))),
                new FuelCapacity(5),
                List.of(spot));
        DayState state = new DayState(matchData, new DayIndex(0), agents, traffic, stock);

        agents.clear();
        traffic.clear();
        stock.clear();

        assertEquals(1, state.agents().size());
        assertEquals(TrafficStatus.CLEAR, state.roadTraffic().get(new Position(1)));
        assertEquals(1, state.spotStock().get(new Position(0)));
        assertThrows(UnsupportedOperationException.class, () -> state.roadTraffic().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.spotStock().clear());
    }

    @Test
    void sameInputProducesDeeplyEqualDeterministicResults() {
        DayState state = state(
                new Terrain[] {Terrain.ROAD, Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 5),
                        AgentState.refuel(REFUEL_ID, new Position(1))),
                3,
                Map.of(new Position(0), TrafficStatus.CLEAR),
                Map.of());
        TeamPlan plan = plans(Map.of(
                PATROL_ID, List.of(new WaitAction(3)),
                REFUEL_ID, List.of(new MoveAction(Direction.LEFT), new WaitAction(1))));

        DaySimulationResult first = new DaySimulator().simulate(state, plan);
        DaySimulationResult second = new DaySimulator().simulate(state, plan);

        assertEquals(first, second);
    }

    @Test
    void dayStateRejectsInvalidCapacityDuplicateIdsTrafficAndStock() {
        assertThrows(IllegalArgumentException.class, () -> state(
                new Terrain[] {Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 6),
                1,
                Map.of(),
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> state(
                new Terrain[] {Terrain.PLAIN},
                List.of(
                        AgentState.patrol(PATROL_ID, new Position(0), 0),
                        AgentState.patrol(PATROL_ID, new Position(0), 0)),
                1,
                Map.of(),
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> state(
                new Terrain[] {Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 0),
                1,
                Map.of(new Position(0), TrafficStatus.CLEAR),
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> state(
                new Terrain[] {Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 0),
                1,
                Map.of(),
                Map.of(new Position(0), 1)));

        UdonSpot spot = new UdonSpot(new BrandId("brand"), new Position(0), 1);
        assertThrows(IllegalArgumentException.class, () -> state(
                new Terrain[] {Terrain.PLAIN},
                AgentState.patrol(PATROL_ID, new Position(0), 0),
                1,
                Map.of(),
                Map.of(new Position(0), 2),
                spot));
    }

    private static Stream<Arguments> officialMoveCases() {
        return Stream.of(
                Arguments.of(Terrain.PLAIN, null, 2, 5, 2, 4),
                Arguments.of(Terrain.MOUNTAIN, null, 3, 5, 3, 3),
                Arguments.of(Terrain.ROAD, TrafficStatus.CLEAR, 1, 5, 1, 3),
                Arguments.of(Terrain.ROAD, TrafficStatus.CONGESTED, 2, 5, 2, 3),
                Arguments.of(Terrain.ROAD, TrafficStatus.JAMMED, 4, 5, 4, 3));
    }

    private static ValidDaySimulationResult valid(DaySimulationResult result) {
        return assertInstanceOf(ValidDaySimulationResult.class, result);
    }

    private static InvalidDaySimulationResult invalid(DaySimulationResult result) {
        return assertInstanceOf(InvalidDaySimulationResult.class, result);
    }

    private static void assertFailure(
            DayState state, TeamPlan plan, SimulationFailureCode expectedCode) {
        InvalidDaySimulationResult result = invalid(new DaySimulator().simulate(state, plan));
        assertEquals(expectedCode, result.failure().code());
        assertFalse(result.valid());
    }

    private static TeamPlan plan(AgentId id, AgentAction... actions) {
        return plans(Map.of(id, List.of(actions)));
    }

    private static TeamPlan plans(Map<AgentId, ? extends List<? extends AgentAction>> plans) {
        return new TeamPlan(plans);
    }

    private static DaySimulationResult simulate(DayState state, TeamPlan plan) {
        return new DaySimulator().simulate(state, plan);
    }

    private static DayState state(
            Terrain[] terrain,
            AgentState agent,
            int steps,
            Map<Position, TrafficStatus> traffic,
            Map<Position, Integer> stock,
            UdonSpot... spots) {
        return state(terrain, List.of(agent), steps, traffic, stock, List.of(spots));
    }

    private static DayState state(
            Terrain[] terrain,
            AgentState agent,
            int steps,
            Map<Position, TrafficStatus> traffic,
            Map<Position, Integer> stock,
            List<UdonSpot> spots) {
        return state(terrain, List.of(agent), steps, traffic, stock, spots);
    }

    private static DayState state(
            Terrain[] terrain,
            List<AgentState> agents,
            int steps,
            Map<Position, TrafficStatus> traffic,
            Map<Position, Integer> stock) {
        return state(terrain, agents, steps, traffic, stock, List.of());
    }

    private static DayState state(
            Terrain[] terrain,
            List<AgentState> agents,
            int steps,
            Map<Position, TrafficStatus> traffic,
            Map<Position, Integer> stock,
            List<UdonSpot> spots) {
        List<InitialAgent> initialAgents = agents.stream()
                .map(agent -> new InitialAgent(agent.id(), agent.position()))
                .toList();
        StaticMatchData staticData = new StaticMatchData(
                new HexMap(terrain.length, 1, terrain),
                new DayStepBudgets(new int[] {steps}),
                initialAgents,
                new FuelCapacity(5),
                spots);
        return new DayState(staticData, new DayIndex(0), agents, traffic, stock);
    }
}
