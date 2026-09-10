package vn.ptit.procon.planner;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.model.Model;
import vn.ptit.procon.simulation.ExactSimulator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioPlannerTest {
    @Test void finalDayFullHarvestReturnsAValidOfficialMaximum() {
        int[][] cells = new int[1][3];
        Model.Setup setup = new Model.Setup(new Model.MapData(3, 1, cells),
                List.of(new Model.Spot(0, "A", 1, 1), new Model.Spot(1, "B", 2, 1)),
                new int[]{0, 0}, new int[]{12}, 60);
        PortfolioPlanner planner = new PortfolioPlanner(setup);
        int[] roles = planner.chooseAssignment();
        Model.DayState state = new Model.DayState(0, List.of(
                new Model.AgentState(Model.AgentKind.fromCode(roles[0]), 0, 60),
                new Model.AgentState(Model.AgentKind.fromCode(roles[1]), 0, 60)), List.of());

        Model.PlannedDay plan = planner.plan(state, Deadline.afterMillis(500));
        ExactSimulator.SimulationResult replay = new ExactSimulator().simulate(setup, state, plan.actions());

        assertEquals(2, replay.portions());
    }

    @Test void canonicalP24EnablesTheValidatedFuelReserveArmOnlyForItsProvenShape() {
        Model.Setup canonical = p24Setup(24, 8, new int[]{100, 100, 100, 100}, 200);
        PortfolioPlanner promoted = new PortfolioPlanner(canonical);

        assertTrue(AdaptivePlanner.usesP24FuelPacingDefaults(canonical));
        assertTrue(promoted.includesArm(AdaptivePlanner.Heuristic.FUEL_PACED));
        assertTrue(promoted.includesArm(AdaptivePlanner.Heuristic.CAPACITY_DENSITY));
        assertEquals(40, AdaptivePlanner.defaultFuelReserve(canonical, promoted.profile()));

        Model.Setup generic = p24Setup(24, 6, new int[]{100, 100, 100, 100}, 200);
        assertFalse(AdaptivePlanner.usesP24FuelPacingDefaults(generic));
        assertFalse(new PortfolioPlanner(generic).includesArm(AdaptivePlanner.Heuristic.FUEL_PACED));
        assertFalse(new PortfolioPlanner(generic).includesArm(AdaptivePlanner.Heuristic.CAPACITY_DENSITY));
    }

    @Test void p24LongHorizonTankerIsExactlyShapeScoped() {
        Model.Setup proven = p24Setup(24, 6, new int[]{80, 80, 80, 80, 80}, 160);
        Model.Setup canonical = p24Setup(24, 8, new int[]{100, 100, 100, 100}, 200);
        Model.Setup wrongFuel = p24Setup(24, 6, new int[]{80, 80, 80, 80, 80}, 240);

        assertTrue(AdaptivePlanner.usesP24LongHorizonTankerDefaults(proven));
        assertFalse(AdaptivePlanner.usesP24LongHorizonTankerDefaults(canonical));
        assertFalse(AdaptivePlanner.usesP24LongHorizonTankerDefaults(wrongFuel));
    }

    @Test void p32EnablesItsValidatedCoveragePortfolioArmWithoutChangingP24Policy() {
        Model.Setup p32 = p24Setup(32, 8, new int[]{100, 100, 100, 100}, 200);

        assertTrue(new PortfolioPlanner(p32).includesArm(AdaptivePlanner.Heuristic.COVERAGE_FIRST));
    }

    @Test void canonicalProfilesGetOnlyTheirValidatedCapacityBonus() {
        Model.Setup p16 = p24Setup(16, 6, new int[]{60, 60, 60, 60}, 120);
        Model.Setup p12 = p24Setup(12, 6, new int[]{60, 60, 60, 60}, 120);
        Model.Setup p32 = p24Setup(32, 8, new int[]{100, 100, 100, 100}, 200);

        assertEquals(25, AdaptivePlanner.defaultCapacitySlotBonus(p16, AdaptiveR3Policy.select(MatchShape.of(p16))));
        assertEquals(25, AdaptivePlanner.defaultCapacitySlotBonus(p12, AdaptiveR3Policy.select(MatchShape.of(p12))));
        assertEquals(25, AdaptivePlanner.defaultCapacitySlotBonus(p32, AdaptiveR3Policy.select(MatchShape.of(p32))));
    }

    private Model.Setup p24Setup(int size, int agents, int[] daySteps, int fuelLimit) {
        int[][] cells = new int[size][size];
        int[] starts = new int[agents];
        for (int agent = 0; agent < agents; agent++) starts[agent] = agent;
        return new Model.Setup(new Model.MapData(size, size, cells),
                List.of(new Model.Spot(0, "A", size + 1, 1)), starts, daySteps, fuelLimit);
    }

}
