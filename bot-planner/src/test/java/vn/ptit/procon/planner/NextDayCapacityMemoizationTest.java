package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Correctness contract of the per-calculator next-day capacity memo.
 *
 * <p>The memo key is {@code (projectedEndPosition, projectedEndFuel)}. These tests pin that the key is
 * COMPLETE — nothing outside it may leak between two answers — and that it is not under-specified in
 * exchange for speed. Every non-aliasing test compares the shared-instance answer against a ground truth
 * computed by a calculator that serves a single patrol, so aliasing is impossible in the reference value and
 * no expected number has to be hardcoded.
 *
 * <p>The fixture is the wide-Pareto board of {@link NextDayHarvestCapacityLabelOnceDpTest}: row 0 is ROAD
 * ({@code 1 step, 2 fuel} per move under the optimistic CLEAR assumption), row 1 is PLAIN ({@code 2 steps,
 * 1 fuel}) and carries every Udon spot, so steps and fuel genuinely trade against each other and both key
 * components really do change the answer.
 */
class NextDayCapacityMemoizationTest {

    private static final int WIDTH = 12;
    private static final int GENEROUS_NEXT_DAY_BUDGET = 24;
    private static final List<UdonSpot> FOUR_SPOTS = List.of(
            spot("A", WIDTH + 2), spot("B", WIDTH + 5), spot("C", WIDTH + 8), spot("D", WIDTH + 11));
    private static final List<UdonSpot> ONE_SPOT = List.of(spot("A", WIDTH + 2));

    @Test
    @DisplayName("SAME_KEY_YIELDS_THE_IDENTICAL_ANSWER_FOR_EVERY_REQUESTING_PATROL")
    void SAME_KEY_YIELDS_THE_IDENTICAL_ANSWER_FOR_EVERY_REQUESTING_PATROL() {
        DayState state = state(List.of(patrol(0, 0, 20), patrol(1, 0, 20), patrol(2, 0, 20)),
                GENEROUS_NEXT_DAY_BUDGET, FOUR_SPOTS);
        NextDayHarvestCapacityCalculator calculator = NextDayHarvestCapacityCalculator.forState(state);
        List<PatrolNextDayHarvestCapacity> patrols = evaluate(calculator, state).patrols();

        List<Object> reference = derived(single(0, 20, GENEROUS_NEXT_DAY_BUDGET, FOUR_SPOTS));
        assertEquals(List.of(reference, reference, reference),
                patrols.stream().map(NextDayCapacityMemoizationTest::derived).toList(),
                "three patrols sharing one key must report the DP answer of that key, unchanged");
        assertEquals(List.of(0, 1, 2), patrols.stream()
                .map(value -> value.agentId().value()).toList(),
                "identity is re-attached per requester and is deliberately not part of the key");
        assertEquals(1, calculator.capacityCacheMisses());
        assertEquals(2, calculator.capacityCacheHits());
    }

    @Test
    @DisplayName("DIFFERENT_FUEL_IS_NEVER_ALIASED")
    void DIFFERENT_FUEL_IS_NEVER_ALIASED() {
        DayState state = state(List.of(patrol(0, 0, 20), patrol(1, 0, 6)),
                GENEROUS_NEXT_DAY_BUDGET, FOUR_SPOTS);
        NextDayHarvestCapacityCalculator calculator = NextDayHarvestCapacityCalculator.forState(state);
        List<PatrolNextDayHarvestCapacity> patrols = evaluate(calculator, state).patrols();

        assertEquals(derived(single(0, 20, GENEROUS_NEXT_DAY_BUDGET, FOUR_SPOTS)),
                derived(patrols.get(0)));
        assertEquals(derived(single(0, 6, GENEROUS_NEXT_DAY_BUDGET, FOUR_SPOTS)),
                derived(patrols.get(1)));
        assertNotEquals(patrols.get(0).maxReachableDistinctSpots(),
                patrols.get(1).maxReachableDistinctSpots(),
                "the cheapest four-spot tour costs 12 fuel, so 6 fuel must reach strictly fewer spots");
        assertEquals(0, calculator.capacityCacheHits(), "same position, different fuel: two DP questions");
        assertEquals(2, calculator.capacityCacheMisses());
    }

