package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
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
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

class ContentionAttributionTest {

    @Test
    void finalReturnedPlanAttributionCountsSafeTiedAndContestedCollections() {
        List<UdonSpot> spots = List.of(spot("A", 1), spot("B", 2), spot("C", 3));
        DayState state = state(spots, List.of(group(1, new ObservedOtherAgent(p(4), 1, 60))));
        TeamPlan plan = new TeamPlan(Map.of(
                new AgentId(0), List.of(
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.RIGHT),
                        new MoveAction(Direction.RIGHT))));

        ContentionAttribution attribution = ContentionAttribution.fromSimulation(
                state,
                new DaySimulator().simulate(state, plan),
                new ContentionAnalyzer());

        assertEquals(new ContentionAttribution(1, 1, 1, 0, 1), attribution);
    }

    @Test
    void unobservedFinalCollectionsAreExplicitlyAttributed() {
        List<UdonSpot> spots = List.of(spot("A", 1));
        DayState state = state(spots, List.of());
        TeamPlan plan = new TeamPlan(Map.of(
                new AgentId(0), List.of(new MoveAction(Direction.RIGHT), new WaitAction(4))));

        ContentionAttribution attribution = ContentionAttribution.fromSimulation(
                state,
                new DaySimulator().simulate(state, plan),
                new ContentionAnalyzer());

        assertEquals(new ContentionAttribution(0, 0, 0, 1, 0), attribution);
    }

    private static DayState state(List<UdonSpot> spots, List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[5];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(5, 1, terrain),
                new DayStepBudgets(new int[] {6}),
                List.of(), new FuelCapacity(20), spots);
        return new DayState(match, new DayIndex(0),
                List.of(AgentState.patrol(new AgentId(0), p(0), 20)),
                Map.of(), stock, others);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), p(position), 1);
    }

    private static ObservedOtherGroup group(int rawId, ObservedOtherAgent... agents) {
        return new ObservedOtherGroup(rawId, List.of(agents));
    }

    private static Position p(int value) {
        return new Position(value);
    }
}