package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;

class ContentionAwareAnytimePlannerTest {

    private static final AnytimePlannerConfig BUDGET = new AnytimePlannerConfig(2, 8, 1);

    @Test
    void noOpponentDataIsExactlyEquivalentToHarvestForPlanAndStats() {
        DayState state = state(
                7,
                6,
                List.of(spot("A", 2), spot("A", 5)),
                List.of());

        AnytimePlanResult harvest = new HarvestAnytimeTeamPlanner(BUDGET).planWithStats(state);
        AnytimePlanResult contention = new ContentionAwareAnytimePlanner(BUDGET).planWithStats(state);

        assertEquals(harvest.plan().actionsByAgent(), contention.plan().actionsByAgent());
        assertEquals(harvest.evaluation(), contention.evaluation());
        assertEquals(harvest.stats(), contention.stats());
    }

    @Test
    void opponentCloserToNearSpotMakesGuidanceExploreTheSaferSpot() {
        DayState state = state(
                12,
                9,
                List.of(spot("A", 4), spot("A", 3)),
                List.of(group(1, agent(5))));

        ContentionAnalyzer analyzer = new ContentionAnalyzer();
        ContentionMetrics near = analyzer.analyze(state, p(4));
        ContentionMetrics safer = analyzer.analyze(state, p(3));

        assertTrue(safer.distanceAdvantage().orElseThrow()
                > near.distanceAdvantage().orElseThrow());
    }

    @Test
    void sameBudgetPreservesBrandValueAndChoosesBetterContentionThanHarvest() {
        DayState state = gridState(
                List.of(spot("A", 1), spot("A", 5)),
                List.of(group(1, agent(1))));

        AnytimePlanResult harvest = new HarvestAnytimeTeamPlanner(BUDGET).planWithStats(state);
        AnytimePlanResult contention = new ContentionAwareAnytimePlanner(BUDGET).planWithStats(state);
        ContentionAnalyzer analyzer = new ContentionAnalyzer();

        assertEquals(harvest.evaluation().teamBrandCount(), contention.evaluation().teamBrandCount());
        assertEquals(harvest.evaluation().udonTotal(), contention.evaluation().udonTotal());
        Position harvestPosition = finalPosition(harvest, squareMap(3), p(4));
        Position contentionPosition = finalPosition(contention, squareMap(3), p(4));
        int harvestAdvantage = analyzer.analyze(state, harvestPosition)
                .distanceAdvantage().orElseThrow();
        int contentionAdvantage = analyzer.analyze(state, contentionPosition)
                .distanceAdvantage().orElseThrow();
        assertTrue(contentionAdvantage > harvestAdvantage);
    }

    @Test
    void identicalStateProducesIdenticalPlanAndSearchStatistics() {
        DayState state = state(
                10,
                9,
                List.of(spot("A", 4), spot("A", 5)),
                List.of(group(1, agent(3))));
        ContentionAwareAnytimePlanner planner = new ContentionAwareAnytimePlanner(BUDGET);

        AnytimePlanResult first = planner.planWithStats(state);
        AnytimePlanResult second = planner.planWithStats(state);

        assertEquals(first.plan().actionsByAgent(), second.plan().actionsByAgent());
        assertEquals(first.evaluation(), second.evaluation());
        assertEquals(first.stats(), second.stats());
    }

    @Test
    void coverageGuidanceRanksMissingBrandBeforeSaferCoveredGain() {
        ContentionCandidateMetrics missing = metrics(true, 1, 0, 0, 1, 1, 4);
        ContentionCandidateMetrics covered = metrics(false, 3, 3, 0, 0, 0, 2);

        assertTrue(ContentionCandidateMetrics.coveragePreference().compare(missing, covered) < 0);
    }