    @Test
    @DisplayName("DIFFERENT_POSITION_IS_NEVER_ALIASED")
    void DIFFERENT_POSITION_IS_NEVER_ALIASED() {
        DayState state = state(List.of(patrol(0, 0, 20), patrol(1, WIDTH, 20)),
                GENEROUS_NEXT_DAY_BUDGET, FOUR_SPOTS);
        NextDayHarvestCapacityCalculator calculator = NextDayHarvestCapacityCalculator.forState(state);
        List<PatrolNextDayHarvestCapacity> patrols = evaluate(calculator, state).patrols();

        assertEquals(derived(single(0, 20, GENEROUS_NEXT_DAY_BUDGET, FOUR_SPOTS)),
                derived(patrols.get(0)));
        assertEquals(derived(single(WIDTH, 20, GENEROUS_NEXT_DAY_BUDGET, FOUR_SPOTS)),
                derived(patrols.get(1)));
        assertNotEquals(patrols.get(0).dpStatesEvaluated(), patrols.get(1).dpStatesEvaluated(),
                "the ROAD start and the PLAIN start reach the same spots along different Pareto fronts");
        assertEquals(0, calculator.capacityCacheHits(), "same fuel, different position: two DP questions");
        assertEquals(2, calculator.capacityCacheMisses());
    }

    @Test
    @DisplayName("IMMUTABLE_INSTANCE_STATE_IS_NEVER_SHARED_ACROSS_CALCULATORS")
    void IMMUTABLE_INSTANCE_STATE_IS_NEVER_SHARED_ACROSS_CALCULATORS() {
        PatrolNextDayHarvestCapacity fourSpots = single(0, 20, GENEROUS_NEXT_DAY_BUDGET, FOUR_SPOTS);

        // Same key, fewer opportunities: the memo must not answer from the richer instance.
        PatrolNextDayHarvestCapacity oneSpot = single(0, 20, GENEROUS_NEXT_DAY_BUDGET, ONE_SPOT);
        assertEquals(4, fourSpots.maxReachableDistinctSpots());
        assertEquals(1, oneSpot.maxReachableDistinctSpots(),
                "the opportunity list belongs to the instance, not to the key");

        // Same key, same opportunities, tighter next-day step budget: likewise a different instance answer.
        PatrolNextDayHarvestCapacity tightBudget = single(0, 20, 6, FOUR_SPOTS);
        assertTrue(tightBudget.maxReachableDistinctSpots() < fourSpots.maxReachableDistinctSpots(),
                "6 steps cannot buy the four-spot tour that 24 steps buys");
    }

    @Test
    @DisplayName("CACHE_HIT_DOES_NOT_RERUN_THE_DP")
    void CACHE_HIT_DOES_NOT_RERUN_THE_DP() {
        DayState state = state(List.of(patrol(0, 0, 20), patrol(1, 0, 20), patrol(2, 0, 20),
                patrol(3, 0, 20), patrol(4, WIDTH, 20)), GENEROUS_NEXT_DAY_BUDGET, FOUR_SPOTS);
        NextDayHarvestCapacityCalculator calculator = NextDayHarvestCapacityCalculator.forState(state);
        TeamNextDayHarvestCapacity team = evaluate(calculator, state);

        // capacityCacheMisses is incremented at the only call site of the DP, so "misses == 2" is the
        // statement that the DP body ran exactly twice while five patrols asked for an answer.
        assertEquals(5, team.patrols().size());
        assertEquals(2, calculator.capacityCacheMisses(), "two distinct keys, so exactly two DP runs");
        assertEquals(3, calculator.capacityCacheHits(), "the three duplicate requests never reach the DP");
        assertEquals(5, calculator.capacityCacheHits() + calculator.capacityCacheMisses(),
                "every patrol request is accounted for as either a hit or a miss");
    }

