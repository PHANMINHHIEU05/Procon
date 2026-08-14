package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.engine.DayState;

class RefuelRouteFinderTest {

    private final RefuelRouteFinder routeFinder = new RefuelRouteFinder();

    @Test
    void weightedSearchPrefersMoreEdgesWithLowerTotalSteps() {
        DayState state = state(
                new Terrain[] {
                    Terrain.ROAD, Terrain.ROAD, Terrain.PLAIN, Terrain.PLAIN,
                    Terrain.ROAD, Terrain.MOUNTAIN, Terrain.PLAIN, Terrain.PLAIN
                },
                4,
                2,
                20,
                AgentState.refuel(new AgentId(3), new Position(4)),
                traffic(0, 1, 4));

        Route route = routeFinder.find(
                state, state.agents().getFirst(), new Position(6)).orElseThrow();

        assertEquals(List.of(Direction.UP_RIGHT, Direction.RIGHT, Direction.DOWN_RIGHT),
                route.directions());
        assertEquals(3, route.stepsUsed());
        assertEquals(0, route.fuelUsed());
    }

    @Test
    void sourceCellAndAuthoritativeRoadTrafficDetermineCostDeterministically() {
        for (TrafficStatus status : TrafficStatus.values()) {
            DayState state = state(
                    new Terrain[] {Terrain.ROAD, Terrain.PLAIN},
                    2,
                    1,
                    10,
                    AgentState.refuel(new AgentId(1), new Position(0)),
                    Map.of(new Position(0), status));

            Route first = routeFinder.find(
                    state, state.agents().getFirst(), new Position(1)).orElseThrow();
            Route second = routeFinder.find(
                    state, state.agents().getFirst(), new Position(1)).orElseThrow();

            int expected = status == TrafficStatus.CLEAR
                    ? 1
                    : status == TrafficStatus.CONGESTED ? 2 : 4;
            assertEquals(expected, first.stepsUsed());
            assertEquals(first, second);
        }

        DayState mountain = state(
                new Terrain[] {Terrain.MOUNTAIN, Terrain.PLAIN},
                2,
                1,
                10,
                AgentState.refuel(new AgentId(1), new Position(0)),
                Map.of());
        assertEquals(3, routeFinder.find(
                mountain, mountain.agents().getFirst(), new Position(1)).orElseThrow().stepsUsed());
    }

    @Test
    void pondIsNeverEnteredAndMissingRoadTrafficIsNotInvented() {
        DayState pond = state(
                new Terrain[] {Terrain.PLAIN, Terrain.POND, Terrain.PLAIN},
                3,
                1,
                10,
                AgentState.refuel(new AgentId(1), new Position(0)),
                Map.of());
        DayState missingTraffic = state(
                new Terrain[] {Terrain.ROAD, Terrain.PLAIN},
                2,
                1,
                10,
                AgentState.refuel(new AgentId(1), new Position(0)),
                Map.of());

        assertTrue(routeFinder.find(
                pond, pond.agents().getFirst(), new Position(2)).isEmpty());
        assertTrue(routeFinder.find(
                missingTraffic, missingTraffic.agents().getFirst(), new Position(1)).isEmpty());
    }

    private static Map<Position, TrafficStatus> traffic(int... positions) {
        Map<Position, TrafficStatus> result = new HashMap<>();
        for (int position : positions) {
            result.put(new Position(position), TrafficStatus.CLEAR);
        }
        return result;
    }

    private static DayState state(
            Terrain[] terrains,
            int width,
            int height,
            int budget,
            AgentState refuel,
            Map<Position, TrafficStatus> traffic) {
        StaticMatchData matchData = new StaticMatchData(
                new HexMap(width, height, terrains),
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new FuelCapacity(5),
                List.of());
        return new DayState(
                matchData, new DayIndex(0), List.of(refuel), traffic, Map.of());
    }
}
