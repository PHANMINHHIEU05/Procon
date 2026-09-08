package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
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
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/**
 * Pins the subset DP's expansion schedule on a fixture where the Pareto front is genuinely wide.
 *
 * <p>A ROAD source costs {@code (1 step, 2 fuel)} per move under the optimistic CLEAR assumption while a
 * PLAIN source costs {@code (2 steps, 1 fuel)}, so a detour trades steps against fuel and a DP key really
 * does hold several incomparable labels. That matters because the two possible schedules — expanding each
 * accepted label once, or re-expanding a key's whole label set on every dequeue — are indistinguishable
 * when every key holds exactly one label.
 *
 * <p>Both schedules were measured on this fixture. They agree on every decision field and disagree only on
 * the work counter:
 *
 * <pre>
 *   label-once  dpStatesEvaluated = 115 / 87   (total 202)
 *   re-expand   dpStatesEvaluated = 691 / 485  (total 1176)
 *   both        maxSpots=4 maxBrands=4 bestRemainingFuel=8 minSpots=4 totalSpots=8 totalBrands=8
 * </pre>
 *
 * <p>So the exact counter is the only assertion that can catch a return to the quadratic schedule, and the
 * decision fields are what prove such a change would be behaviour preserving.
 */
class NextDayHarvestCapacityLabelOnceDpTest {

    private static final int WIDTH = 12;

    @Test
    @DisplayName("WIDE_PARETO_FRONT_EXPANDS_EVERY_LABEL_ONCE")
    void WIDE_PARETO_FRONT_EXPANDS_EVERY_LABEL_ONCE() {
        TeamNextDayHarvestCapacity team =
                capacity(roadOverPlainState(List.of(AgentState.patrol(id(0), pos(0), 20))));
        PatrolNextDayHarvestCapacity patrol = team.patrols().getFirst();

        assertEquals(24, team.nextDayStepBudget());
        assertEquals(20, patrol.projectedEndFuel());
        assertEquals(4, patrol.maxReachableDistinctSpots(), "all four spots fit 24 steps and 20 fuel");
        assertEquals(4, patrol.maxReachableDistinctBrands());
        assertEquals(8, patrol.bestRemainingFuelAtMaxSpotCount());
        assertEquals(115, patrol.dpStatesEvaluated(),
                "one expansion per accepted Pareto label; re-expanding whole label sets costs 691");
    }

    @Test
    @DisplayName("TEAM_COUNTER_IS_THE_SUM_OF_ITS_PATROLS")
    void TEAM_COUNTER_IS_THE_SUM_OF_ITS_PATROLS() {
        TeamNextDayHarvestCapacity team = capacity(roadOverPlainState(List.of(
                AgentState.patrol(id(0), pos(0), 20),
                AgentState.patrol(id(1), pos(WIDTH), 20))));

        assertEquals(2, team.patrols().size());
        assertEquals(List.of(115, 87), team.patrols().stream()
                .map(PatrolNextDayHarvestCapacity::dpStatesEvaluated).toList(),
                "the PLAIN-row patrol reaches the same four spots with fewer labels");
        assertEquals(202, team.totalDpStatesEvaluated());
        assertEquals(4, team.minimumPatrolDistinctSpots());
        assertEquals(8, team.totalPatrolDistinctSpotCapacity());
        assertEquals(8, team.totalPatrolDistinctBrandCapacity());
    }

    private static TeamNextDayHarvestCapacity capacity(DayState state) {
        ValidDaySimulationResult valid = (ValidDaySimulationResult) new DaySimulator()
                .simulate(state, SafePlanFactory.waitAll(state));
        return NextDayHarvestCapacityCalculator.forState(state).evaluate(valid);
    }

    /** Row 0 is ROAD (fast, thirsty), row 1 is PLAIN (slow, frugal, and where spots must sit). */
    private static DayState roadOverPlainState(List<AgentState> agents) {
        Terrain[] terrain = new Terrain[WIDTH * 2];
        Arrays.fill(terrain, Terrain.PLAIN);
        for (int column = 0; column < WIDTH; column++) {
            terrain[column] = Terrain.ROAD;
        }
        List<UdonSpot> spots = new ArrayList<>(List.of(spot("A", WIDTH + 2), spot("B", WIDTH + 5),
                spot("C", WIDTH + 8), spot("D", WIDTH + 11)));
        Map<Position, Integer> stock = new HashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        return new DayState(new StaticMatchData(
                new HexMap(WIDTH, 2, terrain), new DayStepBudgets(new int[] {6, 24}), List.of(),
                new FuelCapacity(20), spots), new DayIndex(0), agents, Map.of(), stock);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), pos(position), 1);
    }

    private static AgentId id(int value) {
        return new AgentId(value);
    }

    private static Position pos(int value) {
        return new Position(value);
    }
}