    @Test
    @DisplayName("DECISION_OUTPUT_IS_UNCHANGED_BY_MEMOIZATION")
    void DECISION_OUTPUT_IS_UNCHANGED_BY_MEMOIZATION() {
        // Pinned pre-memoization figures of the wide-Pareto fixture, including the diagnostics: the memo
        // returns the cached dpStatesEvaluated, so even the work counter a patrol reports is unchanged.
        DayState state = state(List.of(patrol(0, 0, 20), patrol(1, 0, 20), patrol(2, WIDTH, 20)),
                GENEROUS_NEXT_DAY_BUDGET, FOUR_SPOTS);
        TeamNextDayHarvestCapacity team = evaluate(
                NextDayHarvestCapacityCalculator.forState(state), state);

        assertEquals(List.of(115, 115, 87), team.patrols().stream()
                .map(PatrolNextDayHarvestCapacity::dpStatesEvaluated).toList());
        assertEquals(317, team.totalDpStatesEvaluated(), "115 + 115 + 87, exactly as without the memo");
        assertEquals(List.of(4, 4, 4), team.patrols().stream()
                .map(PatrolNextDayHarvestCapacity::maxReachableDistinctSpots).toList());
        assertEquals(List.of(8, 8, 9), team.patrols().stream()
                .map(PatrolNextDayHarvestCapacity::bestRemainingFuelAtMaxSpotCount).toList(),
                "the two ROAD starts keep 8 fuel, the frugal PLAIN start keeps 9");
        assertEquals(4, team.minimumPatrolDistinctSpots());
        assertEquals(4, team.minimumPatrolDistinctBrands());
        assertEquals(12, team.totalPatrolDistinctSpotCapacity());
        assertEquals(12, team.totalPatrolDistinctBrandCapacity());
        assertEquals(60, team.totalProjectedPatrolFuel());
        assertEquals(4, team.pathfindingExecutions(),
                "still one reverse route search per opportunity, none per patrol");
    }

    private static List<Object> derived(PatrolNextDayHarvestCapacity value) {
        return List.of(value.stationaryOpportunityAvailable(), value.maxReachableDistinctSpots(),
                value.maxReachableDistinctBrands(), value.bestRemainingFuelAtMaxSpotCount(),
                value.dpStatesEvaluated());
    }

    /** Ground truth: one patrol per calculator, so no second request can possibly alias into the answer. */
    private static PatrolNextDayHarvestCapacity single(
            int position, int fuel, int nextDayBudget, List<UdonSpot> spots) {
        DayState state = state(List.of(patrol(0, position, fuel)), nextDayBudget, spots);
        return evaluate(NextDayHarvestCapacityCalculator.forState(state), state).patrols().getFirst();
    }

    private static TeamNextDayHarvestCapacity evaluate(
            NextDayHarvestCapacityCalculator calculator, DayState state) {
        return calculator.evaluate((ValidDaySimulationResult) new DaySimulator()
                .simulate(state, SafePlanFactory.waitAll(state)));
    }

    /** Row 0 is ROAD (fast, thirsty), row 1 is PLAIN (slow, frugal) and holds every opportunity. */
    private static DayState state(List<AgentState> agents, int nextDayBudget, List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[WIDTH * 2];
        Arrays.fill(terrain, Terrain.PLAIN);
        for (int column = 0; column < WIDTH; column++) {
            terrain[column] = Terrain.ROAD;
        }
        Map<Position, Integer> stock = new HashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        return new DayState(new StaticMatchData(
                new HexMap(WIDTH, 2, terrain), new DayStepBudgets(new int[] {6, nextDayBudget}),
                List.of(), new FuelCapacity(20), spots), new DayIndex(0), agents, Map.of(), stock);
    }

    private static AgentState patrol(int id, int position, int fuel) {
        return AgentState.patrol(new AgentId(id), new Position(position), fuel);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), new Position(position), 1);
    }
}
