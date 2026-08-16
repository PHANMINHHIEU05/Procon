package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
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
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;

class OpponentWeightedArrivalLowerBoundTest {

    @Test
    void weightedTerrainDifferenceSeparatesOldRiskFromNewSafe() {
        DayState state = state(
                5,
                1,
                new Terrain[] {Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN},
                1,
                List.of(spot(4)),
                List.of(group(1, agent(0))),
                Map.of());
        OpponentWeightedArrivalLowerBound lowerBound = new OpponentWeightedArrivalLowerBound();

        assertEquals(OptionalInt.of(8), lowerBound.shortestTravelSteps(
                state.matchData().map(), new Position(0), new Position(4)));
        assertEquals(3, new ContentionAnalyzer().hexDistance(
                state.matchData().map(), new Position(1), new Position(4)));
        assertEquals(6, new WeightedRouteFinder().find(
                state, state.agents().getFirst(), new Position(4)).orElseThrow().stepsUsed());
        assertEquals(4, new ContentionAnalyzer().hexDistance(
                state.matchData().map(), new Position(0), new Position(4)));
        assertEquals(ArrivalContentionClassification.ARRIVAL_AT_RISK,
                new ContentionAnalyzer().analyzeArrival(new Position(4), 6, OptionalInt.of(4)).classification());
        assertEquals(ArrivalContentionClassification.ARRIVAL_SAFE,
                new ContentionAnalyzer().analyzeArrival(new Position(4), 6, OptionalInt.of(8)).classification());
    }

    @Test
    void jammedRoadUsesOptimisticClearRoadDuration() {
        DayState state = state(
                3,
                1,
                new Terrain[] {Terrain.ROAD, Terrain.ROAD, Terrain.PLAIN},
                2,
                List.of(spot(2)),
                List.of(group(1, agent(0))),
                Map.of(new Position(0), TrafficStatus.JAMMED));

        assertEquals(OptionalInt.of(2), new OpponentWeightedArrivalLowerBound()
                .shortestTravelSteps(state.matchData().map(), new Position(0), new Position(2)));
    }

    @Test
    void pondIsExcludedAndSearchRoutesAroundIt() {
        DayState state = state(
                3,
                2,
                new Terrain[] {
                    Terrain.PLAIN, Terrain.POND, Terrain.PLAIN,
                    Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN},
                3,
                List.of(spot(2)),
                List.of(group(1, agent(0))),
                Map.of());

        assertEquals(OptionalInt.of(6), new OpponentWeightedArrivalLowerBound()
                .shortestTravelSteps(state.matchData().map(), new Position(0), new Position(2)));
        assertTrue(new OpponentWeightedArrivalLowerBound()
                .shortestTravelSteps(state.matchData().map(), new Position(1), new Position(2)).isEmpty());
    }

    @Test
    void edgeCostUsesSourceTerrain() {
        DayState state = state(
                2,
                1,
                new Terrain[] {Terrain.MOUNTAIN, Terrain.PLAIN},
                1,
                List.of(spot(1)),
                List.of(group(1, agent(0))),
                Map.of());

        assertEquals(OptionalInt.of(3), new OpponentWeightedArrivalLowerBound()
                .shortestTravelSteps(state.matchData().map(), new Position(0), new Position(1)));
    }

    @Test
    void minimumAcrossAllGroupsAndRawKindsIsOrderIndependent() {
        List<ObservedOtherGroup> first = List.of(
                group(20, new ObservedOtherAgent(new Position(4), 9, 1)),
                group(3, new ObservedOtherAgent(new Position(0), 0, 60)));
        List<ObservedOtherGroup> second = List.of(first.get(0), first.get(1));
        DayState stateA = state(5, 1, plain(5), 0, List.of(spot(2)), first, Map.of());
        DayState stateB = state(5, 1, plain(5), 0, List.of(spot(2)), second.reversed(), Map.of());

        OpponentWeightedArrivalLowerBound lowerBound = new OpponentWeightedArrivalLowerBound();
        assertEquals(lowerBound.lowerBounds(stateA), lowerBound.lowerBounds(stateB));
        assertEquals(OptionalInt.of(4), lowerBound.lowerBounds(stateA).get(new Position(2)));
    }

    @Test
    void opponentStartingOnUdonHasZeroLowerBound() {
        DayState state = state(
                2,
                1,
                plain(2),
                0,
                List.of(spot(1)),
                List.of(group(1, agent(1))),
                Map.of());

        assertEquals(OptionalInt.of(0), new OpponentWeightedArrivalLowerBound()
                .lowerBounds(state).get(new Position(1)));
        assertEquals(ArrivalContentionClassification.ARRIVAL_AT_RISK,
                new ContentionAnalyzer().analyzeArrival(new Position(1), 2, OptionalInt.of(0)).classification());
    }

    @Test
    void mixedTerrainProducesMoreThanOneClassificationWhereHexModelSaturatesRisk() {
        DayState state = state(
                4,
                2,
                new Terrain[] {
                    Terrain.PLAIN, Terrain.PLAIN, Terrain.MOUNTAIN, Terrain.PLAIN,
                    Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN, Terrain.PLAIN},
                4,
                List.of(spot(1), spot(3), spot(6)),
                List.of(group(1, agent(0))),
                Map.of());
        ContentionAnalyzer analyzer = new ContentionAnalyzer();
        OpponentWeightedArrivalLowerBound lowerBound = new OpponentWeightedArrivalLowerBound();
        var classifications = state.matchData().udonSpots().stream()
                .map(spot -> {
                    int ourStep = analyzer.hexDistance(state.matchData().map(), new Position(4), spot.position()) * 2;
                    return analyzer.analyzeArrival(
                            spot.position(),
                            ourStep,
                            lowerBound.lowerBounds(state).get(spot.position())).classification();
                })
                .distinct()
                .toList();

        assertTrue(state.matchData().udonSpots().stream().allMatch(spot -> {
            int ourStep = analyzer.hexDistance(state.matchData().map(), new Position(4), spot.position()) * 2;
            int oldBound = analyzer.hexDistance(state.matchData().map(), new Position(0), spot.position());
            return analyzer.analyzeArrival(
                    spot.position(), ourStep, OptionalInt.of(oldBound)).classification()
                    == ArrivalContentionClassification.ARRIVAL_AT_RISK;
        }));
        assertTrue(classifications.size() >= 2);
    }

    private static Terrain[] plain(int count) {
        Terrain[] terrain = new Terrain[count];
        Arrays.fill(terrain, Terrain.PLAIN);
        return terrain;
    }

    private static DayState state(
            int width,
            int height,
            Terrain[] terrain,
            int patrolPosition,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others,
            Map<Position, TrafficStatus> traffic) {
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(width, height, terrain),
                new DayStepBudgets(new int[] {Math.max(1, width * height * 3)}),
                List.of(),
                new FuelCapacity(40),
                spots);
        return new DayState(
                match,
                new DayIndex(0),
                List.of(AgentState.patrol(new AgentId(0), new Position(patrolPosition), 40)),
                traffic,
                stock,
                others);
    }

    private static UdonSpot spot(int position) {
        return new UdonSpot(new BrandId("A"), new Position(position), 1);
    }

    private static ObservedOtherGroup group(int rawId, ObservedOtherAgent... agents) {
        return new ObservedOtherGroup(rawId, List.of(agents));
    }

    private static ObservedOtherAgent agent(int position) {
        return new ObservedOtherAgent(new Position(position), 7, 60);
    }
}