package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;

/**
 * M16 coupled competitive rollout: one shared chronological stock timeline for both sides.
 *
 * <p>Every fixture is a single PLAIN row, so a move always costs two steps and one PATROL fuel and the
 * distance between two columns is exactly twice their gap. Each expected arrival step is therefore
 * derivable by hand from the official movement rules rather than from a fitted constant, and every
 * expected count below follows from those steps alone.</p>
 */
class M16CoupledCompetitiveRolloutTest {

    private static final AgentId OWN = new AgentId(0);
    private static final AgentId OWN_SECOND = new AgentId(1);
    private static final int UNKNOWN_FUEL = -1;

    /**
     * The signature M15 defect: a frozen own event the opponent had already beaten.
     *
     * <p>Our PATROL is planned onto spot 4 at step 1 and onto spot 9 at step 15. The opponent's
     * unopposed baseline is spot 4 at step 2 then spot 9 at step 12. Once our earlier arrival takes spot
     * 4 the opponent reroutes straight to spot 9 and gets there at step 12, three steps before us. M15
     * kept spot 9 frozen as a guaranteed own collection; the coupled timeline shows it collects nothing,
     * and the dead event removes no stock afterwards.</p>
     */
    @Test
    void anOpponentRerouteInvalidatesOurLaterPlannedArrivalAndItRemovesNoStock() {
        DayState state = lineState(21, 20, spots(4, 9), group(7, agent(3, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveBaseline baseline = rollout.baseline();
        CoupledCompetitiveRolloutResult coupled =
                rollout.evaluate(baseline, events(event(4, 1), event(9, 15)));

        assertEquals(2, baseline.totalCollections(),
                "Unopposed the collector takes spot 4 at step 2 and spot 9 at step 12");
        assertEquals(2, coupled.plannedOwnOpportunityEvents().size());
        assertEquals(1, coupled.coupledOwnCollections(), "Only the step 1 arrival on spot 4 collects");
        assertEquals(1, coupled.ownPlannedEventsInvalidatedByOpponent());
        assertEquals(0, coupled.ownPlannedEventsExhaustedByOwnTeam());
        assertEquals(CoupledOwnEventOutcome.COLLECTED, coupled.ownEventResults().get(0).outcome());
        assertEquals(CoupledOwnEventOutcome.INVALIDATED_BY_OPPONENT,
                coupled.ownEventResults().get(1).outcome());
        assertEquals(1, coupled.coupledOpponentCollections());
        assertEquals(new Position(9), coupled.coupledOpponentClaims().getFirst().spot());
        assertEquals(12, coupled.coupledOpponentClaims().getFirst().arrivalStep());
        assertEquals(1, coupled.opponentCollectionsRemovedVsBaseline());
        assertEquals(0, coupled.projectedCoupledMargin());
    }

    /**
     * The same fixture under the M15 frozen-collection model, to pin the difference numerically.
     *
     * <p>M15 counts both of our planned collections as guaranteed against one surviving opponent
     * collection, so it reports a margin of plus one. The coupled rollout reports zero, because our spot
     * 9 arrival really collects nothing. M16 is strictly less optimistic for us on exactly the case the
     * model was built to fix.</p>
     */
    @Test
    void theCoupledMarginIsLessOptimisticThanTheM15FrozenResidualOnTheSameFixture() {
        DayState state = lineState(21, 20, spots(4, 9), group(7, agent(3, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout m15 = FullDayOpponentHarvestRollout.forState(
                state, OpponentIntentConfig.defaults());
        OpponentFullDayResidualEvaluation residual = m15.evaluate(m15.baseline(), List.of(
                new FullDayOpponentHarvestRollout.OwnSemiCollection(new Position(4), 1),
                new FullDayOpponentHarvestRollout.OwnSemiCollection(new Position(9), 15)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveRolloutResult coupled =
                rollout.evaluate(rollout.baseline(), events(event(4, 1), event(9, 15)));

        // M15 freezes both own semi collections, so its margin is two minus the residual.
        int m15Margin = 2 - residual.residualOpponentCollections();
        assertEquals(1, residual.residualOpponentCollections());
        assertEquals(1, m15Margin, "M15 treats both frozen own collections as guaranteed");
        assertEquals(1, coupled.coupledOwnCollections(),
                "The coupled timeline realizes only one of the two planned arrivals");
        assertEquals(0, coupled.projectedCoupledMargin());
        assertTrue(coupled.projectedCoupledMargin() < m15Margin,
                "M16 must be less optimistic than M15 when a reroute steals our later stock");
    }

    @Test
    void anOwnArrivalStrictlyEarlierKeepsTheStockAndTheOpponentNeverReachesIt() {
        DayState state = lineState(9, 10, spots(4), group(7, agent(3, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveBaseline baseline = rollout.baseline();
        CoupledCompetitiveRolloutResult coupled = rollout.evaluate(baseline, events(event(4, 1)));

        assertEquals(1, baseline.totalCollections());
        assertEquals(1, coupled.coupledOwnCollections());
        assertEquals(0, coupled.coupledOpponentCollections());
        assertEquals(0, coupled.ownPlannedEventsInvalidatedByOpponent());
        assertEquals(1, coupled.projectedCoupledMargin());
    }

    @Test
    void anOpponentArrivalStrictlyEarlierEmptiesTheSpotBeforeOurPlannedArrival() {
        DayState state = lineState(9, 10, spots(4), group(7, agent(3, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveRolloutResult coupled =
                rollout.evaluate(rollout.baseline(), events(event(4, 5)));

        assertEquals(0, coupled.coupledOwnCollections());
        assertEquals(1, coupled.coupledOpponentCollections());
        assertEquals(2, coupled.coupledOpponentClaims().getFirst().arrivalStep());
        assertEquals(1, coupled.ownPlannedEventsInvalidatedByOpponent());
        assertEquals(0, coupled.opponentCollectionsRemovedVsBaseline());
        assertEquals(-1, coupled.projectedCoupledMargin());
    }

    /**
     * Equal-step semantics, documented and pinned.
     *
     * <p>The opponent is resolved first at an identical step. A single portion that cannot satisfy both
     * sides is therefore never counted as a guaranteed own collection, which is conservative from our
     * perspective, and the tie never removes an opponent collection either, so a tie can never
     * manufacture denial in our favour.</p>
     */
    @Test
    void anEqualStepNeverCountsAsAGuaranteedOwnCollectionAndNeverFakesDenial() {
        DayState state = lineState(9, 10, spots(4), group(7, agent(3, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveRolloutResult coupled =
                rollout.evaluate(rollout.baseline(), events(event(4, 2)));

        assertEquals(2, coupled.coupledOpponentClaims().getFirst().arrivalStep());
        assertEquals(2, coupled.plannedOwnOpportunityEvents().getFirst().arrivalStep());
        assertEquals(0, coupled.coupledOwnCollections());
        assertEquals(1, coupled.coupledOpponentCollections());
        assertEquals(0, coupled.opponentCollectionsRemovedVsBaseline(),
                "A tie must not be reported as an opponent collection we removed");
        assertEquals(1, coupled.equalStepContests());
        assertTrue(coupled.ownEventResults().getFirst().equalStepContest());
    }

    @Test
    void anEqualStepOnAStockOfTwoSatisfiesBothSidesSoTheTieCostsUsNothing() {
        DayState state = lineStateWithStock(9, 10, Map.of(4, 2), group(7, agent(3, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveRolloutResult coupled =
                rollout.evaluate(rollout.baseline(), events(event(4, 2)));

        assertEquals(1, coupled.coupledOwnCollections());
        assertEquals(1, coupled.coupledOpponentCollections());
        assertEquals(0, coupled.ownPlannedEventsInvalidatedByOpponent());
        assertEquals(1, coupled.equalStepContests());
        assertTrue(coupled.ownEventResults().getFirst().collected());
        assertEquals(0, coupled.projectedCoupledMargin());
    }

    @Test
    void oneStockOfTwoIsSharedByTheWholeOpponentTeamAndLeavesNothingForOurLaterArrival() {
        DayState state = lineStateWithStock(9, 10, Map.of(4, 2),
                group(7, agent(3, UNKNOWN_FUEL), agent(5, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveBaseline baseline = rollout.baseline();
        CoupledCompetitiveRolloutResult coupled = rollout.evaluate(baseline, events(event(4, 6)));

        assertEquals(2, baseline.totalCollections());
        assertEquals(1, baseline.maxCollectorCollections());
        assertEquals(2, coupled.coupledOpponentCollections());
        assertEquals(0, coupled.coupledOwnCollections());
        assertEquals(1, coupled.ownPlannedEventsInvalidatedByOpponent());
    }

    /**
     * Second-order interaction: the reroute our early collection forces then feeds the later stock.
     *
     * <p>Our PATROL takes spot 2 at step 3 and is planned onto spot 6 at step 15. The opponent's
     * unopposed baseline is spot 2, spot 6, spot 10. Losing spot 2 sends it to spot 6 at step 12, which
     * kills our step 15 arrival; its next leg is then chosen against the UPDATED stock, in which spot 2
     * is already empty, so it goes on to spot 10 instead of wasting the trip back.</p>
     */
    @Test
    void aRerouteInvalidatesOurLaterArrivalAndTheNextOpponentLegUsesTheUpdatedStock() {
        DayState state = lineState(13, 30, spots(2, 6, 10), group(7, agent(0, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveBaseline baseline = rollout.baseline();
        CoupledCompetitiveRolloutResult coupled =
                rollout.evaluate(baseline, events(event(2, 3), event(6, 15)));

        assertEquals(3, baseline.totalCollections());
        assertEquals(1, coupled.coupledOwnCollections());
        assertEquals(1, coupled.ownPlannedEventsInvalidatedByOpponent());
        assertEquals(2, coupled.coupledOpponentCollections());
        assertEquals(List.of(new Position(6), new Position(10)),
                coupled.coupledOpponentClaims().stream()
                        .map(OpponentFullDayClaim::spot).toList());
        assertEquals(List.of(12, 20), coupled.coupledOpponentClaims().stream()
                .map(OpponentFullDayClaim::arrivalStep).toList());
        assertEquals(1, coupled.opponentCollectionsRemovedVsBaseline());
        assertEquals(-1, coupled.projectedCoupledMargin());
    }

    /**
     * Full replacement: we take a spot but the opponent's whole-day total does not move.
     *
     * <p>The unopposed baseline is spot 4 at step 4 then spot 8 at step 12. Our step 3 arrival takes
     * spot 4, so the collector opens on spot 8 at step 4 and picks up spot 10 at step 8 instead. Two
     * collections either way, so nothing was denied and one collection is a replacement.</p>
     */
    @Test
    void anOpponentCanFullyReplaceWhatWeTookWithoutLosingAnyCollection() {
        DayState state = lineState(13, 14, spots(4, 8, 10), group(7, agent(6, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveBaseline baseline = rollout.baseline();
        CoupledCompetitiveRolloutResult coupled = rollout.evaluate(baseline, events(event(4, 3)));

        assertEquals(2, baseline.totalCollections());
        assertEquals(List.of(new Position(4), new Position(8)),
                baseline.claims().stream().map(OpponentFullDayClaim::spot).toList());
        assertEquals(2, coupled.coupledOpponentCollections());
        assertEquals(List.of(new Position(8), new Position(10)),
                coupled.coupledOpponentClaims().stream()
                        .map(OpponentFullDayClaim::spot).toList());
        assertEquals(0, coupled.opponentCollectionsRemovedVsBaseline(), "Full replacement");
        assertEquals(1, coupled.opponentReplacementCollections());
        assertEquals(1, coupled.coupledOwnCollections());
        assertEquals(-1, coupled.projectedCoupledMargin());
    }

    @Test
    void withNothingLeftToSubstituteTheOpponentLosesTheCollectionOutright() {
        DayState state = lineState(9, 10, spots(4), group(7, agent(3, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveRolloutResult coupled =
                rollout.evaluate(rollout.baseline(), events(event(4, 1)));

        assertEquals(1, coupled.opponentCollectionsRemovedVsBaseline());
        assertEquals(0, coupled.opponentReplacementCollections(), "No replacement was available");
        assertEquals(0, coupled.coupledOpponentCollections());
    }

    @Test
    void ourOwnTeamOverSubscribingASpotIsNotCountedAsAnOpponentDenial() {
        DayState state = twoPatrolLineState(9, 10, spots(4));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveRolloutResult coupled = rollout.evaluate(
                rollout.baseline(), events(event(0, 4, 1), event(1, 4, 3)));

        assertEquals(1, coupled.coupledOwnCollections());
        assertEquals(1, coupled.ownPlannedEventsExhaustedByOwnTeam());
        assertEquals(0, coupled.ownPlannedEventsInvalidatedByOpponent());
        assertEquals(CoupledOwnEventOutcome.EXHAUSTED_BY_OWN_TEAM,
                coupled.ownEventResults().get(1).outcome());
    }

    @Test
    void bothSidesDrawFromOneSharedStockSoNoPortionIsEverCountedTwice() {
        DayState state = twoPatrolLineStateWithStock(9, 10, Map.of(4, 3),
                group(7, agent(3, UNKNOWN_FUEL), agent(5, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveRolloutResult coupled = rollout.evaluate(
                rollout.baseline(), events(event(0, 4, 4), event(1, 4, 6)));

        assertEquals(2, coupled.coupledOpponentCollections());
        assertEquals(1, coupled.coupledOwnCollections());
        assertEquals(3, coupled.coupledOwnCollections() + coupled.coupledOpponentCollections(),
                "Three portions can never yield four collections across both sides");
        assertEquals(1, coupled.ownPlannedEventsInvalidatedByOpponent());
    }

    @Test
    void theCoupledOwnBrandCountOnlyCountsBrandsWeReallyCollect() {
        DayState state = lineState(21, 20, spots(4, 9), group(7, agent(3, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveRolloutResult coupled =
                rollout.evaluate(rollout.baseline(), events(event(4, 1), event(9, 15)));

        assertEquals(1, coupled.coupledOwnBrands());
        assertEquals(new BrandId("B4"), coupled.ownEventResults().getFirst().brand());
        assertEquals(new BrandId("B9"), coupled.ownEventResults().get(1).brand());
        assertFalse(coupled.ownEventResults().get(1).collected());
    }

    @Test
    void oneReverseParetoPassPerSpotServesTheBaselineAndEveryCoupledRolloutAlike() {
        DayState state = lineState(13, 30, spots(2, 6, 10), group(7, agent(0, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveBaseline baseline = rollout.baseline();
        CoupledCompetitiveRolloutResult first =
                rollout.evaluate(baseline, events(event(2, 3), event(6, 15)));
        CoupledCompetitiveRolloutResult second = rollout.evaluate(baseline, events(event(10, 1)));

        assertEquals(3, rollout.spotCount());
        assertEquals(rollout.spotCount(), rollout.pathfindingExecutions(),
                "Exactly one reverse-Pareto pass per Udon spot, never one per plan or per event");
        assertEquals(rollout.pathfindingExecutions(), baseline.pathfindingExecutions());
        assertEquals(rollout.routeCostCacheEntries(), baseline.routeCostCacheEntries());
        assertTrue(baseline.rolloutEvents() <= rollout.maxRolloutEvents(0));
        assertTrue(first.rolloutEvents() <= rollout.maxRolloutEvents(2));
        assertTrue(second.rolloutEvents() <= rollout.maxRolloutEvents(1));
    }

    @Test
    void withNoEligibleCollectorNoPathfindingRunsAtAll() {
        DayState withoutOthers = lineState(9, 4, spots(3));
        DayState withOnlyNonCollectors =
                lineState(9, 4, spots(3), group(7, agent(4, UNKNOWN_FUEL, 1)));

        assertEquals(0, rollout(withoutOthers).pathfindingExecutions());
        assertEquals(0, rollout(withoutOthers).routeCostCacheEntries());
        assertEquals(0, rollout(withOnlyNonCollectors).pathfindingExecutions());
        assertEquals(0, rollout(withOnlyNonCollectors).baseline().totalCollections());
    }

    @Test
    void differentOwnPlansProduceDifferentCoupledOutcomesFromOneSharedBaseline() {
        DayState state = lineState(21, 20, spots(4, 9), group(7, agent(3, UNKNOWN_FUEL)));
        CoupledCompetitiveRollout rollout = rollout(state);
        CoupledCompetitiveBaseline baseline = rollout.baseline();

        CoupledCompetitiveRolloutResult untouched = rollout.evaluate(baseline, events());
        CoupledCompetitiveRolloutResult contested =
                rollout.evaluate(baseline, events(event(4, 1), event(9, 15)));

        assertEquals(2, untouched.coupledOpponentCollections());
        assertEquals(0, untouched.opponentCollectionsRemovedVsBaseline());
        assertEquals(0, untouched.coupledOwnCollections());
        assertEquals(-2, untouched.projectedCoupledMargin());
        assertEquals(1, contested.coupledOpponentCollections());
        assertEquals(0, contested.projectedCoupledMargin());
    }

    private static CoupledCompetitiveRollout rollout(DayState state) {
        return CoupledCompetitiveRollout.forState(state, OpponentIntentConfig.defaults());
    }

    /** Immutable planned arrivals in the deterministic order the day simulator resolves them. */
    private static List<PlannedOwnOpportunityEvent> events(int[]... rawEvents) {
        List<int[]> ordered = new ArrayList<>(Arrays.asList(rawEvents));
        ordered.sort(Comparator.<int[]>comparingInt(raw -> raw[1])
                .thenComparingInt(raw -> raw[2])
                .thenComparingInt(raw -> raw[0]));
        List<PlannedOwnOpportunityEvent> events = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            int[] raw = ordered.get(index);
            events.add(new PlannedOwnOpportunityEvent(
                    new AgentId(raw[2]), new Position(raw[0]), raw[1], index));
        }
        return List.copyOf(events);
    }

    private static int[] event(int spotColumn, int arrivalStep) {
        return event(0, spotColumn, arrivalStep);
    }

    private static int[] event(int agentOrdinal, int spotColumn, int arrivalStep) {
        return new int[] {spotColumn, arrivalStep, agentOrdinal};
    }

    private static List<Integer> spots(int... columns) {
        List<Integer> values = new ArrayList<>();
        for (int column : columns) {
            values.add(column);
        }
        return List.copyOf(values);
    }

    private static ObservedOtherAgent agent(int column, int fuel) {
        return agent(column, fuel, 0);
    }

    private static ObservedOtherAgent agent(int column, int fuel, int rawKind) {
        return new ObservedOtherAgent(new Position(column), rawKind, fuel);
    }

    private static ObservedOtherGroup group(int rawId, ObservedOtherAgent... agents) {
        return new ObservedOtherGroup(rawId, List.of(agents));
    }

    /** One PLAIN row; every listed column carries one Udon portion of its own brand. */
    private static DayState lineState(
            int width, int stepBudget, List<Integer> spotColumns, ObservedOtherGroup... others) {
        return lineStateWithStock(width, stepBudget, unitStock(spotColumns), others);
    }

    private static DayState lineStateWithStock(
            int width, int stepBudget, Map<Integer, Integer> stockByColumn,
            ObservedOtherGroup... others) {
        return state(width, stepBudget, stockByColumn,
                List.of(AgentState.patrol(OWN, new Position(0), 20)), others);
    }

    private static DayState twoPatrolLineState(
            int width, int stepBudget, List<Integer> spotColumns, ObservedOtherGroup... others) {
        return twoPatrolLineStateWithStock(width, stepBudget, unitStock(spotColumns), others);
    }

    private static DayState twoPatrolLineStateWithStock(
            int width, int stepBudget, Map<Integer, Integer> stockByColumn,
            ObservedOtherGroup... others) {
        return state(width, stepBudget, stockByColumn, List.of(
                AgentState.patrol(OWN, new Position(0), 20),
                AgentState.patrol(OWN_SECOND, new Position(1), 20)), others);
    }

    private static Map<Integer, Integer> unitStock(List<Integer> spotColumns) {
        Map<Integer, Integer> stock = new LinkedHashMap<>();
        spotColumns.forEach(column -> stock.put(column, 1));
        return stock;
    }

    private static DayState state(
            int width, int stepBudget, Map<Integer, Integer> stockByColumn,
            List<AgentState> agents, ObservedOtherGroup... others) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<UdonSpot> spots = new ArrayList<>();
        Map<Position, Integer> stock = new LinkedHashMap<>();
        stockByColumn.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Position position = new Position(entry.getKey());
                    spots.add(new UdonSpot(
                            new BrandId("B" + entry.getKey()), position, entry.getValue()));
                    stock.put(position, entry.getValue());
                });
        return new DayState(
                new StaticMatchData(new HexMap(width, 1, terrain),
                        new DayStepBudgets(new int[] {stepBudget}), List.of(),
                        new FuelCapacity(20), List.copyOf(spots)),
                new DayIndex(0),
                List.copyOf(agents),
                Map.of(), stock, List.of(others));
    }
}
