package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
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
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.planner.v2.R3SupportRootExport;

/**
 * The PART 38-45 micro fixtures: one tiny, hand-verifiable day per mobile-support rule.
 *
 * <p>Every map here is a single all-PLAIN row, so {@link Direction#RIGHT} is {@code +1} and
 * {@link Direction#LEFT} is {@code -1} on both even and odd rows and every move costs exactly two steps
 * and one fuel. That makes each tanker timeline readable by hand, which is the point: these fixtures are
 * the ones that must fail loudly if the refuel semantics ever drift back towards granting fuel.
 *
 * <p>The support roots here are built directly, not exported from R3, because the rules under test are the
 * SEMANTICS of a fixed tanker trajectory rather than the provenance of the universe. They therefore carry
 * {@link #MICRO_FIXTURE} provenance and never enter a {@link V3SupportRootUniverse}; the provenance gates
 * (PART 48) are asserted on the real fixtures, where the roots really do come from R3.
 */
public final class V3Phase25Fixtures {

    /** Provenance of a hand-built micro-fixture root. Deliberately NOT {@code EXISTING_R3_ROOT}. */
    public static final String MICRO_FIXTURE = "PHASE25_MICRO_FIXTURE";

    private static final AgentId PATROL_0 = new AgentId(0);
    private static final AgentId PATROL_1 = new AgentId(1);
    private static final AgentId REFUEL_0 = new AgentId(9);

    private V3Phase25Fixtures() {
    }

