package vn.ptit.procon.planner.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.planner.WeightedRouteFinder;

/**
 * ITERATION 6/7 — pins the deterministic route work of {@link StrategicOpportunityGraphBuilder}.
 *
 * <p>These are not behaviour checks. Every assertion here fails if eliminated route work returns, and the
 * numbers are stated as exact integers rather than bounds so a regression cannot hide inside an inequality.
 *
 * <p>The reference fixture has {@code S=8} spots and {@code A=3} PATROL agents, so the builder REQUESTS
 * {@code S*(S-1) + 3*S*A = 128} routes at its four call sites. Only {@code 96} of them are distinct
 * {@code (origin, initialFuel, goal)} questions — iteration 6 cut the executions to those 96 — and those 96
 * questions come from only {@code 13} distinct {@code (origin, initialFuel)} SOURCES.
 *
 * <p>ITERATION 7 charges the remaining work to the source rather than to the question: because the route
 * search is goal-independent, one traversal per source answers every goal from it, so the fixture now runs
 * {@code 13} traversals instead of {@code 96} searches. The iteration-6 invariant is kept as well — no
 * question may be asked twice — because it is now implied rather than superseded.
 */
class StrategicGraphRouteMemoizationTest {

    private static final int SPOTS = 8;
    private static final int PATROLS = 3;
    private static final int REQUESTS = SPOTS * (SPOTS - 1) + 3 * SPOTS * PATROLS;
    private static final int DISTINCT_QUERIES = 96;
    private static final int DISTINCT_SOURCES = 13;
    /**
     * A shared traversal answers the whole declared goal set, so from each of the {@code S} spot sources it
     * also records the spot's own cell — the one {@code (from == to)} pair the spot-to-spot family skips.
     * That is {@code S} answers nobody asked for, at zero search cost: the source cell is settled first,
     * before a single edge is expanded.
     */
    private static final int ANSWERED_QUESTIONS = DISTINCT_QUERIES + SPOTS;
    private static final int COUNTED_REQUESTS = SPOTS * (SPOTS - 1) + 2 * SPOTS * PATROLS;