    @Test
    void harvestGuidanceRanksTwoSafeBeforeThreeStronglyContested() {
        ContentionCandidateMetrics contested = metrics(false, 3, 0, 0, 3, 3, 4);
        ContentionCandidateMetrics safe = metrics(false, 2, 2, 0, 0, 0, 4);

        assertTrue(ContentionCandidateMetrics.harvestPreference().compare(safe, contested) < 0);
    }

    @Test
    void moreThanKCandidatesRetainsAndExpandsALowContentionBranch() {
        DayState state = gridState(
                List.of(spot("A", 1), spot("A", 3), spot("A", 5)),
                List.of(group(1, agent(1))));

        AnytimePlanResult result = new ContentionAwareAnytimePlanner(BUDGET).planWithStats(state);
        Position selected = finalPosition(result, squareMap(3), p(4));

        assertTrue(result.stats().candidateGenerated() >= 3);
        assertTrue(result.stats().candidatePrunedByTopK() >= 2);
        assertEquals(2, result.stats().expandedStates());
        assertEquals(ContentionClassification.SAFE,
                new ContentionAnalyzer().analyze(state, selected).classification());
    }

    @Test
    void doneAttributesFallbackReturnedPlanEvenWithoutIncumbentReplacement() {
        DayState state = state(
                3,
                2,
                List.of(spot("A", 1)),
                List.of(group(1, agent(1))));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new ContentionAwareAnytimePlanner(new AnytimePlannerConfig(0, 4, 1))
                    .planWithStats(state);
        } finally {
            System.setOut(original);
        }

        String logs = captured.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("ANYTIME_CONTENTION_DONE"));
        assertTrue(logs.contains("improvements=0"));
        assertTrue(logs.contains("safeProjected=0 tiedProjected=0 contestedProjected=1"));
    }

    private static ContentionCandidateMetrics metrics(
            boolean newBrand,
            int gain,
            int safe,
            int tied,
            int contested,
            int stronglyContested,
            int steps) {
        return new ContentionCandidateMetrics(
                newBrand, gain, safe, tied, contested, stronglyContested,
                steps, steps / 2, 10, p(steps), new AgentId(0));
    }

    private static DayState state(
            int width,
            int budget,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(width, 1, terrain),
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new FuelCapacity(20),
                spots);
        return new DayState(
                match,
                new DayIndex(0),
                List.of(AgentState.patrol(new AgentId(0), p(0), 20)),
                Map.of(),
                stock,
                others);
    }

    private static DayState gridState(
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[9];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(3, 3, terrain),
                new DayStepBudgets(new int[] {2}),
                List.of(),
                new FuelCapacity(20),
                spots);
        return new DayState(
                match,
                new DayIndex(0),
                List.of(AgentState.patrol(new AgentId(0), p(4), 20)),
                Map.of(), stock, others);
    }

    private static Position finalPosition(AnytimePlanResult result) {
        return finalPosition(result, lineMap(10), p(0));
    }

    private static Position finalPosition(
            AnytimePlanResult result, HexMap map, Position start) {
        Position cursor = start;
        for (var action : result.plan().actionsFor(new AgentId(0))) {
            if (action instanceof vn.ptit.procon.domain.action.MoveAction move) {
                cursor = map.neighbor(cursor, move.direction()).orElseThrow();
            }
        }
        return cursor;
    }

    private static HexMap lineMap(int width) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        return new HexMap(width, 1, terrain);
    }

    private static HexMap squareMap(int size) {
        Terrain[] terrain = new Terrain[size * size];
        Arrays.fill(terrain, Terrain.PLAIN);
        return new HexMap(size, size, terrain);
    }

    private static ObservedOtherGroup group(int rawId, ObservedOtherAgent... agents) {
        return new ObservedOtherGroup(rawId, List.of(agents));
    }

    private static ObservedOtherAgent agent(int position) {
        return new ObservedOtherAgent(p(position), 1, 60);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), p(position), 1);
    }

    private static Position p(int value) {
        return new Position(value);
    }
}