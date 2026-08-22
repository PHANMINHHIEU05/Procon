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
 * M12 own-collection attribution, commitment-realizable brand coverage and the ordinal risk tiers.
 *
 * <p>Every fixture runs the real {@link DaySimulator} on a one-row map, so the collection steps under
 * test (2, 4 and 6) are the simulator's own, and only the commitment forecast is hand-built. The M10
 * forecast-realizable count is produced by the same pass, which is what makes the section 14
 * invariant {@code oldForecastRealizable <= commitmentRealizable} directly assertable.</p>
 */
class CommitmentForecastEvaluatorTest {

    private static final AgentId PATROL = new AgentId(0);

    private static final BrandId BRAND_D = new BrandId("D");

    private static final BrandId BRAND_E = new BrandId("E");

    private static final CommitmentAdjustmentWeights WEIGHTS = CommitmentAdjustmentWeights.defaults();

    /** Section 6: only an observed claim deletes hard stock, and only strictly before us. */
    @Test
    void observedClaimBeforeUsIsTheOnlyHardLoss() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(1, 4));

        CommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast(
                state, Map.of(position(1), List.of(observedNow(position(1), 0)))));

        assertEquals(1, attribution.assessments().size());
        CommitmentCollectionAssessment assessment = attribution.assessments().get(0);
        assertEquals(CommitmentCollectionClassification.HARD_CLAIMED_FIRST,
                assessment.classification());
        assertFalse(assessment.commitmentRealizable());
        assertEquals(0, attribution.commitmentRealizableCollections());
        assertEquals(0, attribution.oldForecastRealizableCollections());
        assertEquals(1, attribution.hardClaimedFirstCollections());
        assertEquals(0, attribution.adjustedScore().value());
    }

    /** Section 12: the same collection that M10 deleted survives when the claim is only intent. */
    @Test
    void directIntentBeforeUsCostsScoreButKeepsTheCollection() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(1, 4));

        CommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast(
                state, Map.of(position(1), List.of(directIntent(position(1), 0, 1)))));

        CommitmentCollectionAssessment assessment = attribution.assessments().get(0);
        assertEquals(CommitmentCollectionClassification.DIRECT_INTENT_BEFORE,
                assessment.classification());
        assertTrue(assessment.commitmentRealizable());
        assertEquals(1, attribution.commitmentRealizableCollections());
        assertEquals(0, attribution.oldForecastRealizableCollections(),
                "M10 removed this collection outright; that is the field error M12 corrects");
        assertEquals(0, attribution.hardClaimedFirstCollections());
        assertEquals(1, attribution.directIntentBeforeCollections());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT,
                attribution.adjustedScore().value());
    }

    @Test
    void followOnIntentBeforeUsCostsLessThanDirectIntent() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(1, 4));

        CommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast(
                state, Map.of(position(1), List.of(followOnIntent(position(1), 0, 1)))));

        assertEquals(CommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE,
                attribution.assessments().get(0).classification());
        assertEquals(1, attribution.commitmentRealizableCollections());
        assertEquals(1, attribution.followOnIntentBeforeCollections());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_FOLLOW_ON_INTENT_BEFORE_WEIGHT,
                attribution.adjustedScore().value());
    }

    /**
     * Sections 7 and 11: equal-step arrival is a contest, never a before-classification.
     *
     * <p>The hard half of this rule is asserted in {@code OpponentCommitmentForecastTest}, where our
     * own collection can be placed at step zero; here the simulator's earliest collection is step
     * two, so this fixture covers the soft classes.</p>
     */
    @Test
    void equalStepArrivalIsATieAndNeverABeforeClassification() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(1, 4));

        CommitmentCollectionAttribution directTie = evaluate(state, simulation, forecast(
                state, Map.of(position(1), List.of(directIntent(position(1), 0, 2)))));
        CommitmentCollectionAttribution followOnTie = evaluate(state, simulation, forecast(
                state, Map.of(position(1), List.of(followOnIntent(position(1), 0, 2)))));

        assertEquals(CommitmentCollectionClassification.CONTESTED_TIE,
                directTie.assessments().get(0).classification());
        assertEquals(1, directTie.tieCollections());
        assertEquals(0, directTie.directIntentBeforeCollections());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_CONTESTED_TIE_WEIGHT,
                directTie.adjustedScore().value());
        assertEquals(CommitmentCollectionClassification.CONTESTED_TIE,
                followOnTie.assessments().get(0).classification());
        assertEquals(0, followOnTie.followOnIntentBeforeCollections());
    }

    /** An opponent claim that lands after us leaves nothing to attribute against the collection. */
    @Test
    void claimAfterUsIsSimplyLikelyAvailable() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(1, 4));

        CommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast(
                state, Map.of(position(1), List.of(directIntent(position(1), 0, 5)))));

        assertEquals(CommitmentCollectionClassification.LIKELY_AVAILABLE,
                attribution.assessments().get(0).classification());
        assertEquals(1, attribution.likelyAvailableCollections());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_LIKELY_AVAILABLE_WEIGHT,
                attribution.adjustedScore().value());
    }

    /**
     * Sections 16 and 17: attribution is categorical per collection event. Five hypothetical claims
     * against a single portion are one risk class, not five penalties, and the raw claim counts
     * remain available for diagnostics.
     */
    @Test
    void manyFutureClaimsAgainstOnePortionArePenalisedOnce() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(1, 4));
        List<CommittedOpponentClaim> crowd = List.of(
                directIntent(position(1), 0, 1),
                followOnIntent(position(1), 1, 1),
                followOnIntent(position(1), 2, 1),
                followOnIntent(position(1), 3, 1),
                followOnIntent(position(1), 4, 1));
        OpponentCommitmentForecast forecast = forecast(state, Map.of(position(1), crowd));

        CommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast);

        assertEquals(5, forecast.forecastClaims(), "Diagnostics still see every claim");
        assertEquals(1, forecast.directIntentClaims());
        assertEquals(4, forecast.followOnIntentClaims());
        assertEquals(0, forecast.hardConsumedPortions());
        assertEquals(1, attribution.assessments().size());
        assertEquals(CommitmentCollectionClassification.DIRECT_INTENT_BEFORE,
                attribution.assessments().get(0).classification());
        assertEquals(1, attribution.commitmentRealizableCollections());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT,
                attribution.adjustedScore().value(),
                "One collection, one attribution class, one penalty");
    }

    /** Section 13: a redundant source keeps the brand realizable when one source is hard lost. */
    @Test
    void redundantBrandSourceSurvivesAHardLoss() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(3, 0));

        CommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast(
                state, Map.of(position(1), List.of(observedNow(position(1), 0)))));

        assertEquals(3, attribution.assessments().size());
        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.localProjectedBrands());
        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.commitmentRealizableBrands());
        assertEquals(1, attribution.hardClaimedFirstCollections());
        assertEquals(2, attribution.commitmentRealizableCollections());
    }

    /** Section 36: when every projected source of a brand is hard lost, the brand is gone. */
    @Test
    void brandWithEveryProjectedSourceHardLostStopsBeingRealizable() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(3, 0));

        CommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast(state, Map.of(
                position(1), List.of(observedNow(position(1), 0)),
                position(2), List.of(observedNow(position(2), 1)))));

        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.localProjectedBrands());
        assertEquals(Set.of(BRAND_E), attribution.commitmentRealizableBrands());
        assertEquals(2, attribution.hardClaimedFirstCollections());
        assertEquals(1, attribution.commitmentRealizableCollections());
    }

    /**
     * Section 13 again, from the other side: the same two sources claimed only as future intent keep
     * the brand realizable, where the M10 model dropped it.
     */
    @Test
    void softClaimsOnEverySourceStillKeepTheBrandRealizable() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(3, 0));

        CommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast(state, Map.of(
                position(1), List.of(directIntent(position(1), 0, 1)),
                position(2), List.of(followOnIntent(position(2), 0, 3)))));

        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.localProjectedBrands());
        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.commitmentRealizableBrands());
        assertEquals(0, attribution.hardClaimedFirstCollections());
        assertEquals(3, attribution.commitmentRealizableCollections());
        assertEquals(1, attribution.oldForecastRealizableCollections(),
                "M10 kept only the unclaimed brand-E source out of the same three collections");
    }

    /** Section 8: hard depletion is capped by stock, so a spare portion survives one claimer. */
    @Test
    void hardDepletionOnlyConsumesThePortionsThatActuallyExist() {
        UdonSpot shared = new UdonSpot(BRAND_D, position(1), 2);
        DayState state = state(3, List.of(shared), 4);
        DaySimulationResult simulation = simulate(state, route(1, 2));

        CommitmentCollectionAttribution single = evaluate(state, simulation, forecast(
                state, Map.of(position(1), List.of(observedNow(position(1), 0)))));
        CommitmentCollectionAttribution both = evaluate(state, simulation, forecast(state, Map.of(
                position(1), List.of(observedNow(position(1), 0), observedNow(position(1), 1)))));

        assertEquals(1, single.assessments().get(0).commitmentRemainingStock(),
                "Two portions less one hard claim leaves exactly one");
        assertTrue(single.assessments().get(0).commitmentRealizable());
        assertEquals(0, both.assessments().get(0).commitmentRemainingStock(),
                "Two portions and two hard claimers leave nothing, and never a negative stock");
        assertEquals(CommitmentCollectionClassification.HARD_CLAIMED_FIRST,
                both.assessments().get(0).classification());
    }

    /** Section 15: the ordinal tiers, including the deliberate direct-equals-tie equality. */
    @Test
    void hardClaimCarriesTheStrongestPenaltyAcrossTheOrdinalTiers() {
        int available = WEIGHTS.weightFor(CommitmentCollectionClassification.LIKELY_AVAILABLE);
        int unforecasted = WEIGHTS.weightFor(CommitmentCollectionClassification.UNFORECASTED);
        int followOn = WEIGHTS.weightFor(CommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE);
        int direct = WEIGHTS.weightFor(CommitmentCollectionClassification.DIRECT_INTENT_BEFORE);
        int tie = WEIGHTS.weightFor(CommitmentCollectionClassification.CONTESTED_TIE);
        int hard = WEIGHTS.weightFor(CommitmentCollectionClassification.HARD_CLAIMED_FIRST);

        assertEquals(available, unforecasted, "An unforecasted spot is worth a clean one");
        assertTrue(available > followOn, "AVAILABLE > FOLLOW_ON");
        assertTrue(followOn > direct, "FOLLOW_ON > DIRECT");
        assertEquals(direct, tie, "DIRECT == TIE is deliberate: both are one contested arrival away");
        assertTrue(tie > hard, "TIE > HARD");
        assertEquals(0, hard, "A hard-claimed collection is worth nothing");
    }

    /** Section 32: two identical plans differing only in one risk class are ordered by that class. */
    @Test
    void directIntentRiskIsStructurallyStrongerThanFollowOnRisk() {
        CommitmentAwarePlanEvaluation withDirect = evaluation(
                CommitmentCollectionClassification.DIRECT_INTENT_BEFORE);
        CommitmentAwarePlanEvaluation withFollowOn = evaluation(
                CommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE);

        assertTrue(withFollowOn.betterThan(withDirect),
                "The plan whose risk is only a hypothetical continuation is preferred");
        assertFalse(withDirect.betterThan(withFollowOn));
        assertTrue(withFollowOn.adjustedCollectionScore().value()
                > withDirect.adjustedCollectionScore().value());
    }

    /** Section 33: a clean collection still outranks a follow-on contested one. */
    @Test
    void likelyAvailableIsStructurallyStrongerThanFollowOnIntent() {
        CommitmentAwarePlanEvaluation clean = evaluation(
                CommitmentCollectionClassification.LIKELY_AVAILABLE);
        CommitmentAwarePlanEvaluation followOn = evaluation(
                CommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE);

        assertTrue(clean.betterThan(followOn));
        assertFalse(followOn.betterThan(clean));
    }

    /** Section 34: a hard loss is the only class that also removes the collection itself. */
    @Test
    void hardClaimedFirstIsTheWeakestPlanOfAll() {
        List<CommitmentAwarePlanEvaluation> descending = List.of(
                evaluation(CommitmentCollectionClassification.LIKELY_AVAILABLE),
                evaluation(CommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE),
                evaluation(CommitmentCollectionClassification.DIRECT_INTENT_BEFORE),
                evaluation(CommitmentCollectionClassification.HARD_CLAIMED_FIRST));

        for (int index = 0; index + 1 < descending.size(); index++) {
            assertTrue(descending.get(index).betterThan(descending.get(index + 1)),
                    "Tier " + index + " must outrank tier " + (index + 1));
        }
        CommitmentAwarePlanEvaluation hard = descending.get(descending.size() - 1);
        assertEquals(0, hard.commitmentRealizableCollections());
        assertEquals(0, hard.commitmentRealizableBrandCount());
        assertEquals(1, hard.hardClaimedFirstCollections());
    }

    /** Section 14: the invariants are enforced by the records themselves, not merely documented. */
    @Test
    void requiredInvariantsAreEnforced() {
        PlanEvaluation base = new PlanEvaluation(1, 1, 1, 10, 4, "sig");

        assertThrows(IllegalArgumentException.class, () -> new CommitmentAwarePlanEvaluation(
                base, new CommitmentAdjustedCollectionScore(4), 2, 1, 1, 0, 0, 0, 0, 1),
                "commitmentRealizableBrandCount <= localTeamBrandCount");
        assertThrows(IllegalArgumentException.class, () -> new CommitmentAwarePlanEvaluation(
                base, new CommitmentAdjustedCollectionScore(4), 1, 2, 2, 0, 0, 0, 0, 1),
                "commitmentRealizableCollections <= rawSimulatorProjectedCollections");
        assertThrows(IllegalArgumentException.class, () -> new CommitmentAwarePlanEvaluation(
                base, new CommitmentAdjustedCollectionScore(4), 1, 0, 1, 0, 0, 0, 0, 1),
                "oldForecastRealizableCollections <= commitmentRealizableCollections");
        assertThrows(IllegalArgumentException.class, () -> new CommitmentCollectionAttribution(
                new CommitmentAdjustedCollectionScore(0), 0, 1, 0, 0, 0, 0, 0, 0,
                Set.of(BRAND_D), Set.of(BRAND_D), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CommitmentCollectionAttribution(
                new CommitmentAdjustedCollectionScore(0), 0, 0, 0, 0, 0, 0, 0, 0,
                Set.of(BRAND_D), Set.of(BRAND_D, BRAND_E), List.of()));
    }

    /**
     * Section 14 on live-shaped data: for the same plan and the same forecast, the M12 count is
     * never below the M10 one, because hard depletion is a subset of every-claim depletion.
     */
    @Test
    void commitmentRealizableNeverFallsBelowTheOldForecastRealizableCount() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, route(3, 0));
        ValidDaySimulationResult valid = (ValidDaySimulationResult) simulation;
        long raw = valid.events().stream().filter(UdonCollectedEvent.class::isInstance).count();
        List<OpponentCommitmentForecast> forecasts = List.of(
                forecast(state, Map.of()),
                forecast(state, Map.of(position(1), List.of(observedNow(position(1), 0)))),
                forecast(state, Map.of(position(1), List.of(directIntent(position(1), 0, 1)))),
                forecast(state, Map.of(
                        position(1), List.of(followOnIntent(position(1), 0, 1)),
                        position(2), List.of(observedNow(position(2), 1)),
                        position(3), List.of(directIntent(position(3), 2, 5)))));

        for (OpponentCommitmentForecast forecast : forecasts) {
            CommitmentCollectionAttribution attribution = evaluate(state, simulation, forecast);
            assertTrue(attribution.oldForecastRealizableCollections()
                            <= attribution.commitmentRealizableCollections(),
                    "M10 must never exceed M12 for the same plan and forecast");
            assertTrue(attribution.commitmentRealizableCollections() <= raw,
                    "M12 must never exceed the raw simulator projection");
            assertTrue(attribution.localProjectedBrands()
                    .containsAll(attribution.commitmentRealizableBrands()));
        }
    }

    @Test
    void invalidSimulationYieldsAnEmptyAttribution() {
        DayState state = brandState();
        // Four plain moves cost eight steps against a six-step day budget.
        DaySimulationResult invalid = new DaySimulator().simulate(state, new TeamPlan(Map.of(
                PATROL, route(4, 0))));

        CommitmentCollectionAttribution attribution = evaluate(
                state, invalid, forecast(state, Map.of()));

        assertFalse(invalid instanceof ValidDaySimulationResult, "The fixture must exceed the budget");
        assertEquals(0, attribution.assessments().size());
        assertEquals(0, attribution.commitmentRealizableCollections());
        assertEquals(0, attribution.oldForecastRealizableCollections());
        assertEquals(Set.of(), attribution.commitmentRealizableBrands());
    }

    private static CommitmentAwarePlanEvaluation evaluation(
            CommitmentCollectionClassification classification) {
        boolean realizable = classification != CommitmentCollectionClassification.HARD_CLAIMED_FIRST;
        return new CommitmentAwarePlanEvaluation(
                new PlanEvaluation(1, 1, 1, 10, 4, "sig"),
                new CommitmentAdjustedCollectionScore(WEIGHTS.weightFor(classification)),
                realizable ? 1 : 0,
                realizable ? 1 : 0,
                0,
                classification == CommitmentCollectionClassification.HARD_CLAIMED_FIRST ? 1 : 0,
                classification == CommitmentCollectionClassification.DIRECT_INTENT_BEFORE ? 1 : 0,
                classification == CommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE ? 1 : 0,
                classification == CommitmentCollectionClassification.CONTESTED_TIE ? 1 : 0,
                0);
    }

    private CommitmentCollectionAttribution evaluate(
            DayState state, DaySimulationResult simulation, OpponentCommitmentForecast forecast) {
        return new CommitmentForecastEvaluator().evaluate(state, simulation, forecast, WEIGHTS);
    }

    private DaySimulationResult simulate(DayState state, List<? extends AgentAction> actions) {
        Map<AgentId, List<? extends AgentAction>> byAgent = new LinkedHashMap<>();
        byAgent.put(PATROL, actions);
        return new DaySimulator().simulate(state, new TeamPlan(byAgent));
    }

    /**
     * A rightward walk padded to the exact day step budget.
     *
     * <p>{@link vn.ptit.procon.rules.PlanValidator} requires the day budget to be spent exactly, so
     * a short walk is completed with a wait rather than left underspent.</p>
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
        return (int) claims.stream()
                .filter(claim -> claim.commitment() == commitment)
                .count();
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
    private DayState brandState() {
        return state(5, List.of(
                new UdonSpot(BRAND_D, position(1), 1),
                new UdonSpot(BRAND_D, position(2), 1),
                new UdonSpot(BRAND_E, position(3), 1)), 6);
    }

    private DayState state(int width, List<UdonSpot> spots, int budget) {
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
