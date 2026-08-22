package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.map.Position;

/**
 * M12.1 bounded semi-reservation over the unchanged M12 commitment annotation.
 *
 * <p>The live evidence behind these tests is structural, never numeric. On the 8x8 calibration day the
 * raw simulator projected 56 own collections, the M10 binary forecast 44, the M12 hard-only forecast 55,
 * and the server actually granted 50: M10 removed six collections too many, M12 kept five too many. On
 * the 12x12 five-collector day the same four numbers were 96 / 60 / 87 / 82. Both errors have one cause —
 * M10 charged every forecast claim against stock, M12 charged none but the observed ones — and the fix is
 * a single bounded middle layer, not a fitted coefficient. Nothing below encodes "subtract five" or "a
 * fifth of the direct claims"; every assertion covers only the per-spot cap, the capacity ordering and
 * the arrival ordering.</p>
 */
class SemiCommitmentForecastTest {

    private static final SemiCommitmentAdjustmentWeights WEIGHTS =
            SemiCommitmentAdjustmentWeights.defaults();

    private static final SemiCommitmentForecastEvaluator SEMI = new SemiCommitmentForecastEvaluator();

    private static final CommitmentForecastEvaluator HARD_ONLY = new CommitmentForecastEvaluator();

    /**
     * Section 24: one direct claim strictly before us reserves the single portion. This is the whole
     * point of M12.1 — M12 alone leaves the collection realizable, and that is exactly where its
     * remaining optimism came from on both live days.
     */
    @Test
    void oneDirectClaimBeforeUsReservesTheOnlyPortion() {
        OpponentCommitmentForecast forecast = forecast(spot(10, 1, directClaim(0, 10, 4)));

        SpotCommitmentPressure pressure = forecast.pressureAt(new Position(10));
        assertEquals(0, pressure.hardConsumedStrictlyBefore(9), "No observed claim, so no hard loss");
        assertEquals(1, pressure.directClaimsStrictlyBefore(9));
        assertEquals(1, pressure.semiReservedDirectPortionsStrictlyBefore(9));

        assertEquals(1, hardRemaining(forecast, 10, 9),
                "M12 keeps the portion: a future claim is not an observed collector");

        SemiCommitmentCollectionAssessment assessment = assess(forecast, 10, 9);
        assertEquals(0, assessment.semiCommitmentRemainingStock());
        assertFalse(assessment.semiCommitmentRealizable());
        assertEquals(SemiCommitmentCollectionClassification.SEMI_CLAIMED_FIRST,
                assessment.classification());
        assertEquals(SemiCommitmentAdjustmentWeights.DEFAULT_SEMI_CLAIMED_FIRST_WEIGHT,
                assessment.semiCommitmentValueUnits());
    }

    /**
     * Sections 19 and 25: the critical anti-M10 test. Five direct claimers on a five-portion spot
     * reserve one portion between them, not five, so four of our five collections survive. M10 would
     * have erased all five.
     */
    @Test
    void fiveDirectClaimsOnOneSpotReserveExactlyOnePortion() {
        SpotCommitmentPressure pressure = pressure(10, 5, 0, 5, 0, 0, List.of(
                directClaim(0, 10, 1),
                directClaim(1, 10, 2),
                directClaim(2, 10, 3),
                directClaim(3, 10, 4),
                directClaim(4, 10, 5)));
        OpponentCommitmentForecast forecast = forecast(pressure, 5, 0, 5, 0, 0);

        assertEquals(5, pressure.directClaimsStrictlyBefore(9));
        assertEquals(1, pressure.semiReservedDirectPortionsStrictlyBefore(9),
                "One portion is reserved however many agents predict the spot");
        assertEquals(1, pressure.semiReservedDirectPortions());

        // Five own collections at the same spot, every one arriving after every claim.
        List<SemiCommitmentCollectionClassification> classes = new ArrayList<>();
        int semiRealizable = 0;
        int commitmentRealizable = 0;
        for (int index = 0; index < 5; index++) {
            Map<Position, Integer> remainingStock = Map.of(new Position(10), 5 - index);
            SemiCommitmentCollectionAssessment assessment =
                    SEMI.assessCollection(remainingStock, new Position(10), 9, forecast, WEIGHTS);
            classes.add(assessment.classification());
            semiRealizable += assessment.semiCommitmentRealizable() ? 1 : 0;
            commitmentRealizable += HARD_ONLY.assessCollection(
                    remainingStock, new Position(10), 9, forecast,
                    CommitmentAdjustmentWeights.defaults()).commitmentRealizable() ? 1 : 0;
        }

        assertEquals(5, commitmentRealizable, "M12 keeps every portion");
        assertEquals(4, semiRealizable, "M12.1 loses exactly one portion, not five and not zero");
        assertEquals(List.of(
                SemiCommitmentCollectionClassification.DIRECT_INTENT_BEFORE,
                SemiCommitmentCollectionClassification.DIRECT_INTENT_BEFORE,
                SemiCommitmentCollectionClassification.DIRECT_INTENT_BEFORE,
                SemiCommitmentCollectionClassification.DIRECT_INTENT_BEFORE,
                SemiCommitmentCollectionClassification.SEMI_CLAIMED_FIRST),
                classes,
                "Only the collection that runs out of capacity is semi-claimed-first");
    }

