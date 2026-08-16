package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
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
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.AgentStepUsage;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.MoveStartedEvent;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

class HarvestAnytimeTeamPlannerTest {

    private static final AgentId PATROL_0 = new AgentId(0);
    private static final AgentId PATROL_1 = new AgentId(1);
    private static final AgentId REFUEL = new AgentId(2);
    private static final AnytimePlannerConfig SAME_BUDGET = new AnytimePlannerConfig(64, 48, 4);

    @Test
    void densityUsesDeterministicIntegerCrossMultiplication() {
        HarvestCandidateMetrics oneInSix = metrics(1, 6, false, 1, 0);
        HarvestCandidateMetrics threeInEight = metrics(3, 8, false, 2, 0);

        assertTrue(HarvestCandidateMetrics.densityPreference()
                .compare(threeInEight, oneInSix) < 0);
        assertTrue(HarvestCandidateMetrics.densityPreference()
                .compare(oneInSix, threeInEight) > 0);
    }

    @Test
    void totalGainPrecedesDensityDuringHarvest() {
        HarvestCandidateMetrics denseTwo = metrics(2, 2, false, 1, 0);
        HarvestCandidateMetrics largerFive = metrics(5, 6, false, 2, 0);

        assertTrue(HarvestCandidateMetrics.harvestPreference()
                .compare(largerFive, denseTwo) < 0);
    }

    @Test
    void coverageComesFirstAndImmediatelyDropsOutOfHarvestOrdering() {
        HarvestCandidateMetrics missingBrand = metrics(1, 6, true, 1, 0);
        HarvestCandidateMetrics coveredHarvest = metrics(4, 8, false, 2, 0);

        assertTrue(HarvestCandidateMetrics.coveragePreference()
                .compare(missingBrand, coveredHarvest) < 0);
        assertTrue(HarvestCandidateMetrics.harvestPreference()
                .compare(coveredHarvest, missingBrand) < 0);
    }

    @Test
    void searchTransitionsFromCoverageToHarvestAfterLastFeasibleBrand() {
        DayState state = lineState(
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN},
                6,
                6,
                List.of(AgentState.patrol(PATROL_0, position(0), 6)),
                List.of(spot("A", 0), spot("B", 1), spot("A", 2), spot("A", 3)),
                Map.of());

        AnytimePlanResult result = new HarvestAnytimeTeamPlanner(SAME_BUDGET).planWithStats(state);

