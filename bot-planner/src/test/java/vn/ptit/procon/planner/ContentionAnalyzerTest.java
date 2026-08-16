package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.Direction;
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

class ContentionAnalyzerTest {

    private final ContentionAnalyzer analyzer = new ContentionAnalyzer();

    @Test
    void evenRDistanceCoversSameCellEveryNeighborAndMultipleRows() {
        HexMap map = map(5, 5);
        for (Position center : List.of(map.positionOf(2, 2), map.positionOf(1, 2))) {
            assertEquals(0, analyzer.hexDistance(map, center, center));
            for (Direction direction : Direction.values()) {
                Position neighbor = map.neighbor(center, direction).orElseThrow();
                assertEquals(1, analyzer.hexDistance(map, center, neighbor));
                assertEquals(1, analyzer.hexDistance(map, neighbor, center));
            }
        }
        assertEquals(4, analyzer.hexDistance(
                map, map.positionOf(0, 0), map.positionOf(4, 2)));
        assertEquals(6, analyzer.hexDistance(
                map, map.positionOf(0, 4), map.positionOf(4, 0)));
    }

    @Test
    void computesAcrossAllGroupsAndIncludesRawKindOneByDefault() {
        DayState state = state(
                9, 9,
                List.of(AgentState.patrol(new AgentId(0), p(0), 20)),
                List.of(spot("A", 40)),
                List.of(
                        group(9, agent(80, 0)),
                        group(1, agent(41, 1)),
                        group(4, agent(31, 0))));

        ContentionMetrics metrics = analyzer.analyze(state, p(40));

        assertEquals(OptionalInt.of(6), metrics.ourNearestHexDistance());
        assertEquals(OptionalInt.of(1), metrics.otherNearestHexDistance());
        assertEquals(2, metrics.otherAgentsWithinRadius1());
        assertEquals(2, metrics.otherAgentsWithinRadius2());
        assertEquals(OptionalInt.of(-5), metrics.distanceAdvantage());
        assertEquals(ContentionClassification.CONTESTED, metrics.classification());
    }

    @Test
    void exactOpponentCloserFixtureGuidanceFavorsTheGeometricallySaferSpot() {
        HexMap map = map(15, 15);
        Position ours = map.positionOf(7, 7);
        Position contested = map.positionOf(7, 11);
        Position safer = map.positionOf(7, 2);
        Position other = map.positionOf(7, 10);
        DayState state = state(
                map,
                List.of(AgentState.patrol(new AgentId(0), ours, 60)),
                List.of(
                        new UdonSpot(new BrandId("same"), contested, 1),
                        new UdonSpot(new BrandId("same"), safer, 1)),
                List.of(group(1, new ObservedOtherAgent(other, 1, 60))));

        ContentionMetrics spotA = analyzer.analyze(state, contested);
        ContentionMetrics spotB = analyzer.analyze(state, safer);

        assertEquals(OptionalInt.of(4), spotA.ourNearestHexDistance());
        assertEquals(OptionalInt.of(1), spotA.otherNearestHexDistance());
        assertEquals(OptionalInt.of(5), spotB.ourNearestHexDistance());
        assertEquals(OptionalInt.of(8), spotB.otherNearestHexDistance());
        assertEquals(ContentionClassification.CONTESTED, spotA.classification());
        assertEquals(ContentionClassification.SAFE, spotB.classification());

        ContentionCandidateMetrics candidateA = candidate(contested, 0, 1);
        ContentionCandidateMetrics candidateB = candidate(safer, 1, 0);
        assertTrue(ContentionCandidateMetrics.harvestPreference()
                .compare(candidateB, candidateA) < 0);
    }

    @Test
    void classifiesCloserTieAndUnobservedWithoutMagicDistances() {
        DayState closer = state(
                7, 1,
                List.of(AgentState.patrol(new AgentId(0), p(0), 10)),
                List.of(spot("A", 1), spot("B", 3)),
                List.of(group(1, agent(6, 0))));
        assertEquals(ContentionClassification.SAFE, analyzer.analyze(closer, p(1)).classification());
        assertEquals(ContentionClassification.TIED, analyzer.analyze(closer, p(3)).classification());

        DayState unobserved = state(
                2, 1,
                List.of(AgentState.patrol(new AgentId(0), p(0), 10)),
                List.of(spot("A", 1)),
                List.of());
        ContentionMetrics metrics = analyzer.analyze(unobserved, p(1));
        assertTrue(metrics.otherNearestHexDistance().isEmpty());
        assertTrue(metrics.distanceAdvantage().isEmpty());
        assertEquals(ContentionClassification.UNOBSERVED, metrics.classification());
    }