    /** Sections 20 and 26: the cap is per spot, so two contested spots reserve one portion each. */
    @Test
    void reservationIsPerSpotAndNotPerDay() {
        OpponentCommitmentForecast forecast = forecast(
                spot(10, 1, directClaim(0, 10, 3)),
                spot(20, 1, directClaim(1, 20, 4)));

        assertEquals(1,
                forecast.pressureAt(new Position(10)).semiReservedDirectPortionsStrictlyBefore(9));
        assertEquals(1,
                forecast.pressureAt(new Position(20)).semiReservedDirectPortionsStrictlyBefore(9));

        SemiCommitmentForecast semi = SemiCommitmentForecast.derive(forecast);
        assertEquals(2, semi.semiReservedSpots(), "One reserved portion at each of the two spots");
        assertEquals(1, semi.maxSemiReservedPortions(), "Still capped at one portion per spot");

        assertEquals(SemiCommitmentCollectionClassification.SEMI_CLAIMED_FIRST,
                assess(forecast, 10, 9).classification());
        assertEquals(SemiCommitmentCollectionClassification.SEMI_CLAIMED_FIRST,
                assess(forecast, 20, 9).classification());
    }

    /** Sections 21 and 27: hard depletion takes capacity first, so nothing is left to reserve. */
    @Test
    void hardClaimConsumesTheLastPortionBeforeAnyReservation() {
        SpotCommitmentPressure pressure = pressure(10, 1, 1, 3, 0, 1, List.of(
                observedClaim(0, 10),
                directClaim(1, 10, 2),
                directClaim(2, 10, 3),
                directClaim(3, 10, 4)));
        OpponentCommitmentForecast forecast = forecast(pressure, 4, 1, 3, 0, 1);

        assertEquals(1, pressure.hardConsumedStrictlyBefore(9));
        assertEquals(3, pressure.directClaimsStrictlyBefore(9));
        assertEquals(0, pressure.semiReservedDirectPortionsStrictlyBefore(9),
                "Nothing survives the hard depletion, so the reservation has no capacity to take");

        SemiCommitmentCollectionAssessment assessment = assess(forecast, 10, 9);
        assertEquals(0, assessment.semiCommitmentRemainingStock());
        assertEquals(SemiCommitmentCollectionClassification.HARD_CLAIMED_FIRST,
                assessment.classification(),
                "Hard loss outranks the bounded reservation in the attribution precedence");
        assertEquals(SemiCommitmentAdjustmentWeights.DEFAULT_HARD_CLAIMED_FIRST_WEIGHT,
                assessment.semiCommitmentValueUnits());
    }

    /** Sections 21 and 28: two portions, one hard claim and direct claims take one portion each. */
    @Test
    void hardAndSemiStackWithinCapacity() {
        SpotCommitmentPressure pressure = pressure(10, 2, 1, 3, 0, 1, List.of(
                observedClaim(0, 10),
                directClaim(1, 10, 2),
                directClaim(2, 10, 3),
                directClaim(3, 10, 4)));
        OpponentCommitmentForecast forecast = forecast(pressure, 4, 1, 3, 0, 1);

        assertEquals(1, pressure.hardConsumedStrictlyBefore(9));
        assertEquals(1, pressure.semiReservedDirectPortionsStrictlyBefore(9));
        assertEquals(2,
                pressure.hardConsumedStrictlyBefore(9)
                        + pressure.semiReservedDirectPortionsStrictlyBefore(9),
                "Total capacity reduction is two of the two portions");

        assertEquals(1, hardRemaining(forecast, 10, 9), "M12 loses only the hard portion");

        SemiCommitmentCollectionAssessment assessment = assess(forecast, 10, 9);
        assertEquals(0, assessment.semiCommitmentRemainingStock());
        assertEquals(SemiCommitmentCollectionClassification.SEMI_CLAIMED_FIRST,
                assessment.classification(),
                "The portion that survived the hard loss is the one the reservation takes");
    }

