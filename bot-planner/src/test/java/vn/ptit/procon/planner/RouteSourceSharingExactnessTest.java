package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
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

/**
 * ITERATION 7 — the exactness invariant of {@link WeightedRouteFinder#findAll}.
 *
 * <p>The claim being pinned is not "similar routes" and not "the same step count". It is the strongest
 * statement available: for every source and every goal, the {@link Route} the shared single-source traversal
 * records is {@link Object#equals equal} to the {@link Route} the dedicated per-goal search returns, and a
 * goal the per-goal search cannot reach is absent from the shared answer. The tests below assert that
 * EXHAUSTIVELY — every traversable cell as a source, every cell as a goal — on fixtures chosen so that all
 * the ways a route can be refused are exercised: ponds, mountains, jammed roads, an empty tank and a step
 * budget that runs out mid-map.
 *
 * <p>Why the equality can be expected to hold at all: {@link WeightedRouteFinder}'s label order is
 * {@code (steps, fuelUsed, position, -fuel, moves)}, which never mentions the goal, and the search uses no
 * goal-directed heuristic. Two searches from the same {@code (cell, tank)} therefore settle labels in the
 * same order; the goal only decides where that shared order is cut off. The shared traversal reconstructs a
 * goal's route at the instant that goal is settled — the same instant, and so the same predecessor map, the
 * per-goal search would have used.
 */
class RouteSourceSharingExactnessTest {

    private final WeightedRouteFinder finder = new WeightedRouteFinder();

    @Test
    @DisplayName("OPEN_PLAIN_EVERY_SOURCE_EVERY_GOAL_AGREES")
    void OPEN_PLAIN_EVERY_SOURCE_EVERY_GOAL_AGREES() {
        assertExhaustiveAgreement(plain(6, 6, 60), 9);
    }

    /** Ponds are the only non-traversable terrain, so they are how a goal becomes structurally unreachable. */
    @Test
    @DisplayName("POND_WALL_LEAVES_UNREACHABLE_GOALS_ABSENT_IN_BOTH")
    void POND_WALL_LEAVES_UNREACHABLE_GOALS_ABSENT_IN_BOTH() {
        DayState state = terrain(6, 6, 60, pondWall(6, 6), Map.of());
        Report report = assertExhaustiveAgreement(state, 12);
        assertTrue(report.refused() > 0,
                "the pond wall must actually cut the map in two, otherwise the fixture proves nothing");
        assertTrue(report.resolved() > 0, "and it must still leave reachable goals: " + report);
    }

    /** MOUNTAIN is traversable but expensive, which is what makes the tank the binding constraint. */
    @Test
    @DisplayName("MOUNTAIN_RIDGE_AND_A_SMALL_TANK_AGREE")
    void MOUNTAIN_RIDGE_AND_A_SMALL_TANK_AGREE() {
        Terrain[] cells = new Terrain[36];
        Arrays.fill(cells, Terrain.PLAIN);
        for (int cell = 12; cell < 18; cell++) {
            cells[cell] = Terrain.MOUNTAIN;
        }
        Report report = assertExhaustiveAgreement(terrain(6, 6, 60, cells, Map.of()), 4);
        assertTrue(report.refused() > 0, "a four-unit tank must fail to reach something: " + report);
    }

    /** Road traffic changes every step cost, so it changes the settle ORDER, not just the totals. */
    @Test
    @DisplayName("MIXED_ROAD_TRAFFIC_AGREES")
    void MIXED_ROAD_TRAFFIC_AGREES() {
        Terrain[] cells = new Terrain[36];
        Arrays.fill(cells, Terrain.ROAD);
        for (int cell = 0; cell < cells.length; cell += 5) {
            cells[cell] = Terrain.PLAIN;
        }
        Map<Position, TrafficStatus> traffic = new LinkedHashMap<>();
        for (int cell = 0; cell < cells.length; cell++) {
            if (cells[cell] == Terrain.ROAD) {
                traffic.put(new Position(cell), cell % 3 == 0 ? TrafficStatus.JAMMED : TrafficStatus.CLEAR);
            }
        }
        assertExhaustiveAgreement(terrain(6, 6, 60, cells, traffic), 20);
    }

    /**
     * A road cell whose traffic status is UNKNOWN to the state is impassable by rule, and a step budget of 3
     * truncates the search long before the map is exhausted. Both are cut-offs the shared traversal has to
     * honour at exactly the same places.
     */
    @Test
    @DisplayName("MISSING_TRAFFIC_AND_A_TIGHT_STEP_BUDGET_AGREE")
    void MISSING_TRAFFIC_AND_A_TIGHT_STEP_BUDGET_AGREE() {
        Terrain[] cells = new Terrain[36];
        Arrays.fill(cells, Terrain.PLAIN);
        for (int cell = 20; cell < 26; cell++) {
            cells[cell] = Terrain.ROAD;
        }
        Map<Position, TrafficStatus> traffic = new LinkedHashMap<>();
        traffic.put(new Position(20), TrafficStatus.CLEAR);
        traffic.put(new Position(22), TrafficStatus.JAMMED);
        Report report = assertExhaustiveAgreement(terrain(6, 6, 3, cells, traffic), 40);
        assertTrue(report.refused() > 0, "a three-step budget must leave far cells unreached: " + report);
    }