    /**
     * PART 39/43/44: two zero-fuel PATROLs and ONE tanker that drives past both of them.
     *
     * <p>The tanker leaves cell 6 and walks left to cell 0, arriving at cell 2 on step 8 and at cell 0 on
     * step 12. PATROL1 stands on cell 2 and PATROL0 on cell 0, both with an empty tank, so neither can move
     * a single step until the tanker reaches it. Nothing else on this day can produce fuel.
     */
    public static DayState supportRendezvous() {
        return state(20, 10, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 0),
                AgentState.patrol(PATROL_1, new Position(2), 0),
                AgentState.refuel(REFUEL_0, new Position(6))), List.of(
                spot("A", 1, 1), spot("B", 3, 1), spot("C", 7, 1), spot("D", 9, 1)));
    }

    /**
     * The rendezvous root. Its metadata predicts ONLY the step-12 service of PATROL0.
     *
     * <p>PART 43: the step-8 refuelling of PATROL1 is caused by the same tanker route but was never
     * recorded as a service, so it must surface as an unplanned refill rather than disappear.
     */
    public static V3SupportRootContext supportRendezvousRoot(DayState state) {
        return root(state, "9:0@0/12", moves(Direction.LEFT, 6), 12,
                List.of(0), List.of(service(0, 0, 12)));
    }

    /** PART 45: the SAME day, a different tanker future. Its refuelling reaches neither PATROL. */
    public static V3SupportRootContext supportRendezvousAlternativeRoot(DayState state) {
        return root(state, "9:0@8/4", moves(Direction.RIGHT, 2), 4, List.of(0),
                List.of(service(0, 8, 4)));
    }

    /**
     * PART 41: the PATROL is parked on the cell the tanker starts from and then leaves.
     *
     * <p>On step 1 the tanker is mid-move and still retains cell 2, and it never comes back. A retained
     * source is not an occupied cell, so this day must end with the PATROL still empty.
     */
    public static DayState movingRetainedSource() {
        return state(12, 6, List.of(
                AgentState.patrol(PATROL_0, new Position(2), 0),
                AgentState.refuel(REFUEL_0, new Position(2))), List.of(spot("A", 3, 1)));
    }

    public static V3SupportRootContext movingRetainedSourceRoot(DayState state) {
        return root(state, "9:0@5/6", moves(Direction.RIGHT, 3), 6, List.of(0), List.of());
    }

    /**
     * PART 38: a tanker root exists, but its route never touches the expensive leg.
     *
     * <p>PATROL0 holds exactly one fuel and the spot on cell 5 costs five. The tanker walks the other way,
     * so no wait of any length makes that leg legal. This is the fixture that fails the moment support ever
     * degenerates back into granting fuel.
     */
    public static DayState unreachableSupport() {
        return state(20, 12, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 1),
                AgentState.refuel(REFUEL_0, new Position(8))), List.of(
                spot("B", 1, 1), spot("A", 5, 1)));
    }

    public static V3SupportRootContext unreachableSupportRoot(DayState state) {
        return root(state, "9:0@11/6", moves(Direction.RIGHT, 3), 6, List.of(0), List.of());
    }

    /**
     * PART 40/42/14: the rendezvous happens in the MIDDLE of one committed leg.
     *
     * <p>PATROL0 has two fuel and a four-fuel leg to fly. It departs immediately, spends its whole tank
     * reaching cell 2, and arrives there on exactly the step the tanker also arrives, which refills it and
     * lets the same leg continue. No waiting is inserted anywhere.
     */
    public static DayState midRouteSupport() {
        return state(20, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 2),
                AgentState.refuel(REFUEL_0, new Position(4))), List.of(
                spot("B", 2, 1), spot("A", 4, 1)));
    }

    public static V3SupportRootContext midRouteSupportRoot(DayState state) {
        return root(state, "9:0@2/4", moves(Direction.LEFT, 2), 4, List.of(0),
                List.of(service(0, 2, 4)));
    }

    /** The straight single-row leg from {@code from} to {@code to}; no pathfinding is involved. */
    public static Route straight(int from, int to) {
        if (from == to) throw new IllegalArgumentException("A micro-fixture leg must move");
        Direction direction = to > from ? Direction.RIGHT : Direction.LEFT;
        int hops = Math.abs(to - from);
        List<Direction> directions = new ArrayList<>();
        for (int index = 0; index < hops; index++) directions.add(direction);
        return new Route(new Position(from), new Position(to), List.copyOf(directions), hops * 2, hops);
    }

    /** A full-day action sequence: the given moves, then WAIT padding to the day budget. */
    public static List<AgentAction> fullDay(DayState state, List<AgentAction> moves, int consumedSteps) {
        List<AgentAction> actions = new ArrayList<>(moves);
        if (consumedSteps < state.stepBudget()) {
            actions.add(new WaitAction(state.stepBudget() - consumedSteps));
        }
        return List.copyOf(actions);
    }

    /** The four micro fixtures in a deterministic order, for reporting. */
    public static Map<String, DayState> microTable() {
        Map<String, DayState> fixtures = new LinkedHashMap<>();
        fixtures.put("MICRO_SUPPORT_RENDEZVOUS", supportRendezvous());
        fixtures.put("MICRO_MOVING_RETAINED_SOURCE", movingRetainedSource());
        fixtures.put("MICRO_UNREACHABLE_SUPPORT", unreachableSupport());
        fixtures.put("MICRO_MID_ROUTE_SUPPORT", midRouteSupport());
        return Map.copyOf(fixtures);
    }

    private static V3SupportRootContext root(DayState state, String signature, List<AgentAction> moves,
            int consumedSteps, List<Integer> supported, List<R3SupportRootExport.PlannedService> services) {
        List<AgentAction> actions = fullDay(state, moves, consumedSteps);
        AgentState refuel = state.agents().stream().filter(agent -> agent.id().equals(REFUEL_0)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Micro fixture has no REFUEL agent"));
        CachedSupportTrajectory trajectory = CachedSupportTrajectory.from(state, REFUEL_0, refuel.position(),
                actions);
        return new V3SupportRootContext("MICRO#" + signature, signature, REFUEL_0, refuel.position(),
                services.size(), supported, actions, services, MICRO_FIXTURE, trajectory);
    }

    private static R3SupportRootExport.PlannedService service(int patrolId, int position, int step) {
        return new R3SupportRootExport.PlannedService(patrolId, new Position(position), step, 10, List.of());
    }

    private static List<AgentAction> moves(Direction direction, int count) {
        List<AgentAction> actions = new ArrayList<>();
        for (int index = 0; index < count; index++) actions.add(new MoveAction(direction));
        return List.copyOf(actions);
    }

    private static DayState state(int stepBudget, int width, List<AgentState> agents, List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(width, 1, terrain),
                new DayStepBudgets(new int[] {stepBudget}), List.of(), new FuelCapacity(10), spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock, List.of());
    }

    private static UdonSpot spot(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }
}