    /** Sections 8, 23 and 29: an equal-step direct claim is a contest, never a reservation. */
    @Test
    void directClaimAtOurOwnStepDoesNotReserve() {
        OpponentCommitmentForecast forecast = forecast(spot(10, 1, directClaim(0, 10, 6)));

        SpotCommitmentPressure pressure = forecast.pressureAt(new Position(10));
        assertEquals(0, pressure.directClaimsStrictlyBefore(6));
        assertEquals(0, pressure.semiReservedDirectPortionsStrictlyBefore(6));
        assertTrue(pressure.anyAt(6));

        SemiCommitmentCollectionAssessment assessment = assess(forecast, 10, 6);
        assertEquals(1, assessment.semiCommitmentRemainingStock());
        assertTrue(assessment.semiCommitmentRealizable());
        assertEquals(SemiCommitmentCollectionClassification.CONTESTED_TIE, assessment.classification());
        assertEquals(SemiCommitmentAdjustmentWeights.DEFAULT_CONTESTED_TIE_WEIGHT,
                assessment.semiCommitmentValueUnits());
    }

    /** Section 22: a direct claim landing after us cannot touch the collection we already made. */
    @Test
    void directClaimAfterUsDoesNotReserve() {
        OpponentCommitmentForecast forecast = forecast(spot(10, 1, directClaim(0, 10, 8)));

        SpotCommitmentPressure pressure = forecast.pressureAt(new Position(10));
        assertEquals(0, pressure.directClaimsStrictlyBefore(3));
        assertEquals(0, pressure.semiReservedDirectPortionsStrictlyBefore(3));

        SemiCommitmentCollectionAssessment assessment = assess(forecast, 10, 3);
        assertEquals(1, assessment.semiCommitmentRemainingStock());
        assertEquals(SemiCommitmentCollectionClassification.LIKELY_AVAILABLE,
                assessment.classification());
    }

    /** Sections 9 and 30: follow-on intent never reserves stock, however much of it there is. */
    @Test
    void followOnIntentNeverReservesStock() {
        SpotCommitmentPressure pressure = pressure(10, 1, 0, 0, 5, 0, List.of(
                followOnClaim(0, 10, 1),
                followOnClaim(1, 10, 2),
                followOnClaim(2, 10, 3),
                followOnClaim(3, 10, 4),
                followOnClaim(4, 10, 5)));
        OpponentCommitmentForecast forecast = forecast(pressure, 5, 0, 0, 5, 0);

        assertEquals(0, pressure.directClaimsStrictlyBefore(9));
        assertEquals(0, pressure.semiReservedDirectPortionsStrictlyBefore(9));
        assertEquals(0, pressure.semiReservedDirectPortions());
        assertEquals(0, SemiCommitmentForecast.derive(forecast).semiReservedSpots());

        SemiCommitmentCollectionAssessment assessment = assess(forecast, 10, 9);
        assertEquals(1, assessment.semiCommitmentRemainingStock());
        assertTrue(assessment.semiCommitmentRealizable());
        assertEquals(SemiCommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE,
                assessment.classification());
        assertEquals(SemiCommitmentAdjustmentWeights.DEFAULT_FOLLOW_ON_INTENT_BEFORE_WEIGHT,
                assessment.semiCommitmentValueUnits());
    }

    /**
     * Section 7: the reservation is one portion at the spot, not a toll on every visit. Two own
     * collections on a two-portion spot with a direct claim before both lose one portion in total, so
     * the second collection is the only one that runs out.
     */
    @Test
    void theSameReservationIsNotChargedOncePerOwnCollection() {
        OpponentCommitmentForecast forecast = forecast(spot(10, 2, directClaim(0, 10, 2)));

        SemiCommitmentCollectionAssessment first = SEMI.assessCollection(
                Map.of(new Position(10), 2), new Position(10), 9, forecast, WEIGHTS);
        SemiCommitmentCollectionAssessment second = SEMI.assessCollection(
                Map.of(new Position(10), 1), new Position(10), 9, forecast, WEIGHTS);

        assertEquals(1, first.semiCommitmentRemainingStock());
        assertTrue(first.semiCommitmentRealizable());
        assertEquals(SemiCommitmentCollectionClassification.DIRECT_INTENT_BEFORE,
                first.classification());
        assertEquals(0, second.semiCommitmentRemainingStock());
        assertEquals(SemiCommitmentCollectionClassification.SEMI_CLAIMED_FIRST,
                second.classification(),
                "One reserved portion of the two, so exactly one of the two collections is lost");
    }

