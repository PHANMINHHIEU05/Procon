package vn.ptit.procon.planner;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.model.Model;
import vn.ptit.procon.simulation.ExactSimulator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeMapHarvestTest {
    @Test void largeMapDoesNotStopAfterTheSixBrandCoverageAssignments() {
        Model.Setup setup = new Model.Setup(new Model.MapData(24, 24, new int[24][24]),
                List.of(
                        new Model.Spot(0, "A", 276, 2), new Model.Spot(1, "B", 277, 2),
                        new Model.Spot(2, "C", 300, 2), new Model.Spot(3, "D", 301, 2),
                        new Model.Spot(4, "E", 324, 2), new Model.Spot(5, "F", 325, 2),
                        new Model.Spot(6, "A", 302, 2), new Model.Spot(7, "B", 326, 2),
                        new Model.Spot(8, "C", 275, 2), new Model.Spot(9, "D", 299, 2)),
                new int[]{288, 289, 290, 291, 312, 313, 314, 315}, new int[]{100, 100, 100, 100}, 200);
        List<Model.AgentState> agents = new ArrayList<>();
        for (int start : setup.startPositions()) agents.add(new Model.AgentState(Model.AgentKind.PATROL, start, 200));
        Model.DayState state = new Model.DayState(0, agents, List.of());

        Model.PlannedDay plan = new PortfolioPlanner(setup).plan(state, Deadline.afterMillis(1_000));
        int portions = new ExactSimulator().simulate(setup, state, plan.actions()).portions();

        assertTrue(portions > 6, "large-map harvest must continue after six-brand coverage");
    }
}