        assertTrue(result.stats().coveragePhaseExpandedStates() > 0);
        assertTrue(result.stats().harvestPhaseExpandedStates() > 0);
        assertEquals(2, result.evaluation().teamBrandCount());
        assertEquals(4, result.evaluation().udonTotal());
    }

    @Test
    void sameBudgetHarvestGuidanceBeatsPatrolBrandDistractionAndTopKPruning() {
        DayState state = patrolBrandDistractionState();
        TeamPlan m7Plan = new TeamCoordinatorPlanner().plan(state);
        AnytimePlanResult m8 = new AnytimeTeamPlanner(SAME_BUDGET).planWithStats(state);
        AnytimePlanResult harvest = new HarvestAnytimeTeamPlanner(SAME_BUDGET).planWithStats(state);
        Comparison m7 = comparison(state, m7Plan);
        Comparison original = comparison(state, m8.plan());
        Comparison improved = comparison(state, harvest.plan());

        assertEquals(new Comparison(2, 3, 6, 17, true), m7);
        assertEquals(m7, original);
        assertEquals(new Comparison(2, 5, 8, 16, true), improved);
        assertTrue(harvest.stats().candidatePrunedByTopK() > 0);
        assertTrue(harvest.evaluation().betterThan(m8.evaluation()));
    }

    @Test
    void routeWideGainCountsPassThroughCollections() {
        DayState state = lineState(
                plain(5),
                8,
                8,
                List.of(AgentState.patrol(PATROL_0, position(0), 8)),
                List.of(spot("A", 1), spot("A", 2), spot("A", 4)),
                Map.of());

        AnytimePlanResult result = new HarvestAnytimeTeamPlanner(SAME_BUDGET).planWithStats(state);
        ValidDaySimulationResult simulation = simulate(state, result.plan());

        assertEquals(3, result.evaluation().udonTotal());
        assertEquals(3, totalUdon(simulation));
        assertEquals(0, simulation.remainingSpotStock().get(position(1)));
        assertEquals(0, simulation.remainingSpotStock().get(position(2)));
        assertEquals(0, simulation.remainingSpotStock().get(position(4)));
    }

    @Test
    void projectedStockIsRegeneratedPerBranchAndSharedAcrossPatrols() {
        DayState state = lineState(
                plain(3),
                2,
                4,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 4),
                        AgentState.patrol(PATROL_1, position(2), 4)),
                List.of(spot("shared", 1)),
                Map.of());

        AnytimePlanResult result = new HarvestAnytimeTeamPlanner(SAME_BUDGET).planWithStats(state);
        ValidDaySimulationResult simulation = simulate(state, result.plan());

        assertEquals(1, totalUdon(simulation));
        assertEquals(0, simulation.remainingSpotStock().get(position(1)));
        assertTrue(result.stats().generatedStates() >= 3);
    }

    @Test
    void weightedFuelStepsTrafficAndPondSafetyRemainAuthoritative() {
        Terrain[] terrain = {Terrain.ROAD, Terrain.PLAIN, Terrain.POND, Terrain.PLAIN};
        DayState state = lineState(
                terrain,
                1,
                2,
                List.of(AgentState.patrol(PATROL_0, position(0), 2)),
                List.of(spot("near", 1), spot("blocked", 3)),
                Map.of(position(0), TrafficStatus.CLEAR));

        AnytimePlanResult result = new HarvestAnytimeTeamPlanner(SAME_BUDGET).planWithStats(state);
        ValidDaySimulationResult simulation = simulate(state, result.plan());

        assertEquals(position(1), finalAgent(simulation, PATROL_0).position());
        assertEquals(new FiniteFuel(0), finalAgent(simulation, PATROL_0).fuel());
        assertEquals(new AgentStepUsage(1), simulation.stepUsage().get(PATROL_0));
        assertEquals(1, simulation.remainingSpotStock().get(position(3)));
    }

    @Test
    void returnedPlanNeverRegressesItsValidatedM7Incumbent() {
        DayState state = patrolBrandDistractionState();
        AnytimePlanResult incumbent = new AnytimeTeamPlanner(
                new AnytimePlannerConfig(0, 48, 4)).planWithStats(state);
        AnytimePlanResult harvest = new HarvestAnytimeTeamPlanner(SAME_BUDGET).planWithStats(state);

        assertFalse(incumbent.evaluation().betterThan(harvest.evaluation()));
        assertTrue(new PlanValidator().validate(state, harvest.plan()).valid());
    }

    @Test
    void usefulRefuelRootRemainsSimulatorBackedAndValidated() {
        DayState state = lineState(
                plain(4),
                6,
                5,
                List.of(
                        AgentState.patrol(PATROL_0, position(0), 0),
                        AgentState.refuel(REFUEL, position(1))),
                List.of(spot("start", 0), spot("harvest", 2)),
                Map.of());

        AnytimePlanResult result = new HarvestAnytimeTeamPlanner(SAME_BUDGET).planWithStats(state);
        ValidDaySimulationResult simulation = simulate(state, result.plan());

        assertTrue(simulation.events().stream().anyMatch(RefueledEvent.class::isInstance));
        assertTrue(new PlanValidator().validate(state, result.plan()).valid());
    }

    private static HarvestCandidateMetrics metrics(
            int gain, int steps, boolean newTeamBrand, int target, int agent) {
        return new HarvestCandidateMetrics(
                gain, steps, Math.min(steps, 3), 10,
                newTeamBrand, position(target), new AgentId(agent));
    }

    private static DayState patrolBrandDistractionState() {
        int width = 9;
        int height = 9;
        Terrain[] terrain = plain(width * height);
        HexMap map = new HexMap(width, height, terrain);
        Position center = map.positionOf(4, 4);
        Position left = map.neighbor(center, Direction.LEFT).orElseThrow();
        List<UdonSpot> spots = new ArrayList<>();
        spots.add(new UdonSpot(new BrandId("A"), center, 1));
        spots.add(new UdonSpot(new BrandId("B"), left, 1));
        for (Direction direction : List.of(
                Direction.UP_LEFT,
                Direction.UP_RIGHT,
                Direction.DOWN_LEFT,
                Direction.DOWN_RIGHT)) {
            spots.add(new UdonSpot(new BrandId("B"), walk(map, center, direction, 3), 1));
        }
        spots.add(new UdonSpot(new BrandId("A"), walk(map, center, Direction.RIGHT, 1), 1));
        spots.add(new UdonSpot(new BrandId("A"), walk(map, center, Direction.RIGHT, 2), 1));
        spots.add(new UdonSpot(new BrandId("A"), walk(map, center, Direction.RIGHT, 4), 1));
        return state(
                terrain,
                width,
                height,
                8,
                20,
                List.of(
                        AgentState.patrol(PATROL_0, center, 20),
                        AgentState.patrol(PATROL_1, left, 0)),
                spots,
                Map.of());
    }

    private static Position walk(
            HexMap map, Position start, Direction direction, int distance) {
        Position cursor = start;
        for (int step = 0; step < distance; step++) {
            cursor = map.neighbor(cursor, direction).orElseThrow();
        }
        return cursor;
    }

    private static Comparison comparison(DayState state, TeamPlan plan) {
        ValidDaySimulationResult result = simulate(state, plan);
        int movementSteps = result.events().stream()
                .filter(MoveStartedEvent.class::isInstance)
                .map(MoveStartedEvent.class::cast)
                .mapToInt(MoveStartedEvent::duration)
                .sum();
        int fuel = result.finalAgents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL)
                .map(AgentState::fuel)
                .map(FiniteFuel.class::cast)
                .mapToInt(FiniteFuel::amount)
                .sum();
        return new Comparison(
                result.brandsCollected().size(), totalUdon(result), movementSteps, fuel, true);
    }

    private static ValidDaySimulationResult simulate(DayState state, TeamPlan plan) {
        assertTrue(new PlanValidator().validate(state, plan).valid());
        return assertInstanceOf(
                ValidDaySimulationResult.class,
                new DaySimulator().simulate(state, plan));
    }

    private static int totalUdon(ValidDaySimulationResult result) {
        return result.portionsCollectedByAgent().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    private static AgentState finalAgent(ValidDaySimulationResult result, AgentId id) {
        return result.finalAgents().stream()
                .filter(agent -> agent.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static DayState lineState(
            Terrain[] terrain,
            int budget,
            int capacity,
            List<AgentState> agents,
            List<UdonSpot> spots,
            Map<Position, TrafficStatus> traffic) {
        return state(terrain, terrain.length, 1, budget, capacity, agents, spots, traffic);
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
        Map<Position, Integer> stock = new LinkedHashMap<>();
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
                agents,
                traffic,
                stock);
    }

    private static Terrain[] plain(int size) {
        Terrain[] terrain = new Terrain[size];
        Arrays.fill(terrain, Terrain.PLAIN);
        return terrain;
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), position(position), 1);
    }

    private static Position position(int value) {
        return new Position(value);
    }

    private record Comparison(
            int brands,
            int udon,
            int movementSteps,
            int remainingFuel,
            boolean valid) {
    }
}