    /** The iteration-7 invariant: one single-source traversal per distinct source, not one per question. */
    @Test
    void ACTUAL_ROUTE_EXECUTIONS_EQUAL_DISTINCT_SOURCES() {
        CountingRouteFinder finder = new CountingRouteFinder();
        new StrategicOpportunityGraphBuilder(finder).build(reference(), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        assertEquals(DISTINCT_SOURCES, finder.distinctSources().size(),
                "distinct (origin,initialFuel) sources in one build");
        assertEquals(DISTINCT_SOURCES, finder.traversals.size(),
                "actual Dijkstra traversals must equal distinct sources");
        assertEquals(ANSWERED_QUESTIONS, finder.answers.size(),
                "and they must still answer every distinct (origin,initialFuel,goal) question");
        assertEquals(128, REQUESTS, "structural law S*(S-1)+3*S*A for the reference fixture");
        assertTrue(finder.traversals.size() < DISTINCT_QUERIES,
                "the fixture must actually contain per-goal repetition, otherwise it proves nothing");
    }

    /** Per source, not just in aggregate: no source is ever traversed twice. */
    @Test
    void EVERY_DISTINCT_SOURCE_TRAVERSES_EXACTLY_ONCE() {
        CountingRouteFinder finder = new CountingRouteFinder();
        new StrategicOpportunityGraphBuilder(finder).build(reference(), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        Map<String, Integer> perKey = new LinkedHashMap<>();
        finder.traversals.forEach(key -> perKey.merge(key, 1, Integer::sum));
        List<String> repeated = perKey.entrySet().stream().filter(entry -> entry.getValue() > 1)
                .map(entry -> entry.getKey() + " x" + entry.getValue()).toList();
        assertEquals(List.of(), repeated, "no (origin,initialFuel) may be traversed more than once");
    }

    /** The iteration-6 invariant, still true and now implied: no question reaches a search twice. */
    @Test
    void EVERY_DISTINCT_QUERY_IS_ANSWERED_EXACTLY_ONCE() {
        CountingRouteFinder finder = new CountingRouteFinder();
        new StrategicOpportunityGraphBuilder(finder).build(reference(), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        Map<String, Integer> perKey = new LinkedHashMap<>();
        finder.questionsAnswered.forEach(key -> perKey.merge(key, 1, Integer::sum));
        List<String> repeated = perKey.entrySet().stream().filter(entry -> entry.getValue() > 1)
                .map(entry -> entry.getKey() + " x" + entry.getValue()).toList();
        assertEquals(List.of(), repeated, "no (origin,initialFuel,goal) may be searched more than once");
    }

    /**
     * The REQUEST counter is a published diagnostic and must not move just because the work did.
     * {@code graphBuildPathfindingExecutions} still counts its three historical sites.
     */
    @Test
    void REQUEST_COUNTER_IS_UNCHANGED_BY_MEMOIZATION() {
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder()
                .build(reference(), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        assertEquals(COUNTED_REQUESTS, graph.graphBuildPathfindingExecutions());
        assertNotEquals(DISTINCT_QUERIES, COUNTED_REQUESTS,
                "the request counter and the execution count must be distinguishable numbers");
    }

    /**
     * Two PATROLs parked on one cell with one tank level are asking one question, and the answer they get
     * is the SAME immutable instance. If the duplicate work returns they receive equal-but-distinct routes.
     */
    @Test
    void SAME_CELL_SAME_FUEL_AGENTS_SHARE_ONE_EXECUTION() {
        DayState state = state(30, 5, 5, List.of(agent(0, 12, 8), agent(1, 12, 8)),
                List.of(spot("A", 1, 1), spot("B", 3, 1), spot("C", 21, 1)));
        CountingRouteFinder finder = new CountingRouteFinder();
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder(finder)
                .build(state, V3EdgeRetentionPolicy.CURRENT_PHASE0);
        assertEquals(1, finder.traversalsFrom(12, 8),
                "cell 12 with fuel 8 is ONE source, so it is ONE traversal covering all 3 goals");
        assertEquals(3, finder.answersFrom(12, 8), "and that one traversal answers all 3 goals");
        Map<Position, Route> first = graph.agentRoutes().get(new AgentId(0));
        Map<Position, Route> second = graph.agentRoutes().get(new AgentId(1));
        assertEquals(first.keySet(), second.keySet());
        first.forEach((goal, route) -> assertSame(route, second.get(goal),
                "identical questions must yield the identical shared Route instance"));
    }

    /** The tank level is part of the question. Dropping it from the key would invent unreachable routes. */
    @Test
    void DIFFERENT_FUEL_ON_THE_SAME_CELL_IS_NOT_ALIASED() {
        DayState state = state(40, 5, 5, List.of(agent(0, 0, 10), agent(1, 0, 1)),
                List.of(spot("A", 1, 1), spot("D", 23, 1)));
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder()
                .build(state, V3EdgeRetentionPolicy.CURRENT_PHASE0);
        Position far = new Position(23);
        assertTrue(graph.agentRoutes().get(new AgentId(0)).containsKey(far),
                "the fuelled PATROL can reach cell 23, so the fixture is meaningful");
        assertFalse(graph.agentRoutes().get(new AgentId(1)).containsKey(far),
                "a PATROL with one unit of fuel must never inherit the fuelled PATROL's route");
    }

    /** Nothing about the day state may be carried between two builds of the same builder instance. */
    @Test
    void DIFFERENT_TRAFFIC_IS_NOT_ALIASED() {
        StrategicOpportunityGraphBuilder shared = new StrategicOpportunityGraphBuilder();
        StrategicOpportunityGraph clear = shared.build(trafficState(TrafficStatus.CLEAR), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        StrategicOpportunityGraph jammed = shared.build(trafficState(TrafficStatus.JAMMED), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        assertNotEquals(clear, jammed, "JAMMED roads cost four steps, CLEAR roads cost one");
        assertEquals(new StrategicOpportunityGraphBuilder().build(trafficState(TrafficStatus.JAMMED),
                V3EdgeRetentionPolicy.CURRENT_PHASE0), jammed, "the second build must not read the first build's answers");
    }

    /** Stock is not part of the route question, but it IS part of the graph. */
    @Test
    void DIFFERENT_SPOT_STOCK_IS_NOT_ALIASED() {
        StrategicOpportunityGraphBuilder shared = new StrategicOpportunityGraphBuilder();
        StrategicOpportunityGraph lean = shared.build(stockState(1), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        StrategicOpportunityGraph rich = shared.build(stockState(9), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertNotEquals(lean, rich);
        assertEquals(new StrategicOpportunityGraphBuilder().build(stockState(9), V3EdgeRetentionPolicy.DIVERSE_GRAPH), rich);
    }

    /** The origin cell is part of the question. */
    @Test
    void DIFFERENT_AGENT_POSITION_IS_NOT_ALIASED() {
        List<UdonSpot> spots = List.of(spot("A", 1, 1), spot("B", 3, 1), spot("C", 21, 1));
        StrategicOpportunityGraphBuilder shared = new StrategicOpportunityGraphBuilder();
        StrategicOpportunityGraph near = shared.build(state(30, 5, 5, List.of(agent(0, 0, 9)), spots), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        StrategicOpportunityGraph far = shared.build(state(30, 5, 5, List.of(agent(0, 24, 9)), spots), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        assertNotEquals(near.agentRoutes(), far.agentRoutes(), "a moved PATROL must be re-searched from its real cell");
    }

    /**
     * Key completeness. {@code WeightedRouteFinder.find} reads the cell and the tank and nothing else off
     * the agent, so the key omits the id and the kind on purpose: re-asking under a different id must agree.
     */
    @Test
    void MEMO_KEY_IS_COMPLETE_AGENT_ID_IS_NEVER_READ() {
        DayState state = reference();
        CountingRouteFinder finder = new CountingRouteFinder();
        new StrategicOpportunityGraphBuilder(finder).build(state, V3EdgeRetentionPolicy.CURRENT_PHASE0);
        WeightedRouteFinder fresh = new WeightedRouteFinder();
        finder.answers.forEach((key, answer) -> {
            int[] parts = parse(key);
            Optional<Route> reasked = fresh.find(state,
                    AgentState.patrol(new AgentId(77), new Position(parts[0]), parts[1]), new Position(parts[2]));
            assertEquals(answer, reasked, "answer must depend only on (origin,initialFuel,goal): " + key);
        });
    }

    /** The mandate forbids a cross-build cache. Two builds must pay for two builds. */
    @Test
    void MEMO_LIFETIME_IS_EXACTLY_ONE_BUILD() {
        CountingRouteFinder finder = new CountingRouteFinder();
        StrategicOpportunityGraphBuilder builder = new StrategicOpportunityGraphBuilder(finder);
        builder.build(reference(), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        builder.build(reference(), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        assertEquals(2 * DISTINCT_SOURCES, finder.traversals.size(),
                "no cache may survive a build boundary");
    }

    /** Every retention policy must be unaffected: the memo is below edge selection, not inside it. */
    @Test
    void GRAPH_RESULT_IS_POLICY_STABLE_AND_DETERMINISTIC() {
        for (V3EdgeRetentionPolicy policy : V3EdgeRetentionPolicy.values()) {
            assertEquals(new StrategicOpportunityGraphBuilder().build(reference(), policy),
                    new StrategicOpportunityGraphBuilder(new CountingRouteFinder()).build(reference(), policy),
                    "policy " + policy + " must be untouched by route memoization");
        }
    }

    // ------------------------------------------------------------------ probes and fixtures

    /**
     * Counts what actually reaches the Dijkstra search. {@code traversals} counts single-source traversals,
     * keyed on the source alone; {@code questionsAnswered} counts the {@code (origin,fuel,goal)} questions
     * those traversals resolved, so the iteration-6 and iteration-7 invariants can both be asserted.
     */
    private static final class CountingRouteFinder extends WeightedRouteFinder {
        private final List<String> traversals = new ArrayList<>();
        private final List<String> questionsAnswered = new ArrayList<>();
        private final Map<String, Optional<Route>> answers = new LinkedHashMap<>();

        @Override
        public Optional<Route> find(DayState state, AgentState agent, Position goal) {
            Optional<Route> route = super.find(state, agent, goal);
            traversals.add(source(agent));
            record(source(agent) + "/" + goal.value(), route);
            return route;
        }

        @Override
        public Map<Position, Route> findAll(DayState state, AgentState agent,
                java.util.Collection<Position> goals) {
            Map<Position, Route> routes = super.findAll(state, agent, goals);
            traversals.add(source(agent));
            for (Position goal : goals) {
                record(source(agent) + "/" + goal.value(), Optional.ofNullable(routes.get(goal)));
            }
            return routes;
        }

        private void record(String key, Optional<Route> route) {
            questionsAnswered.add(key);
            answers.put(key, route);
        }

        private static String source(AgentState agent) {
            return agent.position().value() + "/"
                    + (agent.fuel() instanceof FiniteFuel finite ? finite.amount() : -1);
        }

        Set<String> distinctSources() {
            return new LinkedHashSet<>(traversals);
        }

        long traversalsFrom(int origin, int fuel) {
            return traversals.stream().filter(key -> key.equals(origin + "/" + fuel)).count();
        }

        long answersFrom(int origin, int fuel) {
            return answers.keySet().stream().filter(key -> key.startsWith(origin + "/" + fuel + "/")).count();
        }
    }

    private static int[] parse(String key) {
        String[] parts = key.split("/");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }

    /** S=8, A=3, three distinct tank levels so the four query families overlap only partially. */
    private static DayState reference() {
        return state(30, 5, 5, List.of(agent(0, 0, 8), agent(1, 12, 9), agent(2, 24, 10)),
                List.of(spot("A", 1, 1), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1),
                        spot("A", 11, 1), spot("B", 13, 1), spot("C", 16, 1), spot("D", 18, 1)));
    }

    private static DayState stockState(int stock) {
        List<UdonSpot> spots = List.of(spot("A", 1, stock), spot("B", 3, stock), spot("C", 21, stock), spot("D", 23, stock));
        return state(30, 5, 5, List.of(agent(0, 0, 9), agent(1, 12, 9)), spots);
    }

    /** All ROAD except the PLAIN spot cells, so the traffic status changes every step cost. */
    private static DayState trafficState(TrafficStatus status) {
        List<UdonSpot> spots = List.of(spot("A", 1, 1), spot("B", 3, 1), spot("C", 21, 1), spot("D", 23, 1));
        Terrain[] terrain = new Terrain[25];
        Arrays.fill(terrain, Terrain.ROAD);
        spots.forEach(value -> terrain[value.position().value()] = Terrain.PLAIN);
        Map<Position, TrafficStatus> traffic = new LinkedHashMap<>();
        for (int cell = 0; cell < terrain.length; cell++) {
            if (terrain[cell] == Terrain.ROAD) {
                traffic.put(new Position(cell), status);
            }
        }
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(value -> stock.put(value.position(), value.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(5, 5, terrain), new DayStepBudgets(new int[] {40}),
                List.of(), new FuelCapacity(20), spots);
        return new DayState(match, new DayIndex(0), List.of(agent(0, 0, 20), agent(1, 12, 20)), traffic, stock, List.of());
    }

    private static DayState state(int steps, int width, int height, List<AgentState> agents, List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[width * height];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(value -> stock.put(value.position(), value.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(width, height, terrain),
                new DayStepBudgets(new int[] {steps}), List.of(), new FuelCapacity(10), spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock, List.of());
    }

    private static AgentState agent(int id, int position, int fuel) {
        return AgentState.patrol(new AgentId(id), new Position(position), fuel);
    }

    private static UdonSpot spot(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }
}
