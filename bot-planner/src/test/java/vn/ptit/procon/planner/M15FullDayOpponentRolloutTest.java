package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
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
 * M15 full-day replacement-aware opponent rollout.
 *
 * <p>Every fixture is a single PLAIN row, so a move always costs two steps and one PATROL fuel and the
 * distance between two columns is exactly twice their gap. That keeps each expected arrival step
 * derivable by hand from the official movement rules rather than from a fitted constant.</p>
 */
class M15FullDayOpponentRolloutTest {

    private static final AgentId OWN = new AgentId(0);
    private static final int UNKNOWN_FUEL = -1;

    @Test
    void oneCollectorHarvestsFarBeyondTheOldTopThreeTargetLimit() {
        DayState state = lineState(13, 14, spots(1, 2, 3, 4, 5, 6, 7),
                group(7, agent(0, 0, UNKNOWN_FUEL)));
        OpponentFullDayBaseline baseline = rollout(state).baseline();

        assertEquals(7, baseline.totalCollections());
        assertEquals(7, baseline.maxCollectorCollections());
        assertTrue(baseline.maxCollectorCollections() >= 6,
                "A single collector must be able to exceed four, five and six collections");
        assertEquals(1, baseline.collectorCount());
    }

    @Test
    void theFirstFutureCollectionIsDirectAndEveryLaterOneStaysFollowOn() {
        DayState state = lineState(13, 14, spots(1, 2, 3, 4, 5, 6, 7),
                group(7, agent(0, 0, UNKNOWN_FUEL)));
        OpponentFullDayBaseline baseline = rollout(state).baseline();

        assertEquals(0, baseline.observedNowCollections());
        assertEquals(1, baseline.directIntentCollections());
        assertEquals(6, baseline.followOnIntentCollections());
        assertEquals(2, baseline.claims().getFirst().arrivalStep());
        assertEquals(OpponentClaimCommitment.DIRECT_INTENT, baseline.claims().getFirst().commitment());
        for (OpponentFullDayClaim claim : baseline.claims().subList(1, baseline.claims().size())) {
            assertEquals(OpponentClaimCommitment.FOLLOW_ON_INTENT, claim.commitment());
        }
    }

    @Test
    void standingOnAStockedSpotIsObservedNowAndOnlyStepZeroCanBe() {
        DayState state = lineState(9, 4, spots(2, 3, 4), group(7, agent(0, 2, UNKNOWN_FUEL)));
        OpponentFullDayBaseline baseline = rollout(state).baseline();

        assertEquals(3, baseline.totalCollections());
        assertEquals(1, baseline.observedNowCollections());
        assertEquals(1, baseline.directIntentCollections());
        assertEquals(1, baseline.followOnIntentCollections());
        assertEquals(List.of(0, 2, 4),
                baseline.claims().stream().map(OpponentFullDayClaim::arrivalStep).toList());
        for (OpponentFullDayClaim claim : baseline.claims()) {
            assertEquals(claim.arrivalStep() == 0,
                    claim.commitment() == OpponentClaimCommitment.OBSERVED_NOW);
        }
    }