    /** An exhausted tank refuses every goal but the cell the agent already occupies. */
    @Test
    @DisplayName("EMPTY_TANK_AGREES")
    void EMPTY_TANK_AGREES() {
        assertExhaustiveAgreement(plain(5, 5, 30), 0);
    }

    /** A REFUEL agent has no finite tank, so neither entry point may search at all. */
    @Test
    @DisplayName("NON_FINITE_FUEL_IS_REFUSED_BY_BOTH")
    void NON_FINITE_FUEL_IS_REFUSED_BY_BOTH() {
        DayState state = plain(5, 5, 30);
        AgentState support = AgentState.refuel(new AgentId(9), new Position(0));
        List<Position> goals = cells(state);
        assertEquals(Map.of(), finder.findAll(state, support, goals));
        goals.forEach(goal -> assertTrue(finder.find(state, support, goal).isEmpty()));
    }

    /** A source standing on a pond cannot move, and the two entry points must refuse it identically. */
    @Test
    @DisplayName("NON_TRAVERSABLE_SOURCE_IS_REFUSED_BY_BOTH")
    void NON_TRAVERSABLE_SOURCE_IS_REFUSED_BY_BOTH() {
        DayState state = terrain(6, 6, 60, pondWall(6, 6), Map.of());
        AgentState stranded = AgentState.patrol(new AgentId(3), new Position(21), 12);
        assertEquals(Terrain.POND, state.matchData().map().terrainAt(stranded.position()),
                "cell 21 must be the pond the fixture puts there");
        assertEquals(Map.of(), finder.findAll(state, stranded, cells(state)));
        cells(state).forEach(goal -> assertTrue(finder.find(state, stranded, goal).isEmpty()));
    }

    /** The goal set is a filter, not a promise: an off-map goal is dropped, never searched for. */
    @Test
    @DisplayName("OFF_MAP_AND_POND_GOALS_ARE_DROPPED_NOT_SEARCHED")
    void OFF_MAP_AND_POND_GOALS_ARE_DROPPED_NOT_SEARCHED() {
        DayState state = terrain(6, 6, 60, pondWall(6, 6), Map.of());
        AgentState agent = AgentState.patrol(new AgentId(0), new Position(0), 20);
        Position pond = new Position(21);
        Position offMap = new Position(9999);
        Map<Position, Route> answers = finder.findAll(state, agent, List.of(pond, offMap, new Position(1)));
        assertFalse(answers.containsKey(pond), "a pond goal is not a route destination");
        assertFalse(answers.containsKey(offMap), "an off-map goal is not a route destination");
        assertTrue(finder.find(state, agent, pond).isEmpty());
        assertTrue(finder.find(state, agent, offMap).isEmpty());
        assertEquals(1, answers.size(), "only the one admissible goal survives: " + answers.keySet());
    }

    /** Degenerate goal sets must not search, and a null goal must not be silently skipped. */
    @Test
    @DisplayName("EMPTY_GOAL_SET_SEARCHES_NOTHING_AND_NULL_IS_REJECTED")
    void EMPTY_GOAL_SET_SEARCHES_NOTHING_AND_NULL_IS_REJECTED() {
        DayState state = plain(5, 5, 30);
        AgentState agent = AgentState.patrol(new AgentId(0), new Position(0), 9);
        assertEquals(Map.of(), finder.findAll(state, agent, List.of()));
        assertThrows(NullPointerException.class,
                () -> finder.findAll(state, agent, Arrays.asList(new Position(1), null)));
        assertThrows(NullPointerException.class, () -> finder.findAll(null, agent, List.of(new Position(1))));
        assertThrows(NullPointerException.class, () -> finder.findAll(state, agent, null));
    }

    /**
     * The route to the agent's own cell is the empty route, and it is the same empty route both ways. This is
     * the one goal the shared traversal answers before it has expanded anything at all.
     */
    @Test
    @DisplayName("SELF_GOAL_IS_THE_EMPTY_ROUTE_IN_BOTH")
    void SELF_GOAL_IS_THE_EMPTY_ROUTE_IN_BOTH() {
        DayState state = plain(5, 5, 30);
        AgentState agent = AgentState.patrol(new AgentId(0), new Position(12), 9);
        Route shared = finder.findAll(state, agent, cells(state)).get(agent.position());
        Route single = finder.find(state, agent, agent.position()).orElseThrow();
        assertEquals(single, shared);
        assertEquals(List.of(), shared.directions());
        assertEquals(0, shared.stepsUsed());
        assertEquals(0, shared.fuelUsed());
    }

