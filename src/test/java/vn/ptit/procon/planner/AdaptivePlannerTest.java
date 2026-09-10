package vn.ptit.procon.planner;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.model.Model;
import vn.ptit.procon.simulation.ExactSimulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AdaptivePlannerTest {
    @Test void producesExactValidPlanAndUsesStockCapacity() {
        int[][] cells = new int[3][8];
        Model.Setup setup = new Model.Setup(new Model.MapData(8, 3, cells),
                List.of(new Model.Spot(0, "A", 2, 3), new Model.Spot(1, "B", 5, 3)),
                new int[]{0, 0, 0, 0, 0, 0}, new int[]{30}, 60);
        AdaptivePlanner planner = new AdaptivePlanner(setup);
        int[] roles = planner.chooseAssignment();
        List<Model.AgentState> agents = new ArrayList<>();
        for (int role : roles) agents.add(new Model.AgentState(Model.AgentKind.fromCode(role), 0, 60));
        Model.DayState state = new Model.DayState(0, agents, List.of());
        Model.PlannedDay plan = planner.plan(state, Deadline.afterMillis(500));
        ExactSimulator.SimulationResult result = new ExactSimulator().simulate(setup, state, plan.actions());
        assertTrue(result.portions() > 0);
        assertTrue(result.portions() <= 6);
        assertEquals(6, plan.actions().length);
        for (int[] actions : plan.actions()) assertFalse(actions.length == 0);
    }

    @Test void locksAllDailyBrandsBeforeTheStockAuction() {
        int[][] cells = new int[3][4];
        Model.Setup setup = new Model.Setup(new Model.MapData(4, 3, cells),
                List.of(
                        new Model.Spot(0, "A", 0, 1),
                        new Model.Spot(1, "B", 3, 1),
                        new Model.Spot(2, "C", 8, 1),
                        new Model.Spot(3, "D", 11, 1)),
                new int[]{5, 5, 5, 5, 5, 5}, new int[]{30}, 60);
        AdaptivePlanner planner = new AdaptivePlanner(setup);
        List<Model.AgentState> agents = new ArrayList<>();
        for (int role : planner.chooseAssignment()) {
            agents.add(new Model.AgentState(Model.AgentKind.fromCode(role), 5, 60));
        }
        Model.DayState state = new Model.DayState(0, agents, List.of());
        Model.PlannedDay plan = planner.plan(state, Deadline.afterMillis(500));
        ExactSimulator.SimulationResult result = new ExactSimulator().simulate(setup, state, plan.actions());

        assertEquals(Set.of("A", "B", "C", "D"), result.brands());
    }

    @Test void reusesPatrolsForCoverageWhenThereAreMoreBrandsThanAgents() {
        // Practice allows up to 32 franchises while a match can have as few as one Patrol.
        // Four Patrols must be allowed to take a second coverage target; otherwise daily types
        // are capped at four even on this trivial, fully reachable board.
        int[][] cells = new int[1][8];
        Model.Setup setup = new Model.Setup(new Model.MapData(8, 1, cells),
                List.of(
                        new Model.Spot(0, "A", 1, 1), new Model.Spot(1, "B", 2, 1),
                        new Model.Spot(2, "C", 3, 1), new Model.Spot(3, "D", 4, 1),
                        new Model.Spot(4, "E", 5, 1), new Model.Spot(5, "F", 6, 1)),
                new int[]{0, 0, 0, 0}, new int[]{30}, 60);
        Model.DayState state = new Model.DayState(0, List.of(
                new Model.AgentState(Model.AgentKind.PATROL, 0, 60),
                new Model.AgentState(Model.AgentKind.PATROL, 0, 60),
                new Model.AgentState(Model.AgentKind.PATROL, 0, 60),
                new Model.AgentState(Model.AgentKind.PATROL, 0, 60)), List.of());

        Model.PlannedDay plan = new AdaptivePlanner(setup).plan(state, Deadline.afterMillis(500));
        ExactSimulator.SimulationResult result = new ExactSimulator().simulate(setup, state, plan.actions());

        assertEquals(Set.of("A", "B", "C", "D", "E", "F"), result.brands());
    }

    @Test void bouncesOutAndBackToHarvestSpotOccupiedAtDayStart() {
        int[][] cells = new int[1][3];
        Model.Setup setup = new Model.Setup(new Model.MapData(3, 1, cells),
                List.of(new Model.Spot(0, "A", 1, 1)), new int[]{1}, new int[]{8}, 60);
        AdaptivePlanner planner = new AdaptivePlanner(setup);
        Model.DayState state = new Model.DayState(0,
                List.of(new Model.AgentState(Model.AgentKind.PATROL, 1, 60)), List.of());

        Model.PlannedDay plan = planner.plan(state, Deadline.afterMillis(200));
        ExactSimulator.SimulationResult result = new ExactSimulator().simulate(setup, state, plan.actions());

        assertEquals(1, result.portions());
        assertEquals("A", result.brands().iterator().next());
    }

    @Test void fiveOneModeReservesExactlyOneTanker() {
        int[][] cells = new int[2][4];
        Model.Setup setup = new Model.Setup(new Model.MapData(4, 2, cells),
                List.of(new Model.Spot(0, "A", 2, 3)), new int[]{0, 1, 4, 5, 6, 7}, new int[]{30}, 60);
        AdaptivePlanner planner = new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.COVERAGE_FIRST,
                AdaptivePlanner.RoleMode.FIVE_ONE);

        int[] roles = planner.chooseAssignment();

        assertEquals(1, java.util.Arrays.stream(roles).filter(role -> role == Model.AgentKind.REFUEL.code()).count());
        assertEquals(5, java.util.Arrays.stream(roles).filter(role -> role == Model.AgentKind.PATROL.code()).count());
    }

    @Test void autoAssignmentKeepsAllPatrolsWhenOneDayFitsTheFuelBudget() {
        Model.Setup setup = new Model.Setup(new Model.MapData(8, 8, new int[8][8]),
                List.of(
                        new Model.Spot(0, "A", 1, 1), new Model.Spot(1, "B", 10, 1),
                        new Model.Spot(2, "C", 20, 1), new Model.Spot(3, "D", 30, 1)),
                new int[]{0, 7, 56, 63, 24, 39}, new int[]{30, 30, 30, 30}, 60);

        int[] roles = new AdaptivePlanner(setup).chooseAssignment();

        assertEquals(0, java.util.Arrays.stream(roles)
                .filter(role -> role == Model.AgentKind.REFUEL.code()).count());
    }

    @Test void autoAssignmentReservesOneTankerWhenFuelCannotCarryIntoTheNextDay() {
        Model.Setup setup = new Model.Setup(new Model.MapData(8, 8, new int[8][8]),
                List.of(
                        new Model.Spot(0, "A", 1, 1), new Model.Spot(1, "B", 10, 1),
                        new Model.Spot(2, "C", 20, 1), new Model.Spot(3, "D", 30, 1)),
                new int[]{0, 7, 56, 63, 24, 39}, new int[]{30, 30, 30, 30}, 30);

        int[] roles = new AdaptivePlanner(setup).chooseAssignment();

        assertTrue(AdaptivePlanner.requiresLowFuelTanker(setup));
        assertEquals(1, java.util.Arrays.stream(roles)
                .filter(role -> role == Model.AgentKind.REFUEL.code()).count());
    }

    @Test void fourAgentLowFuelShapeAlsoReservesOneTanker() {
        Model.Setup setup = new Model.Setup(new Model.MapData(8, 8, new int[8][8]),
                List.of(new Model.Spot(0, "A", 1, 1), new Model.Spot(1, "B", 10, 1)),
                new int[]{0, 7, 56, 63}, new int[]{30, 30, 30, 30}, 30);

        int[] roles = new AdaptivePlanner(setup).chooseAssignment();

        assertTrue(AdaptivePlanner.requiresLowFuelTanker(setup));
        assertEquals(1, java.util.Arrays.stream(roles)
                .filter(role -> role == Model.AgentKind.REFUEL.code()).count());
    }
}
