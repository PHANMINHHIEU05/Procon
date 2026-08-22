package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
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
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/**
 * M12.1 whole-plan attribution, semi-commitment-realizable brand coverage and the three-way calibration.
 *
 * <p>Every fixture runs the real {@link DaySimulator} on a one-row map, so the collection steps under
 * test (2, 4 and 6) are the simulator's own; only the commitment forecast is hand-built, which is what
 * makes each claim's class exactly the one under test. The M10 binary and M12 hard-only counts come out
 * of the same pass as the M12.1 count, so {@code old <= semi <= commitment} is directly assertable on
 * the identical plan, forecast and event ordering.</p>
 *
 * <p>The brand fixture deliberately holds two redundant brand-D sources: a bounded reservation removing
 * one source must not remove the brand, which is the difference between losing a collection and losing
 * coverage.</p>
 */
class SemiCommitmentForecastEvaluatorTest {

    private static final AgentId PATROL = new AgentId(0);

    private static final AgentId SECOND_PATROL = new AgentId(1);

    private static final BrandId BRAND_D = new BrandId("D");

    private static final BrandId BRAND_E = new BrandId("E");

    private static final SemiCommitmentAdjustmentWeights WEIGHTS =
            SemiCommitmentAdjustmentWeights.defaults();

    /**
     * Section 32: brand D has two projected sources, one is lost to the bounded reservation, and the
     * brand stays semi-realizable through the other. Note M12 keeps all three collections here — a
     * future claim is not an observed collector — so this is exactly the optimism M12.1 corrects.
     */
    @Test
    void redundantBrandSourceSurvivesASemiLoss() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(3, 0));

        SemiCommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast(
                state, Map.of(position(1), List.of(directIntent(position(1), 0, 1)))));

        assertEquals(3, attribution.assessments().size());
        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.localProjectedBrands());
        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.semiCommitmentRealizableBrands(),
                "The second brand-D source keeps the brand covered");
        assertEquals(1, attribution.semiClaimedFirstCollections());
        assertEquals(0, attribution.hardClaimedFirstCollections());
        assertEquals(2, attribution.semiCommitmentRealizableCollections());
        assertEquals(3, attribution.commitmentRealizableCollections(),
                "M12 keeps the portion a future claim only predicts");
        assertEquals(2, attribution.oldForecastRealizableCollections());
    }

    /**
     * Section 33: every projected source of brand D is lost, one by hard depletion and one by the
     * bounded reservation, so the brand stops being semi-realizable.
     */
    @Test
    void brandWithEverySourceLostToHardAndSemiCapacityStopsBeingRealizable() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(3, 0));

        SemiCommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast(
                state, Map.of(
                        position(1), List.of(observedNow(position(1), 0)),
                        position(2), List.of(directIntent(position(2), 1, 3)))));

        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.localProjectedBrands());
        assertEquals(Set.of(BRAND_E), attribution.semiCommitmentRealizableBrands(),
                "Both brand-D sources are consumed, so the brand is gone");
        assertEquals(1, attribution.hardClaimedFirstCollections());
        assertEquals(1, attribution.semiClaimedFirstCollections());
        assertEquals(1, attribution.semiCommitmentRealizableCollections());
        assertEquals(2, attribution.commitmentRealizableCollections(),
                "M12 loses only the observed source");
    }

    /**
     * Sections 20, 26 and 33: two separate spots each hold one reservation, so both brand-D sources go
     * even though no observed collector is anywhere on the map.
     */
    @Test
    void reservationsOnBothBrandSourcesRemoveTheBrandWithoutAnyHardClaim() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(3, 0));

        SemiCommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast(
                state, Map.of(
                        position(1), List.of(directIntent(position(1), 0, 1)),
                        position(2), List.of(directIntent(position(2), 1, 3)))));

        assertEquals(0, attribution.hardClaimedFirstCollections());
        assertEquals(2, attribution.semiClaimedFirstCollections(),
                "One reserved portion at each of the two spots");
        assertEquals(Set.of(BRAND_E), attribution.semiCommitmentRealizableBrands());
        assertEquals(1, attribution.semiCommitmentRealizableCollections());
        assertEquals(3, attribution.commitmentRealizableCollections());
        assertEquals(1, attribution.oldForecastRealizableCollections());
    }

    /**
     * Sections 19 and 25 at whole-plan level: five direct claimers against two portions still reserve
     * one portion, so one of our two collections at the spot survives. The M10 model erased both.
     */
    @Test
    void fiveDirectClaimersAgainstTwoPortionsCostExactlyOneCollection() {
        DayState state = sharedSpotState();
        DaySimulationResult simulation = simulateTeam(state);

        SemiCommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast(
                state, Map.of(position(1), List.of(
                        directIntent(position(1), 0, 1),
                        directIntent(position(1), 1, 1),
                        directIntent(position(1), 2, 1),
                        directIntent(position(1), 3, 1),
                        directIntent(position(1), 4, 1)))));

        assertEquals(2, attribution.assessments().size(), "Two own collections at the shared spot");
        assertEquals(1, attribution.semiCommitmentRealizableCollections(),
                "One portion reserved of the two, whatever the claimer count");
        assertEquals(1, attribution.semiClaimedFirstCollections());
        assertEquals(1, attribution.directIntentBeforeCollections(),
                "The collection that still has capacity is a conflict, not a loss");
        assertEquals(2, attribution.commitmentRealizableCollections());
        assertEquals(0, attribution.oldForecastRealizableCollections(),
                "M10 charged all five claims against the two portions and erased both");
        assertEquals(Set.of(BRAND_D), attribution.semiCommitmentRealizableBrands());
    }

    /**
     * Section 38, the critical scaling invariant: once the single reservation at a spot is active, piling
     * further direct claimers onto that same spot must not take one more portion.
     *
     * <p>Only the claim count varies here. The map, the own plan, the simulated event ordering and every
     * other spot are byte for byte identical across the five runs, which is the only way the invariant is
     * actually isolated: a fixture that added real observed agents instead would also re-route the
     * forecaster onto other spots, and the resulting drop would say nothing about the per-spot cap.</p>
     */
    @Test
    void pilingDirectClaimersOntoOneReservedSpotNeverCostsAFurtherPortion() {
        DayState state = sharedSpotState(3);
        DaySimulationResult simulation = simulateTeam(state);
        List<Integer> semiByClaimerCount = new ArrayList<>();
        List<Integer> oldByClaimerCount = new ArrayList<>();

        for (int claimers = 1; claimers <= 5; claimers++) {
            List<CommittedOpponentClaim> claims = new ArrayList<>();
            for (int index = 0; index < claimers; index++) {
                claims.add(directIntent(position(1), index, 1));
            }
            OpponentCommitmentForecast forecast = forecast(state, Map.of(position(1), claims));
            SemiCommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast);
            String label = claimers + " direct claimers";

            assertEquals(1, SemiCommitmentForecast.derive(forecast).maxSemiReservedPortions(),
                    "The spot never reserves more than the one portion: " + label);
            assertEquals(2, attribution.assessments().size(), "Two own collections: " + label);
            semiByClaimerCount.add(attribution.semiCommitmentRealizableCollections());
            oldByClaimerCount.add(attribution.oldForecastRealizableCollections());
        }

        assertEquals(List.of(2, 2, 2, 2, 2), semiByClaimerCount,
                "Three portions minus the one reservation leaves both collections, at every count");
        assertEquals(List.of(2, 1, 0, 0, 0), oldByClaimerCount,
                "The M10 binary model keeps charging each further claimer until the spot is empty");
    }

    /**
     * Section 39: a direct claimer at a spot that carries no reservation yet costs at most that one new
     * spot's portion, and never more. Three single-portion sources are reserved one at a time.
     */
    @Test
    void eachNewlyReservedSpotCostsAtMostOnePortion() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(3, 0));
        List<Position> reserved = List.of(position(1), position(2), position(3));
        int previous = evaluate(state, simulation, forecast(state, Map.of()))
                .semiCommitmentRealizableCollections();

        assertEquals(3, previous, "An empty forecast keeps the whole raw projection");
        Map<Position, List<CommittedOpponentClaim>> claimsBySpot = new LinkedHashMap<>();
        for (int index = 0; index < reserved.size(); index++) {
            Position spot = reserved.get(index);
            claimsBySpot.put(spot, List.of(directIntent(spot, index, spot.value())));
            SemiCommitmentCollectionAttribution attribution =
                    evaluate(state, simulation, forecast(state, Map.copyOf(claimsBySpot)));
            String label = "after reserving " + claimsBySpot.size() + " spots";

            assertEquals(previous - 1, attribution.semiCommitmentRealizableCollections(),
                    "One newly reserved spot costs exactly its own portion: " + label);
            assertEquals(claimsBySpot.size(), attribution.semiClaimedFirstCollections(), label);
            assertEquals(0, attribution.hardClaimedFirstCollections(),
                    "No observed collector anywhere in this fixture: " + label);
            previous = attribution.semiCommitmentRealizableCollections();
        }

        assertEquals(0, previous, "All three single-portion sources end up reserved");
    }

    /** Section 13: hard loss outranks the bounded reservation, which outranks a surviving conflict. */
    @Test
    void attributionPrecedenceRunsHardThenSemiThenDirect() {
        DayState state = state(3, List.of(new UdonSpot(BRAND_D, position(1), 1)), 6);
        DaySimulationResult simulation = simulate(state, route(1, 4));

        assertEquals(SemiCommitmentCollectionClassification.HARD_CLAIMED_FIRST,
                only(state, simulation, List.of(
                        observedNow(position(1), 0), directIntent(position(1), 1, 1))),
                "An observed claim wins the precedence over any number of direct claims");
        assertEquals(SemiCommitmentCollectionClassification.SEMI_CLAIMED_FIRST,
                only(state, simulation, List.of(directIntent(position(1), 0, 1))),
                "The reservation takes the last portion");
        assertEquals(SemiCommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE,
                only(state, simulation, List.of(followOnIntent(position(1), 0, 1))),
                "Follow-on intent never reserves, so the collection survives as risk only");
        assertEquals(SemiCommitmentCollectionClassification.CONTESTED_TIE,
                only(state, simulation, List.of(directIntent(position(1), 0, 2))),
                "An equal-step direct claim is a contest, never a *_BEFORE and never a reservation");
        assertEquals(SemiCommitmentCollectionClassification.LIKELY_AVAILABLE,
                only(state, simulation, List.of(directIntent(position(1), 0, 5))),
                "A claim landing after us cannot touch the collection we already made");
        assertEquals(SemiCommitmentCollectionClassification.UNFORECASTED,
                only(state, simulation, List.of()),
                "No claim at the spot at all");
    }

    /**
     * Sections 11 and 54: for the same plan and forecast the three counts are ordered, the two
     * claimed-first classes never exceed the raw projection, and the brand set is a subset of the
     * local one. Asserted across a spread of forecast shapes rather than one lucky fixture.
     */
    @Test
    void threeWayOrderingAndInvariantsHoldForEveryForecastShape() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(3, 0));
        long raw = ((ValidDaySimulationResult) simulation).events().stream()
                .filter(UdonCollectedEvent.class::isInstance)
                .count();
        List<Map<Position, List<CommittedOpponentClaim>>> shapes = List.of(
                Map.of(),
                Map.of(position(1), List.of(observedNow(position(1), 0))),
                Map.of(position(1), List.of(directIntent(position(1), 0, 1))),
                Map.of(position(1), List.of(followOnIntent(position(1), 0, 1))),
                Map.of(position(1), List.of(directIntent(position(1), 0, 2))),
                Map.of(
                        position(1), List.of(
                                observedNow(position(1), 0), directIntent(position(1), 1, 1)),
                        position(2), List.of(
                                directIntent(position(2), 2, 1), directIntent(position(2), 3, 3)),
                        position(3), List.of(followOnIntent(position(3), 4, 5))));

        for (Map<Position, List<CommittedOpponentClaim>> shape : shapes) {
            SemiCommitmentCollectionAttribution attribution =
                    evaluate(state, simulation, forecast(state, shape));
            String label = "shape=" + shape.size() + " claimed spots";

            assertTrue(attribution.oldForecastRealizableCollections()
                            <= attribution.semiCommitmentRealizableCollections(),
                    "M10 can never keep more than M12.1: " + label);
            assertTrue(attribution.semiCommitmentRealizableCollections()
                            <= attribution.commitmentRealizableCollections(),
                    "M12.1 can never keep more than M12: " + label);
            assertTrue(attribution.commitmentRealizableCollections() <= raw,
                    "M12 can never exceed the raw simulator projection: " + label);
            assertTrue(attribution.hardClaimedFirstCollections()
                            + attribution.semiClaimedFirstCollections() <= raw,
                    "Hard plus semi losses never exceed the raw projection: " + label);
            assertTrue(attribution.localProjectedBrands()
                            .containsAll(attribution.semiCommitmentRealizableBrands()),
                    "Semi-realizable brands are a subset of the local ones: " + label);
        }
    }

    /** Section 16: the evaluation record enforces the ordering it reports, rather than trusting it. */
    @Test
    void evaluationRecordEnforcesTheOrderingInvariants() {
        PlanEvaluation base = new PlanEvaluation(1, 1, 1, 10, 4, "sig");

        assertThrows(IllegalArgumentException.class, () -> evaluation(base, 2, 1, 1, 1),
                "semiCommitmentRealizableBrandCount <= localTeamBrandCount");
        assertThrows(IllegalArgumentException.class, () -> evaluation(base, 1, 2, 2, 2),
                "semiCommitmentRealizableCollections <= rawSimulatorProjectedCollections");
        assertThrows(IllegalArgumentException.class, () -> evaluation(base, 1, 1, 0, 0),
                "semiCommitmentRealizableCollections <= commitmentRealizableCollections");
        assertThrows(IllegalArgumentException.class, () -> evaluation(base, 1, 0, 1, 1),
                "oldForecastRealizableCollections <= semiCommitmentRealizableCollections");

        assertThrows(IllegalArgumentException.class,
                () -> new SemiCommitmentCollectionAttribution(
                        new SemiCommitmentAdjustedCollectionScore(0), 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        Set.of(BRAND_D), Set.of(BRAND_D), List.of()),
                "M12.1 cannot exceed M12 inside the attribution either");
        assertThrows(IllegalArgumentException.class,
                () -> new SemiCommitmentCollectionAttribution(
                        new SemiCommitmentAdjustedCollectionScore(0), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        Set.of(BRAND_D), Set.of(BRAND_D, BRAND_E), List.of()),
                "Semi-realizable brands must stay a subset of the local ones");
    }

    @Test
    void invalidSimulationYieldsAnEmptyAttribution() {
        DayState state = brandState();
        // Four plain moves cost eight steps against a six-step day budget.
        DaySimulationResult invalid = new DaySimulator().simulate(state, new TeamPlan(Map.of(
                PATROL, route(4, 0))));

        SemiCommitmentCollectionAttribution attribution = evaluate(state, invalid, forecast(
                state, Map.of(position(1), List.of(directIntent(position(1), 0, 1)))));

        assertFalse(invalid instanceof ValidDaySimulationResult, "The fixture must exceed the budget");
        assertEquals(0, attribution.assessments().size());
        assertEquals(0, attribution.semiCommitmentRealizableCollections());
        assertEquals(0, attribution.commitmentRealizableCollections());
        assertEquals(0, attribution.oldForecastRealizableCollections());
        assertEquals(Set.of(), attribution.semiCommitmentRealizableBrands());
    }

    private static SemiCommitmentAwarePlanEvaluation evaluation(
            PlanEvaluation base, int brands, int semi, int commitment, int old) {
        return new SemiCommitmentAwarePlanEvaluation(
                base, new SemiCommitmentAdjustedCollectionScore(4), brands, semi, commitment, old,
                0, 0, 0, 0, 0, 1);
    }

    /** Classification of the single projected collection under one hand-built claim list. */
    private static SemiCommitmentCollectionClassification only(
            DayState state,
            DaySimulationResult simulation,
            List<CommittedOpponentClaim> claims) {
        Map<Position, List<CommittedOpponentClaim>> bySpot = claims.isEmpty()
                ? Map.of()
                : Map.of(position(1), claims);
        SemiCommitmentCollectionAttribution attribution =
                evaluate(state, simulation, forecast(state, bySpot));
        assertEquals(1, attribution.assessments().size(), "The fixture projects one collection");
        return attribution.assessments().get(0).classification();
    }

    private static SemiCommitmentCollectionAttribution evaluate(
            DayState state, DaySimulationResult simulation, OpponentCommitmentForecast forecast) {
        return new SemiCommitmentForecastEvaluator().evaluate(state, simulation, forecast, WEIGHTS);
    }

    private static DaySimulationResult simulate(
            DayState state, List<? extends AgentAction> actions) {
        Map<AgentId, List<? extends AgentAction>> byAgent = new LinkedHashMap<>();
        byAgent.put(PATROL, actions);
        return new DaySimulator().simulate(state, new TeamPlan(byAgent));
    }

    /** Both patrols converge on the shared spot, so two collections land at the same step. */
    private static DaySimulationResult simulateTeam(DayState state) {
        Map<AgentId, List<? extends AgentAction>> byAgent = new LinkedHashMap<>();
        byAgent.put(PATROL, List.of(new MoveAction(Direction.RIGHT)));
        byAgent.put(SECOND_PATROL, List.of(new MoveAction(Direction.LEFT)));
        return new DaySimulator().simulate(state, new TeamPlan(byAgent));
    }

    /**
     * A rightward walk padded to the exact day step budget.
     *
     * <p>{@link vn.ptit.procon.rules.PlanValidator} requires the day budget to be spent exactly, so a
     * short walk is completed with a wait rather than left underspent.</p>
     */
    private static List<AgentAction> route(int moves, int waitSteps) {
        List<AgentAction> actions = new ArrayList<>();
        for (int index = 0; index < moves; index++) {
            actions.add(new MoveAction(Direction.RIGHT));
        }
        if (waitSteps > 0) {
            actions.add(new WaitAction(waitSteps));
        }
        return actions;
    }

    /** Builds a commitment forecast directly, so each claim's class is exactly what is under test. */
    private static OpponentCommitmentForecast forecast(
            DayState state, Map<Position, List<CommittedOpponentClaim>> claimsBySpot) {
        Map<Position, SpotCommitmentPressure> pressureBySpot = new LinkedHashMap<>();
        int observedNow = 0;
        int direct = 0;
        int followOn = 0;
        int hardConsumed = 0;
        for (UdonSpot spot : state.matchData().udonSpots()) {
            List<CommittedOpponentClaim> claims = claimsBySpot.get(spot.position());
            if (claims == null || claims.isEmpty()) {
                continue;
            }
            int spotObserved = count(claims, OpponentClaimCommitment.OBSERVED_NOW);
            int spotDirect = count(claims, OpponentClaimCommitment.DIRECT_INTENT);
            int spotFollowOn = count(claims, OpponentClaimCommitment.FOLLOW_ON_INTENT);
            int spotHard = Math.min(spot.stockCapacity(), spotObserved);
            pressureBySpot.put(spot.position(), new SpotCommitmentPressure(
                    spot.position(), spot.stockCapacity(), spotObserved, spotDirect, spotFollowOn,
                    spotHard, claims));
            observedNow += spotObserved;
            direct += spotDirect;
            followOn += spotFollowOn;
            hardConsumed += spotHard;
        }
        return new OpponentCommitmentForecast(
                pressureBySpot, 1, 1, state.matchData().udonSpots().size(),
                observedNow + direct + followOn, observedNow, direct, followOn, hardConsumed);
    }

    private static int count(
            List<CommittedOpponentClaim> claims, OpponentClaimCommitment commitment) {
        return (int) claims.stream().filter(claim -> claim.commitment() == commitment).count();
    }

    private static CommittedOpponentClaim observedNow(Position spot, int agentIndex) {
        return new CommittedOpponentClaim(
                claim(agentIndex, spot, 0), OpponentClaimCommitment.OBSERVED_NOW);
    }

    private static CommittedOpponentClaim directIntent(Position spot, int agentIndex, int step) {
        return new CommittedOpponentClaim(
                claim(agentIndex, spot, step), OpponentClaimCommitment.DIRECT_INTENT);
    }

    private static CommittedOpponentClaim followOnIntent(Position spot, int agentIndex, int step) {
        return new CommittedOpponentClaim(
                claim(agentIndex, spot, step), OpponentClaimCommitment.FOLLOW_ON_INTENT);
    }

    private static ForecastOpponentClaim claim(int agentIndex, Position spot, int step) {
        return new ForecastOpponentClaim(5, agentIndex, 0, spot, step, IntentRank.PRIMARY, 1);
    }

    /** Two redundant brand-D sources followed by a single brand-E source. */
    private static DayState brandState() {
        return state(5, List.of(
                new UdonSpot(BRAND_D, position(1), 1),
                new UdonSpot(BRAND_D, position(2), 1),
                new UdonSpot(BRAND_E, position(3), 1)), 6);
    }

    /**
     * One two-portion spot between two patrols.
     *
     * <p>The simulator collects at most once per spot per agent, so two own collections at the same
     * spot need two agents; both arrive on step 2, which is what puts the pair of them behind the same
     * single reservation.</p>
     */
    private static DayState sharedSpotState() {
        return sharedSpotState(2);
    }

    private static DayState sharedSpotState(int stock) {
        List<UdonSpot> spots = List.of(new UdonSpot(BRAND_D, position(1), stock));
        Terrain[] terrain = new Terrain[3];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stockByPosition = new LinkedHashMap<>();
        spots.forEach(spot -> stockByPosition.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(3, 1, terrain), new DayStepBudgets(new int[] {2}), List.of(),
                new FuelCapacity(20), spots);
        return new DayState(match, new DayIndex(0), List.of(
                AgentState.patrol(PATROL, position(0), 20),
                AgentState.patrol(SECOND_PATROL, position(2), 20)), Map.of(), stockByPosition);
    }

    private static DayState state(int width, List<UdonSpot> spots, int budget) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(width, 1, terrain), new DayStepBudgets(new int[] {budget}), List.of(),
                new FuelCapacity(20), spots);
        return new DayState(match, new DayIndex(0),
                List.of(AgentState.patrol(PATROL, position(0), 20)), Map.of(), stock);
    }

    private static Position position(int value) {
        return new Position(value);
    }
}