    /**
     * Asking for a subset must not change any answer in it. If the traversal's stopping point leaked into the
     * routes it records, a two-goal request would disagree with the all-goal request on the shared goal.
     */
    @Test
    @DisplayName("A_SUBSET_REQUEST_AGREES_WITH_THE_FULL_REQUEST")
    void A_SUBSET_REQUEST_AGREES_WITH_THE_FULL_REQUEST() {
        DayState state = terrain(6, 6, 60, pondWall(6, 6), Map.of());
        List<Position> all = cells(state);
        int compared = 0;
        for (Position source : all) {
            if (!state.matchData().map().isTraversable(source)) {
                continue;
            }
            AgentState agent = AgentState.patrol(new AgentId(0), source, 14);
            Map<Position, Route> full = finder.findAll(state, agent, all);
            for (Position goal : all) {
                Map<Position, Route> pair = finder.findAll(state, agent, List.of(source, goal));
                assertEquals(full.get(goal), pair.get(goal),
                        "goal " + goal.value() + " from " + source.value() + " must not depend on the set");
                compared++;
            }
        }
        assertTrue(compared >= 900, "the sweep must be exhaustive, compared=" + compared);
    }

    // ------------------------------------------------------------------ the sweep

    private record Report(int resolved, int refused) {
        int total() {
            return resolved + refused;
        }
    }

    /**
     * Every traversable cell of {@code state} is used as a source with the given tank, and every cell of the
     * map is used as a goal. The dedicated per-goal answer and the shared single-source answer must agree on
     * all of them, including on which goals have no answer at all.
     */
    private Report assertExhaustiveAgreement(DayState state, int fuel) {
        List<Position> goals = cells(state);
        HexMap map = state.matchData().map();
        int resolved = 0;
        int refused = 0;
        int sources = 0;
        for (Position source : goals) {
            if (!map.isTraversable(source)) {
                continue;
            }
            sources++;
            AgentState agent = AgentState.patrol(new AgentId(0), source, fuel);
            Map<Position, Route> shared = finder.findAll(state, agent, goals);
            for (Position goal : goals) {
                Optional<Route> single = finder.find(state, agent, goal);
                Route sharedRoute = shared.get(goal);
                String where = "from=" + source.value() + " to=" + goal.value() + " fuel=" + fuel;
                assertEquals(single.isPresent(), sharedRoute != null,
                        "reachability must agree exactly: " + where);
                if (single.isPresent()) {
                    Route expected = single.orElseThrow();
                    assertEquals(expected, sharedRoute, "the recorded route must be the searched route: "
                            + where + " expected=" + describe(expected) + " shared=" + describe(sharedRoute));
                    assertEquals(expected.directions(), sharedRoute.directions(),
                            "step-for-step identical, not merely equally long: " + where);
                    assertSame(source, sharedRoute.start(), "the shared route keeps the real source cell");
                    assertEquals(goal, sharedRoute.goal());
                    resolved++;
                } else {
                    refused++;
                }
            }
        }
        assertTrue(sources > 1, "the fixture must offer more than one source");
        Report report = new Report(resolved, refused);
        assertEquals(sources * goals.size(), report.total(), "every pair must be classified exactly once");
        return report;
    }

    private static String describe(Route route) {
        return route == null ? "<none>"
                : route.stepsUsed() + "steps/" + route.fuelUsed() + "fuel/" + route.directions();
    }

    private static List<Position> cells(DayState state) {
        List<Position> positions = new ArrayList<>();
        HexMap map = state.matchData().map();
        for (int cell = 0; cell < map.width() * map.height(); cell++) {
            positions.add(new Position(cell));
        }
        return positions;
    }

    private static Terrain[] pondWall(int width, int height) {
        Terrain[] cells = new Terrain[width * height];
        Arrays.fill(cells, Terrain.PLAIN);
        for (int row = 0; row < height; row++) {
            if (row != height - 1) {
                cells[row * width + width / 2] = Terrain.POND;
            }
        }
        return cells;
    }

    private static DayState plain(int width, int height, int steps) {
        Terrain[] cells = new Terrain[width * height];
        Arrays.fill(cells, Terrain.PLAIN);
        return terrain(width, height, steps, cells, Map.of());
    }

    private static DayState terrain(int width, int height, int steps, Terrain[] cells,
            Map<Position, TrafficStatus> traffic) {
        List<UdonSpot> spots = List.of(new UdonSpot(new BrandId("A"), new Position(0), 1));
        StaticMatchData match = new StaticMatchData(new HexMap(width, height, cells),
                new DayStepBudgets(new int[] {steps}), List.of(),
                new vn.ptit.procon.domain.agent.FuelCapacity(40), spots);
        return new DayState(match, new DayIndex(0),
                List.of(AgentState.patrol(new AgentId(0), new Position(0), 40)), traffic,
                Map.of(new Position(0), 1), List.of());
    }
}
