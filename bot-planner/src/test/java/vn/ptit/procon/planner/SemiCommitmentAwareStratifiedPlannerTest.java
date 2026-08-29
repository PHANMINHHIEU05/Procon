package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/**
 * M12.1 semi-committed direct intent over the unchanged M11 stratified search.
 *
 * <p>All three modes under comparison run the identical production budget ({@code 64/48/4}) and the
 * identical stage schedule ({@code 16/24/24}) on the identical {@link DayState} and the identical
 * opponent forecast. Only the reading of that forecast differs, which is the whole scope of M12.1.</p>
 *
 * <p>Both fixtures are purpose-built to separate the three models rather than to reproduce a live
 * scoreline: server-realized totals are not available inside a deterministic fixture, so nothing here
 * asserts them. What is asserted is that M12.1 lands strictly between the M10 binary model and the M12
 * hard-only model, which is the structural claim the live calibration motivated.</p>
 */
class SemiCommitmentAwareStratifiedPlannerTest {

    private static final AnytimePlannerConfig PRODUCTION = AnytimePlannerConfig.defaults();

    /** Zero expansions: the search returns exactly its own initial semi-commitment incumbent. */
    private static final AnytimePlannerConfig NO_SEARCH = new AnytimePlannerConfig(0, 48, 4);

    private static final StratifiedSearchConfig STAGES = StratifiedSearchConfig.defaults();

    /**
     * Section 36: the 8x8 calibration shape — three PATROL, one REFUEL, three opponent collectors, one
     * rawKind-one non-collector, eight stocked spots over four brands, with both a hard observed claim
     * and future claims present. Strict inequalities in both gaps.
     */
    @Test
    void eightByEightCalibrationShapeSeparatesAllThreeModels() {
        DayState state = eightByEightState();
        AnytimePlanResult result = semiCommitmentAware(PRODUCTION).planWithStats(state);
        SemiCommitmentAwarePlanEvaluation evaluation =
                result.semiCommitmentAwareEvaluation().orElseThrow();
        OpponentCommitmentForecast forecast = forecastFor(state);

        assertEquals(4, forecast.observedAgentCount(), "Four observed agents, one of them rawKind one");
        assertEquals(3, forecast.collectionEligibleAgentCount(), "Three rawKind-zero collectors");
        assertEquals(8, forecast.stockedSpotCount());
        assertEquals(6, forecast.forecastClaims());
        assertEquals(1, forecast.observedNowClaims(), "One opponent stands on a stocked spot");
        assertEquals(3, forecast.directIntentClaims());
        assertEquals(2, forecast.followOnIntentClaims());
        assertEquals(1, forecast.hardConsumedPortions());

        // The three-way calibration on one selected plan.
        assertEquals(8, evaluation.base().udonTotal(), "raw");
        assertEquals(3, evaluation.oldForecastRealizableCollections(), "M10 binary");
        assertEquals(4, evaluation.semiCommitmentRealizableCollections(), "M12.1 bounded middle");
        assertEquals(7, evaluation.commitmentRealizableCollections(), "M12 hard-only");
        assertEquals(4, evaluation.base().teamBrandCount());
        assertEquals(3, evaluation.semiCommitmentRealizableBrandCount());
        assertEquals(1, evaluation.hardClaimedFirstCollections());
        assertEquals(3, evaluation.semiClaimedFirstCollections());

        assertOrdering(evaluation, "8x8");
        assertTrue(evaluation.oldForecastRealizableCollections()
                        < evaluation.semiCommitmentRealizableCollections(),
                "M12.1 must keep strictly more than the old binary model");
        assertTrue(evaluation.semiCommitmentRealizableCollections()
                        < evaluation.commitmentRealizableCollections(),
                "M12.1 must keep strictly less than the hard-only model");
    }

