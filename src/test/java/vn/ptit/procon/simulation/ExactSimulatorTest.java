package vn.ptit.procon.simulation;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.model.Model;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExactSimulatorTest {

    @Test void exposesPerSpotClaimsForPlannerStockAccounting() {
        int[][] cells = new int[1][4];
        Model.Setup setup = new Model.Setup(new Model.MapData(4, 1, cells),
                List.of(new Model.Spot(0, "A", 1, 1), new Model.Spot(1, "B", 2, 1)),
                new int[]{0}, new int[]{12}, 60);
        Model.DayState state = new Model.DayState(0,
                List.of(new Model.AgentState(Model.AgentKind.PATROL, 0, 60)), List.of());

        // Moving to B passes through A: both must be collected despite A not being the target.
        ExactSimulator.SimulationResult result = new ExactSimulator().simulate(setup, state, new int[][]{{2, 2, -8}});
        assertArrayEquals(new int[]{1, 1}, result.claimsBySpot());
        assertTrue(result.visitedSpots()[0][0]);
        assertTrue(result.visitedSpots()[0][1]);
    }
    private Model.Setup setup(int stock) {
        return new Model.Setup(
                new Model.MapData(3, 1, new int[][]{{0,0,0}}),
                List.of(new Model.Spot(0, "A", 1, stock)),
                new int[]{0,0}, new int[]{10}, 20);
    }

    @Test void doesNotCollectAtStepZero() {
        Model.Setup setup = setup(2);
        Model.DayState state = new Model.DayState(0, List.of(
                new Model.AgentState(Model.AgentKind.PATROL, 1, 20),
                new Model.AgentState(Model.AgentKind.PATROL, 0, 20)), List.of());
        ExactSimulator.SimulationResult result = new ExactSimulator().simulate(setup, state,
                new int[][]{{-10}, {-10}});
        assertEquals(0, result.portions());
    }

    @Test void stockAllowsTwoPatrolsButEachPatrolOnlyOnce() {
        Model.Setup setup = setup(2);
        Model.DayState state = new Model.DayState(0, List.of(
                new Model.AgentState(Model.AgentKind.PATROL, 0, 20),
                new Model.AgentState(Model.AgentKind.PATROL, 0, 20)), List.of());
        ExactSimulator.SimulationResult result = new ExactSimulator().simulate(setup, state,
                new int[][]{{2}, {2}});
        assertEquals(2, result.portions());
        assertEquals(1, result.brands().size());
    }

    @Test void refuelOccursWhenPatrolArrivesMidDay() {
        Model.Setup setup = new Model.Setup(new Model.MapData(3, 1, new int[][]{{0, 0, 0}}),
                List.of(), new int[]{0, 1}, new int[]{4}, 2);
        Model.DayState state = new Model.DayState(0, List.of(
                new Model.AgentState(Model.AgentKind.PATROL, 0, 1),
                new Model.AgentState(Model.AgentKind.REFUEL, 1, 2)), List.of());
        ExactSimulator.SimulationResult result = new ExactSimulator().simulate(setup, state,
                new int[][]{{2, 2}, {-4}});
        assertEquals(2, result.positions()[0]);
        assertEquals(1, result.fuel()[0], "the second move must spend fuel after the rendezvous");
    }
}