    /** Sections 6 and 54: hard plus semi never exceed the stock, and forecast stock never goes negative. */
    @Test
    void hardAndSemiTogetherNeverExceedCapacity() {
        for (int stock = 0; stock <= 3; stock++) {
            for (int observedClaimers = 0; observedClaimers <= 3; observedClaimers++) {
                List<CommittedOpponentClaim> claims = new ArrayList<>();
                for (int index = 0; index < observedClaimers; index++) {
                    claims.add(observedClaim(index, 10));
                }
                claims.add(directClaim(90, 10, 1));
                claims.add(directClaim(91, 10, 2));
                SpotCommitmentPressure pressure = pressure(
                        10, stock, observedClaimers, 2, 0,
                        Math.min(stock, observedClaimers), claims);

                int hardBefore = pressure.hardConsumedStrictlyBefore(9);
                int semiBefore = pressure.semiReservedDirectPortionsStrictlyBefore(9);
                String shape = "stock=" + stock + " observed=" + observedClaimers;
                assertTrue(semiBefore <= 1, "Bounded per-spot reservation: " + shape);
                assertTrue(hardBefore + semiBefore <= stock, "Within capacity: " + shape);
            }
        }
    }

    /**
     * Sections 11 and 54: the three-way ordering must hold for every combination of claim classes and
     * arrival positions, not only the shapes the planner fixtures happen to produce.
     */
    @Test
    void oldForecastNeverExceedsSemiAndSemiNeverExceedsCommitment() {
        int[] steps = {0, 3, 6, 9};
        for (int stock = 1; stock <= 3; stock++) {
            for (int observed = 0; observed <= 2; observed++) {
                for (int direct = 0; direct <= 3; direct++) {
                    for (int followOn = 0; followOn <= 2; followOn++) {
                        for (int step : steps) {
                            assertOrdering(stock, observed, direct, followOn, step);
                        }
                    }
                }
            }
        }
    }

    /** Section 12: every classification is reachable, and the classifier is total. */
    @Test
    void everyClassificationIsReachable() {
        assertEquals(SemiCommitmentCollectionClassification.UNFORECASTED,
                SEMI.assessCollection(Map.of(new Position(10), 1), new Position(10), 4,
                        OpponentCommitmentForecast.empty(), WEIGHTS).classification());
        assertEquals(SemiCommitmentCollectionClassification.LIKELY_AVAILABLE,
                assess(forecast(spot(10, 1, directClaim(0, 10, 8))), 10, 3).classification());
        assertEquals(SemiCommitmentCollectionClassification.CONTESTED_TIE,
                assess(forecast(spot(10, 1, directClaim(0, 10, 6))), 10, 6).classification());
        assertEquals(SemiCommitmentCollectionClassification.DIRECT_INTENT_BEFORE,
                assess(forecast(spot(10, 2, directClaim(0, 10, 2))), 10, 9).classification());
        assertEquals(SemiCommitmentCollectionClassification.SEMI_CLAIMED_FIRST,
                assess(forecast(spot(10, 1, directClaim(0, 10, 2))), 10, 9).classification());
        assertEquals(SemiCommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE,
                assess(forecast(spot(10, 1, followOnClaim(0, 10, 2))), 10, 9).classification());
        assertEquals(SemiCommitmentCollectionClassification.HARD_CLAIMED_FIRST,
                assess(forecast(spot(10, 1, observedClaim(0, 10))), 10, 9).classification());
    }