    @Test
    void takingTheNearestSpotMakesTheCollectorRerouteToAnEqualCountReplacement() {
        DayState state = lineState(9, 2, spots(3, 5), group(7, agent(0, 4, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout rollout = rollout(state);
        OpponentFullDayBaseline baseline = rollout.baseline();
        OpponentFullDayResidualEvaluation residual =
                rollout.evaluate(baseline, List.of(ownCollection(3, 1)));

        assertEquals(1, baseline.totalCollections());
        assertEquals(new Position(3), baseline.claims().getFirst().spot());
        assertEquals(1, residual.residualOpponentCollections());
        assertEquals(new Position(5), residual.residualClaims().getFirst().spot());
        assertEquals(0, residual.netOpponentCollectionsRemoved());
        assertEquals(1, residual.replacementCollections());
    }

    @Test
    void replacementCanLeaveMoreStepBudgetSoTheNetRemovedMetricIsSigned() {
        DayState state = lineState(9, 8, spots(3, 7, 8), group(7, agent(0, 4, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout rollout = rollout(state);
        OpponentFullDayBaseline baseline = rollout.baseline();
        OpponentFullDayResidualEvaluation residual =
                rollout.evaluate(baseline, List.of(ownCollection(3, 1)));

        assertEquals(1, baseline.totalCollections());
        assertEquals(2, residual.residualOpponentCollections());
        assertEquals(-1, residual.netOpponentCollectionsRemoved());
        assertEquals(2, residual.replacementCollections());
    }

    @Test
    void takingTheOnlyStockRemovesTheCollectionWhenNoReplacementIsReachable() {
        DayState state = lineState(9, 2, spots(3), group(7, agent(0, 4, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout rollout = rollout(state);
        OpponentFullDayBaseline baseline = rollout.baseline();
        OpponentFullDayResidualEvaluation residual =
                rollout.evaluate(baseline, List.of(ownCollection(3, 1)));

        assertEquals(1, baseline.totalCollections());
        assertEquals(0, residual.residualOpponentCollections());
        assertEquals(1, residual.netOpponentCollectionsRemoved());
        assertEquals(0, residual.replacementCollections());
    }

    @Test
    void anEqualArrivalStepNeverRemovesAnOpponentCollection() {
        DayState state = lineState(9, 2, spots(3, 5), group(7, agent(0, 4, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout rollout = rollout(state);
        OpponentFullDayBaseline baseline = rollout.baseline();
        OpponentFullDayResidualEvaluation residual =
                rollout.evaluate(baseline, List.of(ownCollection(3, 2)));

        assertEquals(new Position(3), residual.residualClaims().getFirst().spot());
        assertEquals(0, residual.netOpponentCollectionsRemoved());
        assertEquals(0, residual.replacementCollections());
    }

    @Test
    void anOwnCollectionAfterTheOpponentArrivalCannotAffectIt() {
        DayState state = lineState(9, 2, spots(3, 5), group(7, agent(0, 4, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout rollout = rollout(state);
        OpponentFullDayBaseline baseline = rollout.baseline();
        OpponentFullDayResidualEvaluation residual =
                rollout.evaluate(baseline, List.of(ownCollection(3, 5)));

        assertEquals(new Position(3), residual.residualClaims().getFirst().spot());
        assertEquals(0, residual.netOpponentCollectionsRemoved());
    }

    @Test
    void theWholeOpponentTeamSharesOneResidualStockModel() {
        DayState state = lineState(9, 4, spots(4),
                group(7, agent(0, 3, UNKNOWN_FUEL), agent(1, 5, UNKNOWN_FUEL)));
        OpponentFullDayBaseline baseline = rollout(state).baseline();

        assertEquals(2, baseline.collectorCount());
        assertEquals(1, baseline.totalCollections());
        assertEquals(1, baseline.maxCollectorCollections());
        assertEquals(1, baseline.claimsAt(new Position(4)).size());
    }

    @Test
    void aNonCollectingObservedAgentIsNeverTreatedAsACollector() {
        DayState state = lineState(9, 4, spots(3), group(7, agent(0, 4, UNKNOWN_FUEL, 1)));

        OpponentFullDayBaseline production = rollout(state).baseline();
        OpponentFullDayBaseline diagnostic = FullDayOpponentHarvestRollout.forState(state,
                new OpponentIntentConfig(3, OpponentCollectionEligibility.ALL_OBSERVED_COLLECT))
                .baseline();

        assertEquals(0, production.collectorCount());
        assertEquals(0, production.totalCollections());
        assertEquals(1, diagnostic.collectorCount());
        assertEquals(1, diagnostic.totalCollections());
    }

    @Test
    void unknownNegativeOpponentFuelIsUnboundedRatherThanEmpty() {
        DayState state = lineState(13, 14, spots(1, 2, 3, 4, 5, 6, 7),
                group(7, agent(0, 0, UNKNOWN_FUEL)));

        assertEquals(7, rollout(state).baseline().totalCollections());
    }

    @Test
    void anObservedPositiveFuelBoundsTheFullDayHarvest() {
        DayState state = lineState(13, 14, spots(1, 2, 3, 4, 5, 6, 7),
                group(7, agent(0, 0, 3)));

        assertEquals(3, rollout(state).baseline().totalCollections());
    }

    @Test
    void everyResidualRolloutReusesTheOneRouteCacheWithZeroFurtherPathfinding() {
        DayState state = lineState(13, 14, spots(1, 2, 3, 4, 5, 6, 7),
                group(7, agent(0, 0, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout rollout = rollout(state);
        OpponentFullDayBaseline baseline = rollout.baseline();
        int afterBaseline = rollout.pathfindingExecutions();
        for (int step = 1; step <= 6; step++) {
            rollout.evaluate(baseline, List.of(ownCollection(step, step)));
        }

        assertEquals(rollout.spotCount(), afterBaseline);
        assertEquals(afterBaseline, rollout.pathfindingExecutions());
        assertEquals(baseline.pathfindingExecutions(), rollout.pathfindingExecutions());
        assertTrue(rollout.routeCostCacheEntries() > 0);
        assertEquals(baseline.routeCostCacheEntries(), rollout.routeCostCacheEntries());
    }

    @Test
    void theBaselineIsDeterministicAndUnchangedByResidualEvaluation() {
        DayState state = lineState(13, 14, spots(1, 2, 3, 4, 5, 6, 7),
                group(7, agent(0, 0, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout rollout = rollout(state);
        OpponentFullDayBaseline first = rollout.baseline();
        OpponentFullDayResidualEvaluation residual =
                rollout.evaluate(first, List.of(ownCollection(1, 1)));

        assertEquals(first, rollout(state).baseline());
        assertEquals(first, rollout.baseline());
        assertSame(first, residual.baseline());
        assertEquals(first.totalCollections(), residual.baselineOpponentCollections());
    }

    @Test
    void theRolloutStaysWithinItsEventBoundOnADenseContestedFixture() {
        DayState state = lineState(13, 12, spots(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
                group(7, agent(0, 0, UNKNOWN_FUEL), agent(1, 6, UNKNOWN_FUEL),
                        agent(2, 11, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout rollout = rollout(state);
        OpponentFullDayBaseline baseline = rollout.baseline();
        OpponentFullDayResidualEvaluation residual = rollout.evaluate(baseline,
                List.of(ownCollection(1, 1), ownCollection(6, 1), ownCollection(11, 1)));

        assertEquals(3, baseline.collectorCount());
        assertTrue(baseline.rolloutEvents() > 0);
        assertTrue(baseline.rolloutEvents()
                <= baseline.collectorCount() * (2 * rollout.spotCount() + 2) + 1);
        assertTrue(residual.rolloutEvents()
                <= baseline.collectorCount() * (2 * rollout.spotCount() + 2) + 1);
    }

    @Test
    void anObservedNowClaimMustBeExactlyTheStepZeroCollection() {
        assertThrows(IllegalArgumentException.class, () -> new OpponentFullDayClaim(
                7, 0, 0, new Position(1), 5, 0, 4, 2, OpponentClaimCommitment.OBSERVED_NOW));
        assertThrows(IllegalArgumentException.class, () -> new OpponentFullDayClaim(
                7, 0, 0, new Position(1), 0, 0, 0, 0, OpponentClaimCommitment.DIRECT_INTENT));
    }

    @Test
    void theEmptyBaselineIsUsedByEveryModeThatDoesNotRunTheFullDayRollout() {
        OpponentFullDayBaseline empty = OpponentFullDayBaseline.empty();

        assertEquals(0, empty.totalCollections());
        assertEquals(0, empty.strongCollections());
        assertEquals(0, empty.collectorCount());
        assertEquals(0, empty.pathfindingExecutions());
        assertEquals(List.of(), empty.claimsAt(new Position(1)));
    }

    /** Column 8 to column 3 costs ten steps, which the day's eight-step budget cannot pay. */
    @Test
    void anOpponentThatCannotReachAnySpotHarvestsNothingButStillCostsOneSearchPerSpot() {
        DayState state = lineState(9, 8, spots(3), group(7, agent(0, 8, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout rollout = rollout(state);

        assertEquals(0, rollout.baseline().totalCollections());
        assertEquals(rollout.spotCount(), rollout.pathfindingExecutions());
        assertTrue(rollout.routeCostCacheEntries() > 0,
                "The one search per spot still populates the cache for the cells it does reach");
    }

    @Test
    void withNoEligibleCollectorNoPathfindingRunsAtAll() {
        DayState withoutOthers = lineState(9, 4, spots(3));
        DayState withOnlyNonCollectors =
                lineState(9, 4, spots(3), group(7, agent(0, 4, UNKNOWN_FUEL, 1)));

        assertEquals(0, rollout(withoutOthers).pathfindingExecutions());
        assertEquals(0, rollout(withoutOthers).routeCostCacheEntries());
        assertEquals(0, rollout(withOnlyNonCollectors).pathfindingExecutions());
        assertEquals(0, rollout(withOnlyNonCollectors).baseline().totalCollections());
    }

    @Test
    void aStockOfTwoLetsTwoCollectorsShareOneSpot() {
        DayState state = lineStateWithStock(9, 4, Map.of(4, 2),
                group(7, agent(0, 3, UNKNOWN_FUEL), agent(1, 5, UNKNOWN_FUEL)));
        OpponentFullDayBaseline baseline = rollout(state).baseline();

        assertEquals(2, baseline.totalCollections());
        assertEquals(2, baseline.claimsAt(new Position(4)).size());
        assertEquals(1, baseline.maxCollectorCollections());
    }

    @Test
    void ourOwnCollectionRemovesOnlyOneOfTwoSharedPortions() {
        DayState state = lineStateWithStock(9, 4, Map.of(4, 2),
                group(7, agent(0, 3, UNKNOWN_FUEL), agent(1, 5, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout rollout = rollout(state);
        OpponentFullDayBaseline baseline = rollout.baseline();
        OpponentFullDayResidualEvaluation residual =
                rollout.evaluate(baseline, List.of(ownCollection(4, 1)));

        assertEquals(2, baseline.totalCollections());
        assertEquals(1, residual.residualOpponentCollections());
        assertEquals(1, residual.netOpponentCollectionsRemoved());
        assertEquals(0, residual.replacementCollections());
    }

    @Test
    void differentOwnPlansProduceDifferentResidualsFromOneSharedBaseline() {
        DayState state = lineState(9, 8, spots(3, 7, 8), group(7, agent(0, 4, UNKNOWN_FUEL)));
        FullDayOpponentHarvestRollout rollout = rollout(state);
        OpponentFullDayBaseline baseline = rollout.baseline();

        OpponentFullDayResidualEvaluation untouched = rollout.evaluate(baseline, List.of());
        OpponentFullDayResidualEvaluation contested =
                rollout.evaluate(baseline, List.of(ownCollection(3, 1)));

        assertEquals(1, untouched.residualOpponentCollections());
        assertEquals(0, untouched.netOpponentCollectionsRemoved());
        assertEquals(0, untouched.replacementCollections());
        assertNotEquals(untouched.residualOpponentCollections(),
                contested.residualOpponentCollections());
    }

    private static FullDayOpponentHarvestRollout rollout(DayState state) {
        return FullDayOpponentHarvestRollout.forState(state, OpponentIntentConfig.defaults());
    }

    private static FullDayOpponentHarvestRollout.OwnSemiCollection ownCollection(int spot, int step) {
        return new FullDayOpponentHarvestRollout.OwnSemiCollection(new Position(spot), step);
    }

    private static List<Integer> spots(int... columns) {
        List<Integer> values = new ArrayList<>();
        for (int column : columns) {
            values.add(column);
        }
        return List.copyOf(values);
    }

    private static ObservedOtherAgent agent(int ignoredIndex, int column, int fuel) {
        return agent(ignoredIndex, column, fuel, 0);
    }

    private static ObservedOtherAgent agent(int ignoredIndex, int column, int fuel, int rawKind) {
        return new ObservedOtherAgent(new Position(column), rawKind, fuel);
    }

    private static ObservedOtherGroup group(int rawId, ObservedOtherAgent... agents) {
        return new ObservedOtherGroup(rawId, List.of(agents));
    }

    /** One PLAIN row; every listed column carries one Udon portion of its own brand. */
    private static DayState lineState(
            int width, int stepBudget, List<Integer> spotColumns, ObservedOtherGroup... others) {
        Map<Integer, Integer> stock = new LinkedHashMap<>();
        spotColumns.forEach(column -> stock.put(column, 1));
        return lineStateWithStock(width, stepBudget, stock, others);
    }

    private static DayState lineStateWithStock(
            int width, int stepBudget, Map<Integer, Integer> stockByColumn,
            ObservedOtherGroup... others) {
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
                List.of(AgentState.patrol(OWN, new Position(0), 20)),
                Map.of(), stock, List.of(others));
    }
}