    /**
     * Section 37: the critical 12x12 regression — five PATROL, one REFUEL, five opponent collectors,
     * one non-collector, eight stocked spots over four brands, at least one observed claim, several
     * direct and several follow-on claims, and two direct claims landing on the same spot.
     */
    @Test
    void twelveByTwelveFiveCollectorFixtureProvesTheBoundedModelSitsStrictlyBetween() {
        DayState state = twelveByTwelveState();
        AnytimePlanResult result = semiCommitmentAware(PRODUCTION).planWithStats(state);
        SemiCommitmentAwarePlanEvaluation evaluation =
                result.semiCommitmentAwareEvaluation().orElseThrow();
        OpponentCommitmentForecast forecast = forecastFor(state);
        SemiCommitmentForecast semi = SemiCommitmentForecast.derive(forecast);

        assertEquals(6, forecast.observedAgentCount());
        assertEquals(5, forecast.collectionEligibleAgentCount());
        assertEquals(8, forecast.stockedSpotCount());
        assertEquals(11, forecast.forecastClaims());
        assertEquals(1, forecast.observedNowClaims(), "At least one OBSERVED_NOW claim");
        assertEquals(5, forecast.directIntentClaims(), "Several DIRECT claims");
        assertEquals(5, forecast.followOnIntentClaims(), "Several FOLLOW_ON claims");
        assertEquals(1, forecast.hardConsumedPortions());
        assertEquals(2, forecast.pressureAt(new Position(52)).directIntentClaims(),
                "Two DIRECT claims target spot 52, so the per-spot cap is genuinely exercised");
        assertEquals(1, semi.pressureAt(new Position(52)).semiReservedDirectPortions(),
                "Two direct claimers on one spot still reserve exactly one portion");
        assertEquals(4, semi.semiReservedSpots());
        assertEquals(1, semi.maxSemiReservedPortions(), "Section 50: never above one per spot");

        // The three-way calibration on one selected plan.
        assertEquals(12, evaluation.base().udonTotal(), "raw");
        assertEquals(3, evaluation.oldForecastRealizableCollections(), "M10 binary");
        assertEquals(7, evaluation.semiCommitmentRealizableCollections(), "M12.1 bounded middle");
        assertEquals(11, evaluation.commitmentRealizableCollections(), "M12 hard-only");
        assertEquals(1, evaluation.hardClaimedFirstCollections());
        assertEquals(4, evaluation.semiClaimedFirstCollections());
        assertEquals(1, evaluation.directIntentBeforeCollections());
        assertEquals(4, evaluation.followOnIntentBeforeCollections());
        assertEquals(1, evaluation.tieCollections());

        assertOrdering(evaluation, "12x12");
        assertTrue(evaluation.oldForecastRealizableCollections()
                        < evaluation.semiCommitmentRealizableCollections()
                        && evaluation.semiCommitmentRealizableCollections()
                        < evaluation.commitmentRealizableCollections(),
                "old < semi < commitment on the purpose-built fixture");
    }

    /**
     * Sections 6 and 54: per spot the hard portions plus the bounded reservation never exceed the
     * stock, and the reservation itself is never above one. Asserted over every fixture rather than
     * one lucky shape.
     */
    @Test
    void hardPlusSemiNeverExceedCapacityOnAnyStockedSpot() {
        for (DayState state : List.of(eightByEightState(), twelveByTwelveState())) {
            SemiCommitmentForecast semi = SemiCommitmentForecast.derive(forecastFor(state));
            assertTrue(semi.maxSemiReservedPortions() <= 1,
                    "semiReservedDirectPortions per spot <= 1");
            for (SpotCommitmentPressure pressure : semi.commitment().pressureBySpot().values()) {
                String label = "spot " + pressure.spot().value();
                assertTrue(pressure.semiReservedDirectPortions() <= 1, label + ": reservation <= 1");
                assertTrue(pressure.hardConsumedPortions() + pressure.semiReservedDirectPortions()
                                <= pressure.currentStock(),
                        label + ": hard + semi reserved <= current stock");
            }
        }
    }

