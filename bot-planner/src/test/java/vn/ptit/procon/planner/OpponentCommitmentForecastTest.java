package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.map.Position;

/**
 * M12 claim commitment model over an unchanged M10 opponent intent forecast.
 *
 * <p>The live m-3598 day that motivated M12 removed twenty-nine projected own collections as
 * likely-claimed-first while the opponent actually took roughly fourteen. With five opponent
 * collectors holding up to three intent targets each, later hypothetical route continuations were
 * being treated like committed future actions. These tests pin the separation that fixes it: an
 * opponent already standing on a stocked spot is structural evidence, its first future route claim is
 * a direct intention, and every later claim is a hypothetical continuation. Only the first class may
 * delete hard forecast stock.</p>
 */
class OpponentCommitmentForecastTest {

    private static final CommitmentAdjustmentWeights WEIGHTS = CommitmentAdjustmentWeights.defaults();

    private static final CommitmentForecastEvaluator EVALUATOR = new CommitmentForecastEvaluator();

    /** Section 25: an opponent already on the spot is the only hard, deterministic depletion. */
    @Test
    void observedOpponentOnAStockedSpotHardClaimsThePortion() {
        ForecastOpponentClaim standing = claim(0, 10, 0, IntentRank.PRIMARY);
        OpponentIntentForecast intent = forecast(List.of(pressure(10, 1, List.of(standing))));
        OpponentCommitmentForecast commitment = OpponentCommitmentForecast.annotate(intent);

        assertEquals(OpponentClaimCommitment.OBSERVED_NOW, commitmentOf(commitment, standing));
        assertEquals(1, commitment.observedNowClaims());
        assertEquals(0, commitment.directIntentClaims());
        assertEquals(0, commitment.followOnIntentClaims());
        assertEquals(1, commitment.hardConsumedPortions());

        SpotCommitmentPressure pressure = commitment.pressureAt(new Position(10));
        assertTrue(pressure.claims().get(0).hard());
        assertEquals(1, pressure.hardConsumedStrictlyBefore(3));

        CommitmentCollectionAssessment assessment = assess(commitment, 10, 3);
        assertEquals(CommitmentCollectionClassification.HARD_CLAIMED_FIRST, assessment.classification());
        assertEquals(0, assessment.commitmentRemainingStock());
        assertFalse(assessment.commitmentRealizable());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_HARD_CLAIMED_FIRST_WEIGHT,
                assessment.commitmentValueUnits());
    }

    /**
     * Section 26: even the hardest claim obeys arrival ordering. A step-zero opponent and a
     * step-zero own collection is a contest, never a deterministic deletion.
     */
    @Test
    void observedClaimAtTheSameStepIsAContestAndNotADeletion() {
        ForecastOpponentClaim standing = claim(0, 10, 0, IntentRank.PRIMARY);
        OpponentCommitmentForecast commitment = OpponentCommitmentForecast.annotate(
                forecast(List.of(pressure(10, 1, List.of(standing)))));

        SpotCommitmentPressure pressure = commitment.pressureAt(new Position(10));
        assertEquals(1, pressure.hardConsumedPortions(),
                "The spot-level hard capacity is still one portion");
        assertEquals(0, pressure.hardConsumedStrictlyBefore(0),
                "Strictly-before semantics: nothing arrives before step zero");
        assertTrue(pressure.anyAt(0));

        CommitmentCollectionAssessment assessment = assess(commitment, 10, 0);
        assertEquals(CommitmentCollectionClassification.CONTESTED_TIE, assessment.classification());
        assertEquals(1, assessment.commitmentRemainingStock());
        assertTrue(assessment.commitmentRealizable());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_CONTESTED_TIE_WEIGHT,
                assessment.commitmentValueUnits());
    }

    /**
     * Section 27: the first future claim an agent actually produces is its direct intention. This is
     * the central M12 correction: it costs score but leaves the portion in the hard forecast.
     */
    @Test
    void firstFutureClaimOfAnAgentIsDirectIntentAndDoesNotDeleteStock() {
        ForecastOpponentClaim future = claim(0, 10, 4, IntentRank.PRIMARY);
        OpponentCommitmentForecast commitment = OpponentCommitmentForecast.annotate(
                forecast(List.of(pressure(10, 1, List.of(future)))));

        assertEquals(OpponentClaimCommitment.DIRECT_INTENT, commitmentOf(commitment, future));
        assertEquals(1, commitment.directIntentClaims());
        assertEquals(0, commitment.observedNowClaims());
        assertEquals(0, commitment.hardConsumedPortions(),
                "Direct intent must never create hard forecast depletion");
        assertFalse(commitment.pressureAt(new Position(10)).claims().get(0).hard());
        assertEquals(0, commitment.pressureAt(new Position(10)).hardConsumedStrictlyBefore(9));

        CommitmentCollectionAssessment assessment = assess(commitment, 10, 6);
        assertEquals(CommitmentCollectionClassification.DIRECT_INTENT_BEFORE,
                assessment.classification());
        assertEquals(1, assessment.commitmentRemainingStock(),
                "Under M10 this portion was gone; under M12 it survives");
        assertTrue(assessment.commitmentRealizable());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT,
                assessment.commitmentValueUnits());
    }

    /** Section 28: the second and later claims of the same agent are hypothetical continuations. */
    @Test
    void secondFutureClaimOfTheSameAgentIsFollowOnIntent() {
        ForecastOpponentClaim first = claim(0, 10, 3, IntentRank.PRIMARY);
        ForecastOpponentClaim second = claim(0, 20, 7, IntentRank.SECONDARY);
        OpponentCommitmentForecast commitment = OpponentCommitmentForecast.annotate(forecast(List.of(
                pressure(10, 1, List.of(first)),
                pressure(20, 1, List.of(second)))));

        assertEquals(OpponentClaimCommitment.DIRECT_INTENT, commitmentOf(commitment, first));
        assertEquals(OpponentClaimCommitment.FOLLOW_ON_INTENT, commitmentOf(commitment, second));
        assertEquals(1, commitment.directIntentClaims());
        assertEquals(1, commitment.followOnIntentClaims());
        assertEquals(0, commitment.hardConsumedPortions());

        CommitmentCollectionAssessment assessment = assess(commitment, 20, 9);
        assertEquals(CommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE,
                assessment.classification());
        assertEquals(1, assessment.commitmentRemainingStock());
        assertTrue(assessment.commitmentRealizable());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_FOLLOW_ON_INTENT_BEFORE_WEIGHT,
                assessment.commitmentValueUnits());
    }

    /**
     * Section 29: commitment follows realized claim order, not intent target rank. A PRIMARY target
     * that never became a claim leaves the agent's SECONDARY claim as its direct intention, and an
     * earlier-arriving TERTIARY claim outranks a later PRIMARY one.
     */
    @Test
    void intentTargetRankIsNotCommitment() {
        // Agent 0's PRIMARY target produced no claim at all; only its SECONDARY target survived.
        ForecastOpponentClaim onlySurvivor = claim(0, 10, 4, IntentRank.SECONDARY);
        // Agent 1 produced two claims whose rank order is the reverse of their arrival order.
        ForecastOpponentClaim earlyTertiary = claim(1, 20, 2, IntentRank.TERTIARY);
        ForecastOpponentClaim latePrimary = claim(1, 30, 6, IntentRank.PRIMARY);
        OpponentCommitmentForecast commitment = OpponentCommitmentForecast.annotate(forecast(List.of(
                pressure(10, 1, List.of(onlySurvivor)),
                pressure(20, 1, List.of(earlyTertiary)),
                pressure(30, 1, List.of(latePrimary)))));

        assertEquals(OpponentClaimCommitment.DIRECT_INTENT, commitmentOf(commitment, onlySurvivor),
                "A SECONDARY-rank claim that is the agent's first claim is its direct intention");
        assertEquals(OpponentClaimCommitment.DIRECT_INTENT, commitmentOf(commitment, earlyTertiary),
                "Realized claim order decides, so the earlier TERTIARY claim is the direct one");
        assertEquals(OpponentClaimCommitment.FOLLOW_ON_INTENT, commitmentOf(commitment, latePrimary),
                "A PRIMARY-rank claim arriving later is still only a follow-on continuation");
        assertEquals(2, commitment.directIntentClaims());
        assertEquals(1, commitment.followOnIntentClaims());
        assertEquals(0, commitment.hardConsumedPortions());
    }

    /**
     * Section 30: hard depletion is capacity aware. Three observed claimers on a one-portion spot
     * consume one portion, not three, and forecast stock never goes negative.
     *
     * <p>Built at the {@link SpotCommitmentPressure} level on purpose: the M10
     * {@link SpotIntentPressure} record already refuses to hold more claims than stock, so the M12
     * capacity guard has to hold independently of what the M10 forecaster is willing to emit.</p>
     */
    @Test
    void hardStockDepletionIsCappedByCurrentStock() {
        List<CommittedOpponentClaim> observed = List.of(
                committed(claim(0, 10, 0, IntentRank.PRIMARY), OpponentClaimCommitment.OBSERVED_NOW),
                committed(claim(1, 10, 0, IntentRank.PRIMARY), OpponentClaimCommitment.OBSERVED_NOW),
                committed(claim(2, 10, 0, IntentRank.PRIMARY), OpponentClaimCommitment.OBSERVED_NOW));
        SpotCommitmentPressure pressure = new SpotCommitmentPressure(
                new Position(10), 1, 3, 0, 0, 1, observed);

        assertEquals(1, pressure.hardConsumedPortions());
        assertEquals(1, pressure.hardConsumedStrictlyBefore(5),
                "Three observed claimers may consume at most the single portion that exists");
        assertEquals(0, pressure.hardConsumedStrictlyBefore(0));

        // Two portions and three observed claimers consume exactly two.
        assertEquals(2, new SpotCommitmentPressure(new Position(10), 2, 3, 0, 0, 2, observed)
                .hardConsumedStrictlyBefore(5));
        // Claiming more hard portions than exist is rejected outright, so stock cannot go negative.
        assertThrows(IllegalArgumentException.class, () -> new SpotCommitmentPressure(
                new Position(10), 1, 3, 0, 0, 3, observed));

        OpponentCommitmentForecast forecast = new OpponentCommitmentForecast(
                Map.of(new Position(10), pressure), 3, 3, 1, 3, 3, 0, 0, 1);
        CommitmentCollectionAssessment assessment = EVALUATOR.assessCollection(
                Map.of(new Position(10), 1), new Position(10), 5, forecast, WEIGHTS);
        assertEquals(CommitmentCollectionClassification.HARD_CLAIMED_FIRST,
                assessment.classification());
        assertEquals(0, assessment.commitmentRemainingStock(), "Never negative");
    }

    /**
     * Section 31: this is the m-3598 pathology in one assertion. Five future claims from opponents
     * that are nowhere near the spot yet cannot erase the single portion that is actually there.
     */
    @Test
    void manySoftClaimsDoNotEraseStock() {
        List<CommittedOpponentClaim> soft = List.of(
                committed(claim(0, 10, 1, IntentRank.PRIMARY), OpponentClaimCommitment.DIRECT_INTENT),
                committed(claim(1, 10, 2, IntentRank.SECONDARY),
                        OpponentClaimCommitment.FOLLOW_ON_INTENT),
                committed(claim(2, 10, 3, IntentRank.SECONDARY),
                        OpponentClaimCommitment.FOLLOW_ON_INTENT),
                committed(claim(3, 10, 4, IntentRank.TERTIARY),
                        OpponentClaimCommitment.FOLLOW_ON_INTENT),
                committed(claim(4, 10, 5, IntentRank.TERTIARY),
                        OpponentClaimCommitment.FOLLOW_ON_INTENT));
        SpotCommitmentPressure pressure = new SpotCommitmentPressure(
                new Position(10), 1, 0, 1, 4, 0, soft);
        OpponentCommitmentForecast forecast = new OpponentCommitmentForecast(
                Map.of(new Position(10), pressure), 5, 5, 1, 5, 0, 1, 4, 0);

        assertEquals(0, pressure.hardConsumedPortions());
        assertEquals(0, pressure.hardConsumedStrictlyBefore(9));

        CommitmentCollectionAssessment assessment = EVALUATOR.assessCollection(
                Map.of(new Position(10), 1), new Position(10), 9, forecast, WEIGHTS);
        assertEquals(1, assessment.commitmentRemainingStock(),
                "Five hypothetical future claims removed the portion under M10; under M12 they cannot");
        assertTrue(assessment.commitmentRealizable());
        // Section 16: the attribution is categorical, so five claims are still one risk class.
        assertEquals(CommitmentCollectionClassification.DIRECT_INTENT_BEFORE,
                assessment.classification());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT,
                assessment.commitmentValueUnits(),
                "One collection is penalised once by its class, never once per claim");
    }

    /** Section 51: the annotation itself must be order-stable and structurally consistent. */
    @Test
    void annotationIsDeterministicAndTotallyClassified() {
        List<SpotIntentPressure> spots = List.of(
                pressure(30, 2, List.of(
                        claim(1, 30, 0, IntentRank.PRIMARY), claim(2, 30, 5, IntentRank.SECONDARY))),
                pressure(10, 1, List.of(claim(0, 10, 2, IntentRank.PRIMARY))),
                pressure(20, 1, List.of(claim(2, 20, 3, IntentRank.PRIMARY))));

        OpponentCommitmentForecast first = OpponentCommitmentForecast.annotate(forecast(spots));
        OpponentCommitmentForecast second = OpponentCommitmentForecast.annotate(forecast(spots));

        assertEquals(first, second);
        assertEquals(4, first.forecastClaims());
        assertEquals(first.forecastClaims(),
                first.observedNowClaims() + first.directIntentClaims() + first.followOnIntentClaims());
        assertTrue(first.hardConsumedPortions() <= first.observedNowClaims());
        // Agent 2 claims spot 20 at step 3 and spot 30 at step 5, so the earlier one is direct.
        assertEquals(OpponentClaimCommitment.DIRECT_INTENT,
                commitmentOf(first, claim(2, 20, 3, IntentRank.PRIMARY)));
        assertEquals(OpponentClaimCommitment.FOLLOW_ON_INTENT,
                commitmentOf(first, claim(2, 30, 5, IntentRank.SECONDARY)));
        assertEquals(1, first.observedNowClaims());
        assertEquals(1, first.hardConsumedPortions());
    }

    @Test
    void emptyAnnotationIsInertForEveryOldMode() {
        OpponentCommitmentForecast empty = OpponentCommitmentForecast.empty();

        assertEquals(0, empty.forecastClaims());
        assertEquals(0, empty.hardConsumedPortions());
        assertEquals(null, empty.pressureAt(new Position(10)));

        CommitmentCollectionAssessment assessment = EVALUATOR.assessCollection(
                Map.of(new Position(10), 1), new Position(10), 4, empty, WEIGHTS);
        assertEquals(CommitmentCollectionClassification.UNFORECASTED, assessment.classification());
        assertTrue(assessment.commitmentRealizable());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_UNFORECASTED_WEIGHT,
                assessment.commitmentValueUnits());
    }

    @Test
    void observedNowIsExactlyTheStepZeroClaim() {
        assertThrows(IllegalArgumentException.class, () -> committed(
                claim(0, 10, 3, IntentRank.PRIMARY), OpponentClaimCommitment.OBSERVED_NOW));
        assertThrows(IllegalArgumentException.class, () -> committed(
                claim(0, 10, 0, IntentRank.PRIMARY), OpponentClaimCommitment.DIRECT_INTENT));
        assertThrows(IllegalArgumentException.class, () -> committed(
                claim(0, 10, 0, IntentRank.PRIMARY), OpponentClaimCommitment.FOLLOW_ON_INTENT));
    }

    private static CommitmentCollectionAssessment assess(
            OpponentCommitmentForecast forecast, int position, int step) {
        Map<Position, Integer> stock = new LinkedHashMap<>();
        forecast.pressureBySpot().forEach((spot, pressure) -> stock.put(spot, pressure.currentStock()));
        return EVALUATOR.assessCollection(stock, new Position(position), step, forecast, WEIGHTS);
    }

    private static OpponentClaimCommitment commitmentOf(
            OpponentCommitmentForecast forecast, ForecastOpponentClaim claim) {
        SpotCommitmentPressure pressure = forecast.pressureAt(claim.spot());
        return pressure.claims().stream()
                .filter(committed -> committed.claim().equals(claim))
                .map(CommittedOpponentClaim::commitment)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Claim was not annotated: " + claim));
    }

    private static CommittedOpponentClaim committed(
            ForecastOpponentClaim claim, OpponentClaimCommitment commitment) {
        return new CommittedOpponentClaim(claim, commitment);
    }

    private static ForecastOpponentClaim claim(
            int agentIndex, int position, int step, IntentRank rank) {
        return new ForecastOpponentClaim(5, agentIndex, 0, new Position(position), step, rank, 1);
    }

    private static SpotIntentPressure pressure(
            int position, int stock, List<ForecastOpponentClaim> claims) {
        return new SpotIntentPressure(
                new Position(position),
                stock,
                claims.stream().mapToInt(ForecastOpponentClaim::pressureUnits).sum(),
                claims.size(),
                claims.size(),
                claims.isEmpty()
                        ? OptionalInt.empty()
                        : OptionalInt.of(claims.stream()
                                .mapToInt(ForecastOpponentClaim::forecastArrivalStep)
                                .min()
                                .orElseThrow()),
                claims);
    }

    private static OpponentIntentForecast forecast(List<SpotIntentPressure> spots) {
        Map<Position, SpotIntentPressure> byPosition = new LinkedHashMap<>();
        List<Integer> agents = new ArrayList<>();
        int claims = 0;
        for (SpotIntentPressure pressure : spots) {
            byPosition.put(pressure.spot(), pressure);
            claims += pressure.claims().size();
            pressure.claims().forEach(claim -> {
                if (!agents.contains(claim.agentIndex())) {
                    agents.add(claim.agentIndex());
                }
            });
        }
        return new OpponentIntentForecast(
                List.of(), byPosition, agents.size(), agents.size(), spots.size(), 0, 0,
                claims, claims);
    }
}
