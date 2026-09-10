package vn.ptit.procon.planner;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.model.Model;
import vn.ptit.procon.simulation.ExactSimulator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedAlnsImproverTest {
    @Test void repairCanReplaceAShortRouteWithAHighStockHarvestSuffix() {
        Model.Setup setup = new Model.Setup(new Model.MapData(5, 1, new int[1][5]),
                List.of(new Model.Spot(0, "A", 1, 1), new Model.Spot(1, "B", 4, 3)),
                new int[]{0}, new int[]{20}, 60);
        Model.DayState state = new Model.DayState(0,
                List.of(new Model.AgentState(Model.AgentKind.PATROL, 0, 60)), List.of());
        int[][] incumbent = {{2, -18}}; // collect only A, then leave all of B's stock behind
        ExactSimulator simulator = new ExactSimulator();
        int baseline = simulator.simulate(setup, state, incumbent).portions();

        List<BoundedAlnsImprover.Candidate> repairs = new BoundedAlnsImprover().generate(
                setup, state, incumbent, Deadline.afterMillis(100));

        assertFalse(repairs.isEmpty());
        assertTrue(repairs.stream().anyMatch(candidate ->
                simulator.simulate(setup, state, candidate.actions()).portions() > baseline));
    }

    @Test void p08SixAgentShapeUsesBoundedExactRouteDpForItsRepairSuffix() {
        Model.Setup setup = new Model.Setup(new Model.MapData(8, 8, new int[8][8]),
                List.of(new Model.Spot(0, "A", 1, 1), new Model.Spot(1, "B", 2, 3),
                        new Model.Spot(2, "C", 3, 2)),
                new int[]{0, 0, 0, 0, 0, 0}, new int[]{30, 30, 30, 30}, 60);
        List<Model.AgentState> agents = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> new Model.AgentState(Model.AgentKind.PATROL, 0, 60)).toList();
        Model.DayState state = new Model.DayState(0, agents, List.of());
        int[][] incumbent = {{2, -28}, {2, -28}, {2, -28}, {2, -28}, {2, -28}, {2, -28}};
        ExactSimulator simulator = new ExactSimulator();
        int baseline = simulator.simulate(setup, state, incumbent).portions();

        List<BoundedAlnsImprover.Candidate> repairs = new BoundedAlnsImprover().generate(
                setup, state, incumbent, Deadline.afterMillis(150));

        assertTrue(repairs.stream().anyMatch(candidate -> candidate.operator().startsWith("EXACT_DP_")));
        assertTrue(repairs.stream().anyMatch(candidate ->
                simulator.simulate(setup, state, candidate.actions()).portions() > baseline));
    }

    @Test void p08FourAgentFinalDayUsesItsValidatedExactRouteDp() {
        Model.Setup setup = new Model.Setup(new Model.MapData(8, 8, new int[8][8]),
                List.of(new Model.Spot(0, "A", 1, 1), new Model.Spot(1, "B", 2, 3),
                        new Model.Spot(2, "C", 3, 2)),
                new int[]{0, 0, 0, 0}, new int[]{30, 30, 30, 30}, 60);
        List<Model.AgentState> agents = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> new Model.AgentState(Model.AgentKind.PATROL, 0, 60)).toList();
        Model.DayState state = new Model.DayState(3, agents, List.of());
        int[][] incumbent = {{2, -28}, {2, -28}, {2, -28}, {2, -28}};

        List<BoundedAlnsImprover.Candidate> repairs = new BoundedAlnsImprover().generate(
                setup, state, incumbent, Deadline.afterMillis(150));

        assertTrue(repairs.stream().anyMatch(candidate -> candidate.operator().startsWith("EXACT_DP_")));
    }
}