    /**
     * Section 40: one same-search A/B/C report. All three modes run the identical architecture at the
     * identical budget; only the objective differs. Section 40 forbids asserting that C must win.
     */
    @Test
    void sameSearchAcrossAllThreeModesReportsEveryObjectiveAtTheIdenticalBudget() {
        DayState state = twelveByTwelveState();
        AnytimePlanResult a = stratifiedIntentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult b = commitmentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult c = semiCommitmentAware(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation m10 = a.intentAwareEvaluation().orElseThrow();
        CommitmentAwarePlanEvaluation m12 = b.commitmentAwareEvaluation().orElseThrow();
        SemiCommitmentAwarePlanEvaluation m121 = c.semiCommitmentAwareEvaluation().orElseThrow();
        StratifiedSearchStats depthA = a.stratifiedSearchStats().orElseThrow();
        StratifiedSearchStats depthB = b.stratifiedSearchStats().orElseThrow();
        StratifiedSearchStats depthC = c.stratifiedSearchStats().orElseThrow();

        // A: the shipped M11 stratified intent objective.
        assertEquals(8, m10.base().udonTotal());
        assertEquals(4, m10.forecastRealizableCollections());
        assertEquals(14, m10.adjustedCollectionScore().value());
        // B: the M12 hard-only objective.
        assertEquals(12, m12.base().udonTotal());
        assertEquals(11, m12.commitmentRealizableCollections());
        assertEquals(3, m12.oldForecastRealizableCollections());
        assertEquals(28, m12.adjustedCollectionScore().value());
        // C: the M12.1 bounded middle objective.
        assertEquals(12, m121.base().udonTotal());
        assertEquals(7, m121.semiCommitmentRealizableCollections());
        assertEquals(11, m121.commitmentRealizableCollections());
        assertEquals(3, m121.oldForecastRealizableCollections());
        assertEquals(24, m121.adjustedCollectionScore().value());

        // Identical search work in all three modes: no extra phase, no budget increase.
        for (AnytimePlanResult result : List.of(a, b, c)) {
            assertEquals(PRODUCTION.maxExpandedStates(), result.stats().expandedStates());
        }
        for (StratifiedSearchStats depth : List.of(depthA, depthB, depthC)) {
            assertEquals(8, depth.strategiesQualified());
            assertEquals(16, depth.discoveryExpansions());
            assertEquals(24, depth.qualificationExpansions());
            assertEquals(24, depth.exploitationExpansions());
        }
    }

    /** Section 34: the M12 mode keeps its own objective and never acquires the M12.1 one. */
    @Test
    void theM12ModeIsUnchangedByTheNewMode() {
        for (DayState state : List.of(eightByEightState(), twelveByTwelveState())) {
            AnytimePlanResult m12 = commitmentAware(PRODUCTION).planWithStats(state);

            assertEquals(recomputedCommitmentObjective(state, m12.plan(), m12.evaluation()),
                    m12.commitmentAwareEvaluation().orElseThrow(),
                    "The M12 mode still reports exactly the recomputed M12 objective");
            assertTrue(m12.semiCommitmentAwareEvaluation().isEmpty(),
                    "The M12 mode must not acquire an M12.1 evaluation");
            assertTrue(m12.intentAwareEvaluation().isEmpty());
        }
        // The M12 comparator itself is untouched: its own keys still order as before.
        PlanEvaluation base = new PlanEvaluation(4, 8, 3, 40, 30, "same");
        CommitmentAwarePlanEvaluation direct = new CommitmentAwarePlanEvaluation(
                base, new CommitmentAdjustedCollectionScore(26), 4, 8, 4, 0, 2, 0, 1, 5);
        CommitmentAwarePlanEvaluation followOn = new CommitmentAwarePlanEvaluation(
                base, new CommitmentAdjustedCollectionScore(26), 4, 8, 4, 0, 1, 1, 1, 5);
        assertTrue(followOn.betterThan(direct));
    }

    /** Section 35: the shipped M11 mode is untouched by the presence of the new mode. */
    @Test
    void theM11ModeIsUnchangedByTheNewMode() {
        for (DayState state : List.of(eightByEightState(), twelveByTwelveState())) {
            AnytimePlanResult m11 = stratifiedIntentAware(PRODUCTION).planWithStats(state);

            assertEquals(recomputedIntentObjective(state, m11.plan(), m11.evaluation()),
                    m11.intentAwareEvaluation().orElseThrow(),
                    "The M11 mode still reports exactly the recomputed M10 objective");
            assertTrue(m11.commitmentAwareEvaluation().isEmpty());
            assertTrue(m11.semiCommitmentAwareEvaluation().isEmpty(),
                    "The M11 mode must not acquire an M12.1 evaluation");
        }
        IntentAwarePlanEvaluation better = new IntentAwarePlanEvaluation(
                new PlanEvaluation(4, 7, 3, 40, 30, "a"),
                new IntentAdjustedCollectionScore(24), 4, 7, 0, 0, 7);
        IntentAwarePlanEvaluation worse = new IntentAwarePlanEvaluation(
                new PlanEvaluation(4, 9, 3, 40, 30, "b"),
                new IntentAdjustedCollectionScore(24), 3, 7, 2, 0, 7);
        assertTrue(better.betterThan(worse));
    }

    /** Section 17: the initial incumbent passes through the identical M12.1 pipeline. */
    @Test
    void initialIncumbentIsEvaluatedByTheSameSemiCommitmentPipeline() {
        DayState state = twelveByTwelveState();
        AnytimePlanResult zeroSearch = semiCommitmentAware(NO_SEARCH).planWithStats(state);
        SemiCommitmentAwarePlanEvaluation incumbent =
                zeroSearch.semiCommitmentAwareEvaluation().orElseThrow();

        assertEquals(0, zeroSearch.stats().expandedStates());
        assertEquals(0, zeroSearch.stats().incumbentImprovements());
        assertEquals(incumbent.base(), zeroSearch.evaluation());
        assertEquals(recomputedSemiObjective(state, zeroSearch.plan(), zeroSearch.evaluation()),
                incumbent,
                "Every field of the incumbent evaluation is reproducible from the public pipeline");
        assertOrdering(incumbent, "incumbent");
    }

    /** Sections 2 and 45: the M12.1 mode inherits the M11 bounds exactly, with no budget increase. */
    @Test
    void searchStaysWithinTheUnchangedM11Bounds() {
        for (DayState state : List.of(eightByEightState(), twelveByTwelveState())) {
            AnytimePlanResult result = semiCommitmentAware(PRODUCTION).planWithStats(state);
            StratifiedSearchStats depth = result.stratifiedSearchStats().orElseThrow();

            assertTrue(result.stats().expandedStates() <= 64, "expanded <= 64");
            assertTrue(depth.frontierPeak() <= 48, "frontier <= 48");
            assertTrue(depth.strategiesQualified() <= 8, "qualified strategies <= 8");
            assertEquals(result.stats().expandedStates(), depth.totalExpansions());
            assertEquals(depth.discoveryExpansions() + depth.qualificationExpansions()
                    + depth.exploitationExpansions(), depth.totalExpansions());
            assertEquals(16, depth.discoveryExpansions());
            assertEquals(24, depth.qualificationExpansions());
            assertEquals(24, depth.exploitationExpansions());
            assertTrue(result.stats().candidateRetained()
                            <= result.stats().expandedStates() * PRODUCTION.topCandidatesPerState(),
                    "candidates per state <= 4");
        }
    }

    /** Section 55: identical input yields an identical plan, evaluation, stats and derived view. */
    @Test
    void repeatedPlanningIsBitForBitDeterministic() {
        AnytimePlanResult first = semiCommitmentAware(PRODUCTION).planWithStats(twelveByTwelveState());
        AnytimePlanResult second = semiCommitmentAware(PRODUCTION).planWithStats(twelveByTwelveState());

        assertEquals(actionsOf(first.plan()), actionsOf(second.plan()));
        assertEquals(first.evaluation(), second.evaluation());
        assertEquals(first.semiCommitmentAwareEvaluation(), second.semiCommitmentAwareEvaluation());
        assertEquals(first.stats(), second.stats());
        assertEquals(first.stratifiedSearchStats(), second.stratifiedSearchStats());
        assertEquals(SemiCommitmentForecast.derive(forecastFor(twelveByTwelveState())),
                SemiCommitmentForecast.derive(forecastFor(twelveByTwelveState())),
                "The derived semi-reservation view must itself be reproducible");
    }

    @Test
    void m121ExistingFixtureIsUnaffectedByM13ModeConstruction() {
        DayState state = twelveByTwelveState();
        AnytimePlanResult before = semiCommitmentAware(PRODUCTION).planWithStats(state);
        new HorizonAwareSemiCommitmentPlanner().planWithStats(state);
        AnytimePlanResult after = semiCommitmentAware(PRODUCTION).planWithStats(state);

        assertEquals(actionsOf(before.plan()), actionsOf(after.plan()));
        assertEquals(before.evaluation(), after.evaluation());
        assertEquals(before.semiCommitmentAwareEvaluation(), after.semiCommitmentAwareEvaluation());
        assertEquals(before.stats(), after.stats());
        assertEquals(before.stratifiedSearchStats(), after.stratifiedSearchStats());
        assertTrue(before.horizonAwareEvaluation().isEmpty());
        assertTrue(after.horizonAwareEvaluation().isEmpty());
    }

    /**
     * Section 16: the fourteen-key objective. Brand coverage outranks the adjusted score, the score
     * outranks the realizable count, and a bounded semi loss is broken directly after a hard loss.
     */
    @Test
    void completePlanPreferenceRanksTheFourteenKeyObjective() {
        PlanEvaluation planABase = new PlanEvaluation(4, 9, 3, 40, 30, "plan-a");
        PlanEvaluation planBBase = new PlanEvaluation(4, 7, 3, 40, 30, "plan-b");
        SemiCommitmentAwarePlanEvaluation planA = new SemiCommitmentAwarePlanEvaluation(
                planABase, new SemiCommitmentAdjustedCollectionScore(20), 3, 5, 6, 3, 3, 1, 0, 0, 0, 3);
        SemiCommitmentAwarePlanEvaluation planB = new SemiCommitmentAwarePlanEvaluation(
                planBBase, new SemiCommitmentAdjustedCollectionScore(24), 4, 6, 7, 5, 0, 1, 1, 0, 0, 5);

        assertTrue(planB.betterThan(planA),
                "More semi-commitment-realizable brands is the primary key, above raw volume");
        assertFalse(planA.betterThan(planB));
        assertTrue(planA.base().udonTotal() > planB.base().udonTotal(),
                "Plan A really does win on raw simulator collections");
        assertEquals(planB, List.of(planA, planB).stream()
                .min(SemiCommitmentAwarePlanEvaluation.preference()).orElseThrow());

        // With brands equal, the adjusted score decides before the realizable count and raw volume.
        SemiCommitmentAwarePlanEvaluation equalBrands = new SemiCommitmentAwarePlanEvaluation(
                planABase, new SemiCommitmentAdjustedCollectionScore(20), 4, 5, 6, 3, 3, 1, 0, 0, 0, 3);
        assertTrue(planB.betterThan(equalBrands));

        // With brands, score and realizable count equal, fewer hard losses wins before fewer semi ones.
        PlanEvaluation shared = new PlanEvaluation(4, 8, 3, 40, 30, "same");
        SemiCommitmentAwarePlanEvaluation fewerHard = new SemiCommitmentAwarePlanEvaluation(
                shared, new SemiCommitmentAdjustedCollectionScore(22), 4, 6, 7, 4, 1, 2, 0, 0, 0, 5);
        SemiCommitmentAwarePlanEvaluation fewerSemi = new SemiCommitmentAwarePlanEvaluation(
                shared, new SemiCommitmentAdjustedCollectionScore(22), 4, 6, 7, 4, 2, 1, 0, 0, 0, 5);
        assertTrue(fewerHard.betterThan(fewerSemi),
                "Hard losses are broken before the weaker bounded semi losses");
    }

    /**
     * Sections 14 and 15: deterministic integer tiers only, with the bounded semi loss strictly worse
     * than a direct conflict that capacity survives and strictly better than a hard loss.
     */
    @Test
    void weightTiersAreStructuralIntegersWithSemiBetweenHardAndDirect() {
        SemiCommitmentAdjustmentWeights weights = SemiCommitmentAdjustmentWeights.defaults();

        assertEquals(4, weights.likelyAvailableWeight());
        assertEquals(4, weights.unforecastedWeight());
        assertEquals(3, weights.followOnIntentBeforeWeight());
        assertEquals(2, weights.directIntentBeforeWeight());
        assertEquals(2, weights.contestedTieWeight());
        assertEquals(1, weights.semiClaimedFirstWeight());
        assertEquals(0, weights.hardClaimedFirstWeight());
        assertTrue(weights.hardClaimedFirstWeight() < weights.semiClaimedFirstWeight(),
                "A bounded reservation is weaker evidence than an observed loss");
        assertTrue(weights.semiClaimedFirstWeight() < weights.directIntentBeforeWeight(),
                "A reservation that consumes the last portion is worse than one capacity survives");
        assertEquals(7, SemiCommitmentCollectionClassification.values().length,
                "Seven attribution classes and no enum explosion");
        assertEquals(3, OpponentClaimCommitment.values().length,
                "The three M12 commitment classes are unchanged");
    }

    /**
     * Section 18: the new candidate guidance breaks a bounded semi loss directly after a hard loss, in
     * both the coverage and the harvest phase.
     */
    @Test
    void candidateGuidanceBreaksSemiClaimedFirstDirectlyAfterHardClaimedFirst() {
        SemiCommitmentAwareCandidateMetrics fewerSemi = candidate(1, 1);
        SemiCommitmentAwareCandidateMetrics moreSemi = candidate(1, 2);
        SemiCommitmentAwareCandidateMetrics fewerHard = candidate(0, 2);

        assertTrue(SemiCommitmentAwareCandidateMetrics.coveragePreference()
                .compare(fewerSemi, moreSemi) < 0, "Coverage prefers fewer semi-claimed-first");
        assertTrue(SemiCommitmentAwareCandidateMetrics.harvestPreference()
                .compare(fewerSemi, moreSemi) < 0, "Harvest prefers fewer semi-claimed-first");
        assertTrue(SemiCommitmentAwareCandidateMetrics.coveragePreference()
                        .compare(fewerHard, fewerSemi) < 0,
                "A hard loss still outranks the weaker semi loss in the coverage phase");
        assertTrue(SemiCommitmentAwareCandidateMetrics.harvestPreference()
                        .compare(fewerHard, fewerSemi) < 0,
                "A hard loss still outranks the weaker semi loss in the harvest phase");
    }

    /** Sections 50 to 53: one bounded line each, carrying every calibration number we need. */
    @Test
    void diagnosticsCarryTheSemiCommitmentSummaryAndStayBounded() {
        DayState state = twelveByTwelveState();
        String logs = diagnosticsFor(state);

        assertEquals(1, count(logs, "OPPONENT_SEMI_COMMITMENT_SUMMARY "));
        String summary = line(logs, "OPPONENT_SEMI_COMMITMENT_SUMMARY ");
        for (String field : List.of(
                "day=0", "observedAgents=6", "collectionEligibleAgents=5", "forecastClaims=11",
                "observedNowClaims=1", "directIntentClaims=5", "followOnIntentClaims=5",
                "hardConsumedPortions=1", "semiReservedSpots=4", "maxSemiReservedPortions=1",
                "stockedSpots=8")) {
            assertTrue(summary.contains(field), "The bounded summary must report " + field);
        }

        assertEquals(1, count(logs, "ANYTIME_STRATIFIED_SEMI_COMMITMENT_START "));
        String start = line(logs, "ANYTIME_STRATIFIED_SEMI_COMMITMENT_START ");
        for (String field : List.of(
                "day=0", "incumbentLocalBrands=", "incumbentSemiCommitmentBrands=",
                "incumbentRawUdon=", "oldForecastRealizable=", "commitmentRealizable=",
                "semiCommitmentRealizable=", "incumbentSemiCommitmentScore=", "budget=64",
                "discoveryBudget=16", "qualificationBudget=24", "exploitationBudget=24")) {
            assertTrue(start.contains(field), "The START event must report " + field);
        }

        assertEquals(1, count(logs, "ANYTIME_STRATIFIED_SEMI_COMMITMENT_DONE "));
        String done = line(logs, "ANYTIME_STRATIFIED_SEMI_COMMITMENT_DONE ");
        for (String field : List.of(
                "day=0", "localBrands=", "semiCommitmentBrands=", "rawUdon=12",
                "oldForecastRealizableCollections=3", "commitmentRealizableCollections=11",
                "semiCommitmentRealizableCollections=7", "semiCommitmentAdjustedScore=",
                "hardClaimedFirst=1", "semiClaimedFirst=4", "directIntentBefore=1",
                "followOnIntentBefore=4", "tieCollections=1", "expanded=64", "completedPlans=65",
                "improvements=", "strategiesDiscovered=", "strategiesQualified=",
                "strategiesWithAtLeast2Expansions=", "strategiesWithAtLeast3Expansions=",
                "maxStrategyExpansionCount=", "discoveryExpansions=16", "qualificationExpansions=24",
                "exploitationExpansions=24", "frontierPeak=", "budgetExhausted=")) {
            assertTrue(done.contains(field), "The DONE event must report " + field);
        }
        // Section 50: no per-claim dumping anywhere.
        assertTrue(count(logs, "STRATEGY_DEPTH_SUMMARY ") <= 8,
                "Per-strategy diagnostics stay bounded to the eight qualified strategies");
        assertFalse(logs.contains("SEMI_COMMITMENT_CLAIM"), "No per-claim logging");
        assertFalse(logs.contains("SEMI_COMMITMENT_ATTRIBUTION"), "No per-attribution logging");
        assertFalse(logs.contains("MOVE_"), "Diagnostics must never dump actions");
        assertFalse(logs.contains("frontierState"), "Diagnostics must never dump the frontier");
        assertFalse(logs.contains("ANYTIME_STRATIFIED_COMMITMENT_DONE"),
                "The new mode must not impersonate the M12 search event");
        assertFalse(logs.contains("ANYTIME_STRATIFIED_INTENT_DONE"),
                "The new mode must not impersonate the M11 search event");
    }

    /** Section 55: the complete bounded diagnostic stream is deterministic too. */
    @Test
    void repeatedDiagnosticsAreBitForBitDeterministic() {
        assertEquals(diagnosticsFor(twelveByTwelveState()), diagnosticsFor(twelveByTwelveState()));
    }

    private static String diagnosticsFor(DayState state) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new SemiCommitmentAwareStratifiedPlanner(
                    PRODUCTION,
                    OpponentIntentConfig.defaults(),
                    IntentAdjustmentWeights.defaults(),
                    SemiCommitmentAdjustmentWeights.defaults(),
                    STAGES,
                    true).planWithStats(state);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** Sections 11 and 54: the invariants every M12.1 evaluation must satisfy. */
    private static void assertOrdering(SemiCommitmentAwarePlanEvaluation evaluation, String label) {
        assertTrue(evaluation.oldForecastRealizableCollections()
                        <= evaluation.semiCommitmentRealizableCollections(),
                label + ": oldForecastRealizable <= semiCommitmentRealizable");
        assertTrue(evaluation.semiCommitmentRealizableCollections()
                        <= evaluation.commitmentRealizableCollections(),
                label + ": semiCommitmentRealizable <= commitmentRealizable");
        assertTrue(evaluation.commitmentRealizableCollections() <= evaluation.base().udonTotal(),
                label + ": commitmentRealizable <= raw");
        assertTrue(evaluation.semiCommitmentRealizableBrandCount() <= evaluation.localTeamBrandCount(),
                label + ": semiCommitmentRealizableBrandCount <= localTeamBrandCount");
        assertTrue(evaluation.hardClaimedFirstCollections() + evaluation.semiClaimedFirstCollections()
                        <= evaluation.base().udonTotal(),
                label + ": hardClaimedFirst + semiClaimedFirst <= raw");
    }

    private static SemiCommitmentAwareCandidateMetrics candidate(int hard, int semi) {
        return new SemiCommitmentAwareCandidateMetrics(
                false, false, 10, 2, 0, 2, hard, semi, 0, 0, 0, 4, 4, 20,
                new Position(1), new AgentId(0));
    }

    private static int count(String logs, String prefix) {
        return (int) logs.lines().filter(line -> line.startsWith(prefix)).count();
    }

    private static String line(String logs, String prefix) {
        return logs.lines()
                .filter(candidate -> candidate.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing diagnostic line: " + prefix));
    }

    private static OpponentCommitmentForecast forecastFor(DayState state) {
        return OpponentCommitmentForecast.annotate(new OpponentIntentForecaster().forecast(state));
    }

    /** Structural view of a plan, since {@link TeamPlan} has reference identity semantics. */
    private static Map<Integer, List<String>> actionsOf(TeamPlan plan) {
        Map<Integer, List<String>> actions = new TreeMap<>();
        plan.actionsByAgent().forEach((agent, list) -> actions.put(
                agent.value(), list.stream().map(Object::toString).toList()));
        return actions;
    }

    /** Recomputes the M12.1 objective from the public evaluation pipeline. */
    private static SemiCommitmentAwarePlanEvaluation recomputedSemiObjective(
            DayState state, TeamPlan plan, PlanEvaluation base) {
        SemiCommitmentCollectionAttribution attribution =
                new SemiCommitmentForecastEvaluator().evaluate(
                        state,
                        new DaySimulator().simulate(state, plan),
                        forecastFor(state),
                        SemiCommitmentAdjustmentWeights.defaults());
        return new SemiCommitmentAwarePlanEvaluation(
                base,
                attribution.adjustedScore(),
                attribution.semiCommitmentRealizableBrands().size(),
                attribution.semiCommitmentRealizableCollections(),
                attribution.commitmentRealizableCollections(),
                attribution.oldForecastRealizableCollections(),
                attribution.hardClaimedFirstCollections(),
                attribution.semiClaimedFirstCollections(),
                attribution.directIntentBeforeCollections(),
                attribution.followOnIntentBeforeCollections(),
                attribution.tieCollections(),
                attribution.unforecastedCollections());
    }

    /** Recomputes the unchanged M12 objective from the public evaluation pipeline. */
    private static CommitmentAwarePlanEvaluation recomputedCommitmentObjective(
            DayState state, TeamPlan plan, PlanEvaluation base) {
        CommitmentCollectionAttribution attribution = new CommitmentForecastEvaluator().evaluate(
                state,
                new DaySimulator().simulate(state, plan),
                forecastFor(state),
                CommitmentAdjustmentWeights.defaults());
        return new CommitmentAwarePlanEvaluation(
                base,
                attribution.adjustedScore(),
                attribution.commitmentRealizableBrands().size(),
                attribution.commitmentRealizableCollections(),
                attribution.oldForecastRealizableCollections(),
                attribution.hardClaimedFirstCollections(),
                attribution.directIntentBeforeCollections(),
                attribution.followOnIntentBeforeCollections(),
                attribution.tieCollections(),
                attribution.unforecastedCollections());
    }

    /** Recomputes the unchanged M10 objective from the raw production pipeline. */
    private static IntentAwarePlanEvaluation recomputedIntentObjective(
            DayState state, TeamPlan plan, PlanEvaluation base) {
        IntentCollectionAttribution attribution = new IntentForecastEvaluator().evaluate(
                state,
                new DaySimulator().simulate(state, plan),
                new OpponentIntentForecaster().forecast(state),
                IntentAdjustmentWeights.defaults());
        return new IntentAwarePlanEvaluation(
                base,
                attribution.adjustedScore(),
                attribution.forecastRealizableBrands().size(),
                attribution.forecastRealizableCollections(),
                attribution.likelyClaimedFirstCollections(),
                attribution.tieCollections(),
                attribution.unforecastedCollections());
    }

    private static SemiCommitmentAwareStratifiedPlanner semiCommitmentAware(
            AnytimePlannerConfig config) {
        return new SemiCommitmentAwareStratifiedPlanner(
                config,
                OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()),
                false);
    }

    private static CommitmentAwareStratifiedPlanner commitmentAware(AnytimePlannerConfig config) {
        return new CommitmentAwareStratifiedPlanner(
                config,
                OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(),
                CommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()),
                false);
    }