    /** Sections 14 and 15: the tiers are fixed integers in a fixed order. */
    @Test
    void weightTiersAreOrdinalAndOrdered() {
        assertEquals(4, SemiCommitmentAdjustmentWeights.DEFAULT_LIKELY_AVAILABLE_WEIGHT);
        assertEquals(4, SemiCommitmentAdjustmentWeights.DEFAULT_UNFORECASTED_WEIGHT);
        assertEquals(3, SemiCommitmentAdjustmentWeights.DEFAULT_FOLLOW_ON_INTENT_BEFORE_WEIGHT);
        assertEquals(2, SemiCommitmentAdjustmentWeights.DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT);
        assertEquals(2, SemiCommitmentAdjustmentWeights.DEFAULT_CONTESTED_TIE_WEIGHT);
        assertEquals(1, SemiCommitmentAdjustmentWeights.DEFAULT_SEMI_CLAIMED_FIRST_WEIGHT);
        assertEquals(0, SemiCommitmentAdjustmentWeights.DEFAULT_HARD_CLAIMED_FIRST_WEIGHT);

        assertTrue(SemiCommitmentAdjustmentWeights.DEFAULT_SEMI_CLAIMED_FIRST_WEIGHT
                        > SemiCommitmentAdjustmentWeights.DEFAULT_HARD_CLAIMED_FIRST_WEIGHT,
                "A bounded reservation is weaker evidence than an observed collector");
        assertTrue(SemiCommitmentAdjustmentWeights.DEFAULT_SEMI_CLAIMED_FIRST_WEIGHT
                        < SemiCommitmentAdjustmentWeights.DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT,
                "But worse than a direct conflict the spot still has capacity for");

        for (SemiCommitmentCollectionClassification classification
                : SemiCommitmentCollectionClassification.values()) {
            assertTrue(WEIGHTS.weightFor(classification) >= 0);
        }
    }

    /** Sections 50 and 55: the derived view is bounded, order-stable and enforces its own probe. */
    @Test
    void derivedViewIsBoundedAndDeterministic() {
        OpponentCommitmentForecast forecast = forecast(
                spot(30, 1, directClaim(0, 30, 2)),
                spot(10, 2, directClaim(1, 10, 3)),
                spot(20, 1, directClaim(2, 20, 4)));

        SemiCommitmentForecast first = SemiCommitmentForecast.derive(forecast);
        SemiCommitmentForecast second = SemiCommitmentForecast.derive(forecast);

        assertEquals(first, second);
        assertEquals(3, first.semiReservedSpots());
        assertEquals(1, first.maxSemiReservedPortions());

        assertThrows(IllegalArgumentException.class,
                () -> new SemiCommitmentForecast(forecast, 1, 2),
                "More than one reserved portion at a spot is not representable");
        assertThrows(IllegalArgumentException.class,
                () -> new SemiCommitmentForecast(forecast, 4, 1),
                "More reserved spots than stocked spots is not representable");

        SemiCommitmentForecast empty = SemiCommitmentForecast.empty();
        assertEquals(0, empty.semiReservedSpots());
        assertEquals(0, empty.maxSemiReservedPortions());
        assertNull(empty.pressureAt(new Position(10)));
    }

    private static void assertOrdering(
            int stock, int observed, int direct, int followOn, int ourStep) {
        List<CommittedOpponentClaim> claims = new ArrayList<>();
        for (int index = 0; index < observed; index++) {
            claims.add(observedClaim(index, 10));
        }
        for (int index = 0; index < direct; index++) {
            claims.add(directClaim(10 + index, 10, 1 + index));
        }
        for (int index = 0; index < followOn; index++) {
            claims.add(followOnClaim(20 + index, 10, 1 + index));
        }
        SpotCommitmentPressure pressure = pressure(
                10, stock, observed, direct, followOn, Math.min(stock, observed), claims);
        OpponentCommitmentForecast forecast = forecast(
                pressure, claims.size(), observed, direct, followOn, Math.min(stock, observed));
        Map<Position, Integer> currentStock = Map.of(new Position(10), stock);

        int hardBefore = pressure.hardConsumedStrictlyBefore(ourStep);
        int semiBefore = pressure.semiReservedDirectPortionsStrictlyBefore(ourStep);
        int allBefore = (int) pressure.claims().stream()
                .filter(claim -> claim.forecastArrivalStep() < ourStep)
                .count();

        int oldRemaining = Math.max(0, stock - allBefore);
        int semiRemaining = SEMI.assessCollection(
                currentStock, new Position(10), ourStep, forecast, WEIGHTS)
                .semiCommitmentRemainingStock();
        int commitmentRemaining = HARD_ONLY.assessCollection(
                currentStock, new Position(10), ourStep, forecast,
                CommitmentAdjustmentWeights.defaults())
                .commitmentRemainingStock();

        String shape = "stock=" + stock + " observed=" + observed + " direct=" + direct
                + " followOn=" + followOn + " ourStep=" + ourStep;
        assertEquals(Math.max(0, stock - hardBefore - semiBefore), semiRemaining, shape);
        assertTrue(semiBefore <= 1, "Bounded per-spot reservation: " + shape);
        assertTrue(hardBefore + semiBefore <= stock, "Within capacity: " + shape);
        assertTrue(oldRemaining <= semiRemaining, "M10 can never keep more than M12.1: " + shape);
        assertTrue(semiRemaining <= commitmentRemaining,
                "M12.1 can never keep more than M12: " + shape);
        assertTrue(oldRemaining >= 0 && semiRemaining >= 0 && commitmentRemaining >= 0,
                "Forecast stock never goes negative: " + shape);
    }