    @Test
    void routeUsesUniquePassThroughSpotsAndBranchLocalStock() {
        List<UdonSpot> spots = List.of(spot("A", 1), spot("B", 2), spot("C", 3));
        DayState state = state(
                5, 1,
                List.of(AgentState.patrol(new AgentId(0), p(0), 10)),
                spots,
                List.of(group(1, agent(4, 0))));
        Route route = new Route(
                p(0), p(3), List.of(Direction.RIGHT, Direction.RIGHT, Direction.RIGHT), 6, 3);
        Map<Position, UdonSpot> byPosition = new LinkedHashMap<>();
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> {
            byPosition.put(spot.position(), spot);
            stock.put(spot.position(), 1);
        });

        RouteContentionMetrics all = analyzer.analyzeRoute(
                state, route, stock, Set.of(), byPosition);
        assertEquals(3, all.projectedCollectionGain());
        assertEquals(1, all.safeProjectedCollections());
        assertEquals(1, all.tiedProjectedCollections());
        assertEquals(1, all.contestedProjectedCollections());

        stock.put(p(1), 0);
        RouteContentionMetrics afterConsumed = analyzer.analyzeRoute(
                state, route, stock, Set.of(), byPosition);
        assertEquals(2, afterConsumed.projectedCollectionGain());
        assertEquals(0, afterConsumed.safeProjectedCollections());
        assertEquals(1, afterConsumed.tiedProjectedCollections());
        assertEquals(1, afterConsumed.contestedProjectedCollections());
    }

    @Test
    void everySpotCanBeTiedAndSummaryDistinguishesTiedFromZero() {
        DayState state = state(
                5, 1,
                List.of(AgentState.patrol(new AgentId(0), p(0), 10)),
                List.of(spot("A", 1), spot("B", 2)),
                List.of(group(1, agent(0, 0))));

        assertEquals(ContentionClassification.TIED,
                analyzer.analyze(state, p(1)).classification());
        assertEquals(ContentionClassification.TIED,
                analyzer.analyze(state, p(2)).classification());
        assertEquals(new SpotContentionSummary(2, 0, 2, 0, 0),
                analyzer.summarizeSpots(state));
    }

    @Test
    void noOpponentDataSummarizesEverySpotAsUnobserved() {
        DayState state = state(
                3, 1,
                List.of(AgentState.patrol(new AgentId(0), p(0), 10)),
                List.of(spot("A", 1), spot("B", 2)),
                List.of());

        assertEquals(new SpotContentionSummary(2, 0, 0, 0, 2),
                analyzer.summarizeSpots(state));
    }

    private static DayState state(
            int width,
            int height,
            List<AgentState> ours,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        HexMap map = map(width, height);
        return state(map, ours, spots, others);
    }

    private static DayState state(
            HexMap map,
            List<AgentState> ours,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                map, new DayStepBudgets(new int[] {30}), List.of(), new FuelCapacity(60), spots);
        return new DayState(match, new DayIndex(0), ours, Map.of(), stock, others);
    }

    private static ContentionCandidateMetrics candidate(
            Position target, int safe, int contested) {
        return new ContentionCandidateMetrics(
                false, 1, safe, 0, contested, contested,
                10, 5, 55, target, new AgentId(0));
    }

    private static HexMap map(int width, int height) {
        Terrain[] terrain = new Terrain[width * height];
        Arrays.fill(terrain, Terrain.PLAIN);
        return new HexMap(width, height, terrain);
    }

    private static ObservedOtherGroup group(int rawId, ObservedOtherAgent... agents) {
        return new ObservedOtherGroup(rawId, List.of(agents));
    }

    private static ObservedOtherAgent agent(int position, int rawKind) {
        return new ObservedOtherAgent(p(position), rawKind, 60);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), p(position), 1);
    }

    private static Position p(int value) {
        return new Position(value);
    }
}