    private static StratifiedIntentAwareAnytimePlanner stratifiedIntentAware(
            AnytimePlannerConfig config) {
        return new StratifiedIntentAwareAnytimePlanner(
                config,
                OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()),
                false);
    }

    /**
     * Section 36: 8x8, three PATROL, one REFUEL, three opponent collectors, one rawKind-one
     * non-collector, eight single-portion spots over four brands. The collector on spot 23 supplies the
     * hard observed claim, so the fixture is not degenerately claim-free.
     */
    private static DayState eightByEightState() {
        Terrain[] terrain = new Terrain[64];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<AgentState> agents = new ArrayList<>();
        int[] patrolStarts = {27, 28, 36};
        for (int index = 0; index < patrolStarts.length; index++) {
            agents.add(AgentState.patrol(new AgentId(index), new Position(patrolStarts[index]), 30));
        }
        agents.add(AgentState.refuel(new AgentId(3), new Position(35)));
        return state(
                new HexMap(8, 8, terrain),
                19,
                agents,
                List.of(spot("A", 7), spot("B", 15), spot("C", 23), spot("D", 31),
                        spot("A", 39), spot("B", 47), spot("C", 55), spot("D", 63)),
                List.of(other(23, 0), other(14, 0), other(46, 0), other(19, 1)));
    }

    /**
     * Section 37: 12x12, five PATROL, one REFUEL, five opponent collectors, one non-collector, eight
     * stocked spots over four brands. Spots 31 and 52 hold three portions each so hard depletion and
     * the bounded reservation can both bite without exhausting the spot, and two direct claims land on
     * spot 52 so the per-spot cap is genuinely exercised.
     */
    private static DayState twelveByTwelveState() {
        Terrain[] terrain = new Terrain[144];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<AgentState> agents = new ArrayList<>();
        int[] patrolStarts = {62, 63, 64, 74, 75};
        for (int index = 0; index < patrolStarts.length; index++) {
            agents.add(AgentState.patrol(new AgentId(index), new Position(patrolStarts[index]), 30));
        }
        agents.add(AgentState.refuel(new AgentId(5), new Position(76)));
        return state(
                new HexMap(12, 12, terrain),
                24,
                agents,
                List.of(stocked("A", 31, 3), spot("B", 34), spot("C", 37), spot("D", 40),
                        stocked("A", 52, 3), spot("B", 55), spot("C", 58), spot("D", 85)),
                List.of(other(31, 0), other(33, 0), other(51, 0), other(53, 0), other(86, 0),
                        other(74, 1)));
    }

    private static DayState state(
            HexMap map,
            int budget,
            List<AgentState> agents,
            List<UdonSpot> spots,
            List<ObservedOtherAgent> others) {
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                map, new DayStepBudgets(new int[] {budget}), List.of(), new FuelCapacity(30), spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock,
                List.of(new ObservedOtherGroup(5, others)));
    }

    private static ObservedOtherAgent other(int position, int rawKind) {
        return new ObservedOtherAgent(new Position(position), rawKind, 40);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), new Position(position), 1);
    }

    private static UdonSpot stocked(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }
}