    private static SemiCommitmentCollectionAssessment assess(
            OpponentCommitmentForecast forecast, int position, int step) {
        return SEMI.assessCollection(
                stockOf(forecast), new Position(position), step, forecast, WEIGHTS);
    }

    private static int hardRemaining(OpponentCommitmentForecast forecast, int position, int step) {
        return HARD_ONLY.assessCollection(
                stockOf(forecast), new Position(position), step, forecast,
                CommitmentAdjustmentWeights.defaults()).commitmentRemainingStock();
    }

    private static Map<Position, Integer> stockOf(OpponentCommitmentForecast forecast) {
        Map<Position, Integer> stock = new LinkedHashMap<>();
        forecast.pressureBySpot()
                .forEach((spot, pressure) -> stock.put(spot, pressure.currentStock()));
        return stock;
    }

    /** Builds one spot whose commitment counts are derived from the claims it is given. */
    private static SpotCommitmentPressure spot(
            int position, int stock, CommittedOpponentClaim... claims) {
        List<CommittedOpponentClaim> list = List.of(claims);
        int observed = count(list, OpponentClaimCommitment.OBSERVED_NOW);
        return pressure(
                position,
                stock,
                observed,
                count(list, OpponentClaimCommitment.DIRECT_INTENT),
                count(list, OpponentClaimCommitment.FOLLOW_ON_INTENT),
                Math.min(stock, observed),
                list);
    }

    private static SpotCommitmentPressure pressure(
            int position,
            int stock,
            int observed,
            int direct,
            int followOn,
            int hardConsumed,
            List<CommittedOpponentClaim> claims) {
        return new SpotCommitmentPressure(
                new Position(position), stock, observed, direct, followOn, hardConsumed, claims);
    }

    private static OpponentCommitmentForecast forecast(SpotCommitmentPressure... spots) {
        Map<Position, SpotCommitmentPressure> byPosition = new LinkedHashMap<>();
        int claims = 0;
        int observed = 0;
        int direct = 0;
        int followOn = 0;
        int hard = 0;
        for (SpotCommitmentPressure pressure : spots) {
            byPosition.put(pressure.spot(), pressure);
            claims += pressure.claims().size();
            observed += pressure.observedNowClaims();
            direct += pressure.directIntentClaims();
            followOn += pressure.followOnIntentClaims();
            hard += pressure.hardConsumedPortions();
        }
        return new OpponentCommitmentForecast(
                byPosition, spots.length, spots.length, spots.length, claims, observed, direct,
                followOn, hard);
    }

    private static OpponentCommitmentForecast forecast(
            SpotCommitmentPressure pressure,
            int claims,
            int observed,
            int direct,
            int followOn,
            int hard) {
        return new OpponentCommitmentForecast(
                Map.of(pressure.spot(), pressure), Math.max(1, claims), Math.max(1, claims), 1,
                claims, observed, direct, followOn, hard);
    }

    private static int count(List<CommittedOpponentClaim> claims, OpponentClaimCommitment kind) {
        return (int) claims.stream().filter(claim -> claim.commitment() == kind).count();
    }

    private static CommittedOpponentClaim observedClaim(int agentIndex, int position) {
        return new CommittedOpponentClaim(
                claim(agentIndex, position, 0, IntentRank.PRIMARY),
                OpponentClaimCommitment.OBSERVED_NOW);
    }

    private static CommittedOpponentClaim directClaim(int agentIndex, int position, int step) {
        return new CommittedOpponentClaim(
                claim(agentIndex, position, step, IntentRank.PRIMARY),
                OpponentClaimCommitment.DIRECT_INTENT);
    }

    private static CommittedOpponentClaim followOnClaim(int agentIndex, int position, int step) {
        return new CommittedOpponentClaim(
                claim(agentIndex, position, step, IntentRank.SECONDARY),
                OpponentClaimCommitment.FOLLOW_ON_INTENT);
    }

    private static ForecastOpponentClaim claim(
            int agentIndex, int position, int step, IntentRank rank) {
        return new ForecastOpponentClaim(5, agentIndex, 0, new Position(position), step, rank, 1);
    }
}
