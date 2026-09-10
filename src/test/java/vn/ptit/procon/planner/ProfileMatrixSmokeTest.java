package vn.ptit.procon.planner;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.model.Model;
import vn.ptit.procon.simulation.ExactSimulator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deterministic safety net for shape combinations that cannot all be held out live before a
 * competition.  It does not claim a win-rate; it guarantees that the production portfolio picks
 * a profile, produces a non-empty legal plan, and reaches harvest on each supported board tier.
 */
class ProfileMatrixSmokeTest {
    @Test void producesValidHarvestPlansAcrossCanonicalAndUnusualShapes() {
        List<ProfileCase> matrix = List.of(
                new ProfileCase(8, 4, 30, 60, 6),
                new ProfileCase(8, 6, 30, 60, 4),
                new ProfileCase(12, 3, 60, 50, 6),
                new ProfileCase(16, 8, 60, 80, 6),
                new ProfileCase(24, 8, 100, 100, 6),
                new ProfileCase(32, 8, 100, 200, 6));

        for (ProfileCase profileCase : matrix) {
            Model.Setup setup = setup(profileCase);
            PortfolioPlanner planner = new PortfolioPlanner(setup);
            int[] roles = planner.chooseAssignment();
            Model.DayState state = new Model.DayState(0, agents(setup, roles), traffic(profileCase.size()));
            long budget = profileCase.size() >= 24 ? 1_000 : 450;

            Model.PlannedDay plan = planner.plan(state, Deadline.afterMillis(budget));
            ExactSimulator.SimulationResult replay = assertDoesNotThrow(
                    () -> new ExactSimulator().simulate(setup, state, plan.actions()),
                    () -> "invalid plan for " + profileCase);

            assertEquals(profileCase.agents(), plan.actions().length, () -> "wrong action count for " + profileCase);
            assertTrue(replay.portions() > 0, () -> "no reachable harvest for " + profileCase);
        }
    }

    private Model.Setup setup(ProfileCase profileCase) {
        int size = profileCase.size();
        int[][] cells = new int[size][size];
        // Roads and mountains exercise the two fuel/time trade-offs without blocking the fixture.
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if ((row + col) % 17 == 0) cells[row][col] = Model.Terrain.ROAD.code();
                else if ((row * 3 + col) % 29 == 0) cells[row][col] = Model.Terrain.MOUNTAIN.code();
            }
        }
        List<Model.Spot> spots = new ArrayList<>();
        int spotCount = Math.min(12, (size - 2) * 2);
        for (int index = 0; index < spotCount; index++) {
            int row = 1 + index / (size - 2);
            int col = 1 + index % (size - 2);
            spots.add(new Model.Spot(index, "B" + (index % profileCase.brands()), row * size + col,
                    1 + index % 3));
        }
        int[] starts = new int[profileCase.agents()];
        for (int agent = 0; agent < starts.length; agent++) {
            int row = size / 2 + agent % 2;
            int col = (agent * 3 + 1) % size;
            starts[agent] = row * size + col;
        }
        return new Model.Setup(new Model.MapData(size, size, cells), spots, starts,
                new int[]{profileCase.steps()}, profileCase.fuel());
    }

    private List<Model.AgentState> agents(Model.Setup setup, int[] roles) {
        List<Model.AgentState> agents = new ArrayList<>();
        for (int index = 0; index < roles.length; index++) {
            agents.add(new Model.AgentState(Model.AgentKind.fromCode(roles[index]),
                    setup.startPositions()[index], setup.fuelLimit()));
        }
        return agents;
    }

    private List<Model.TrafficCell> traffic(int size) {
        return List.of(new Model.TrafficCell(size + 1, Model.Traffic.CONGESTED),
                new Model.TrafficCell(size * 2 + 2, Model.Traffic.JAMMED));
    }

    private record ProfileCase(int size, int agents, int steps, int fuel, int brands) {}
}
