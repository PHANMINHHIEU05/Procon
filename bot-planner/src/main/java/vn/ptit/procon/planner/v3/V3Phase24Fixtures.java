package vn.ptit.procon.planner.v3;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * Benchmark-only single source of truth for the Phase 2.3/2.4 offline fixtures.
 *
 * <p>The offline benchmark and the offline diagnostic tests must provably run on the same
 * {@link DayState}; duplicating the literals in two modules would let a typo invalidate every
 * causal conclusion. Nothing here is reachable from the runtime.
 */
public final class V3Phase24Fixtures {
    private static final AgentId PATROL_0 = new AgentId(0);
    private static final AgentId PATROL_1 = new AgentId(1);
    private static final AgentId PATROL_2 = new AgentId(2);
    private static final AgentId REFUEL_0 = new AgentId(9);

    private V3Phase24Fixtures() {
    }

    /** CURRENT LARGE, the Phase 2.4 blocker fixture. */
    public static DayState currentLarge() {
        return state(60, 12, 12, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 0),
                AgentState.patrol(PATROL_1, new Position(23), 2),
                AgentState.patrol(PATROL_2, new Position(48), 1),
                AgentState.patrol(new AgentId(3), new Position(95), 0),
                AgentState.patrol(new AgentId(4), new Position(143), 2),
                AgentState.refuel(REFUEL_0, new Position(72))), List.of(
                spot("A", 2, 2), spot("B", 14, 1), spot("C", 27, 1), spot("D", 39, 1),
                spot("A", 52, 1), spot("B", 65, 2), spot("C", 78, 1), spot("D", 91, 1),
                spot("A", 104, 1), spot("B", 117, 1), spot("C", 130, 1), spot("D", 141, 1)), List.of());
    }

    /** Certified 5x5 fixture with two observed opponents. */
    public static DayState rawKindZero5x5() {
        List<ObservedOtherGroup> others = List.of(new ObservedOtherGroup(41, List.of(
                new ObservedOtherAgent(new Position(2), 0, 0),
                new ObservedOtherAgent(new Position(17), 0, 0))));
        return state(30, 5, 5, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8),
                AgentState.patrol(PATROL_1, new Position(12), 8),
                AgentState.patrol(PATROL_2, new Position(24), 8)), List.of(
                spot("A", 1, 1), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1),
                spot("A", 11, 1), spot("B", 13, 1), spot("C", 16, 1), spot("D", 18, 1)), others);
    }

    /** LIVE_LIKE fixture reproduced from match m-6861. */
    public static DayState liveLike() {
        List<ObservedOtherGroup> others = List.of(new ObservedOtherGroup(51, List.of(
                new ObservedOtherAgent(new Position(2), 0, 0),
                new ObservedOtherAgent(new Position(17), 0, 0))));
        return state(30, 5, 5, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 4),
                AgentState.patrol(PATROL_1, new Position(12), 4),
                AgentState.patrol(PATROL_2, new Position(24), 4),
                AgentState.refuel(REFUEL_0, new Position(12))), List.of(
                spot("A", 1, 2), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1),
                spot("A", 11, 1), spot("B", 13, 2), spot("C", 16, 1), spot("D", 18, 1)), others);
    }

    /** MEDIUM_REGION_RELOCATION. */
    public static DayState mediumRegionRelocation() {
        return state(36, 12, 3, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(11), 8),
                AgentState.patrol(PATROL_2, new Position(24), 8)), List.of(
                spot("A", 1, 1), spot("B", 10, 1), spot("C", 13, 1), spot("D", 22, 1), spot("A", 25, 1)), List.of());
    }

    /** MEDIUM_SHARED_STOCK. */
    public static DayState mediumSharedStock() {
        return state(30, 10, 2, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(9), 8),
                AgentState.patrol(PATROL_2, new Position(10), 8)), List.of(
                spot("A", 2, 2), spot("B", 12, 2), spot("C", 17, 1), spot("D", 19, 1)), List.of());
    }

    /** MEDIUM_SUPPORT_CHAIN. */
    public static DayState mediumSupportChain() {
        return state(30, 12, 2, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(11), 8),
                AgentState.patrol(PATROL_2, new Position(12), 8), AgentState.refuel(REFUEL_0, new Position(6))), List.of(
                spot("A", 1, 1), spot("B", 5, 1), spot("C", 13, 1), spot("D", 18, 1), spot("A", 23, 1)), List.of());
    }

    /** LARGE_DISTRIBUTED. */
    public static DayState largeDistributed() {
        return state(60, 16, 10, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(15), 8),
                AgentState.patrol(PATROL_2, new Position(80), 8), AgentState.patrol(new AgentId(3), new Position(95), 8),
                AgentState.refuel(REFUEL_0, new Position(72))), List.of(
                spot("A", 2, 1), spot("B", 14, 1), spot("C", 35, 1), spot("D", 47, 1), spot("A", 82, 1),
                spot("B", 94, 1), spot("C", 126, 1), spot("D", 143, 1)), List.of());
    }

    /** LARGE_DENSE. */
    public static DayState largeDense() {
        return state(60, 12, 12, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(23), 8),
                AgentState.patrol(PATROL_2, new Position(48), 8), AgentState.patrol(new AgentId(3), new Position(95), 8),
                AgentState.refuel(REFUEL_0, new Position(72))), List.of(
                spot("A", 1, 2), spot("B", 2, 2), spot("C", 13, 1), spot("D", 14, 1), spot("A", 25, 1),
                spot("B", 26, 1), spot("C", 49, 2), spot("D", 50, 1), spot("A", 73, 1), spot("B", 74, 1),
                spot("C", 96, 1), spot("D", 97, 1)), List.of());
    }

    /** Deterministic ordered view of the eight fixtures reported by Phase 2.4. */
    public static Map<String, DayState> phase24Table() {
        Map<String, DayState> fixtures = new LinkedHashMap<>();
        fixtures.put("5x5-raw-kind-zero", rawKindZero5x5());
        fixtures.put("live-like-m6861", liveLike());
        fixtures.put("MEDIUM_REGION_RELOCATION", mediumRegionRelocation());
        fixtures.put("MEDIUM_SHARED_STOCK", mediumSharedStock());
        fixtures.put("MEDIUM_SUPPORT_CHAIN", mediumSupportChain());
        fixtures.put("LARGE_DISTRIBUTED", largeDistributed());
        fixtures.put("LARGE_DENSE", largeDense());
        fixtures.put("large-6-agent-60-step", currentLarge());
        return Collections.unmodifiableMap(fixtures);
    }

    private static DayState state(int stepBudget, int width, int height, List<AgentState> agents,
            List<UdonSpot> spots, List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[width * height];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(width, height, terrain),
                new DayStepBudgets(new int[] {stepBudget}), List.of(), new FuelCapacity(10), spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock, others);
    }

    private static UdonSpot spot(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }
}
