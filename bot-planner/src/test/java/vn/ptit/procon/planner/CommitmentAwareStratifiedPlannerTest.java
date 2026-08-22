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
 * M12 commitment-aware planning over the unchanged M11 stratified search.
 *
 * <p>Both modes run at exactly the same production budget ({@code 64/48/4}) with exactly the same
 * stage schedule ({@code 16/24/24}), on the same {@link DayState} and the same opponent forecast.
 * Only the forecast and evaluation semantics differ, which is the whole scope of M12.</p>
 *
 * <p>The 12x12 fixtures are shaped after live match m-3598, where five opponent collectors holding
 * up to three intent targets each drove the M10 forecast to remove twenty-nine projected collections
 * against a real loss of roughly fourteen. Server-realized totals are not available inside a
 * deterministic fixture, so nothing here asserts them; what is asserted is the structural direction
 * of the correction and the invariants of section 14.</p>
 */
class CommitmentAwareStratifiedPlannerTest {

    private static final AnytimePlannerConfig PRODUCTION = AnytimePlannerConfig.defaults();

    /** Zero expansions: the search returns exactly its own initial commitment-aware incumbent. */
    private static final AnytimePlannerConfig NO_SEARCH = new AnytimePlannerConfig(0, 48, 4);

    private static final StratifiedSearchConfig STAGES = StratifiedSearchConfig.defaults();

    /**
     * Section 38: 8x8 with three opponent collectors, one non-collector, three PATROL, one REFUEL,
     * eight stocked spots over four brands.
     */
    @Test
    void eightByEightThreeCollectorShapeKeepsTheSectionFourteenInvariants() {
        AnytimePlanResult result = commitmentAware(PRODUCTION).planWithStats(liveShapedState());
        CommitmentAwarePlanEvaluation evaluation = result.commitmentAwareEvaluation().orElseThrow();
        OpponentCommitmentForecast forecast = forecastFor(liveShapedState());

        assertEquals(3, forecast.collectionEligibleAgentCount(), "Three rawKind-zero collectors");
        assertEquals(4, forecast.observedAgentCount(), "Four observed agents, one of them rawKind one");
        assertEquals(8, forecast.stockedSpotCount());
        assertEquals(8, evaluation.base().udonTotal());
        assertEquals(4, evaluation.base().teamBrandCount());
        assertEquals(7, evaluation.oldForecastRealizableCollections());
        assertEquals(8, evaluation.commitmentRealizableCollections());
        assertTrue(evaluation.oldForecastRealizableCollections()
                        <= evaluation.commitmentRealizableCollections(),
                "oldForecastRealizable <= commitmentRealizable");
        assertTrue(evaluation.commitmentRealizableCollections() <= evaluation.base().udonTotal(),
                "commitmentRealizable <= raw");
        assertTrue(evaluation.commitmentRealizableBrandCount() <= evaluation.localTeamBrandCount());
        assertEquals(0, forecast.observedNowClaims(),
                "No opponent stands on a stocked spot here, so nothing may be hard claimed");
        assertEquals(0, evaluation.hardClaimedFirstCollections());
    }

    /**
     * Section 39: the critical regression. Twelve-by-twelve, five opponent collectors and one
     * rawKind-one non-collector, twelve stocked spots over four brands, two collectors already
     * standing on stock so the fixture is not degenerately claim-free.
     */
    @Test
    void twelveByTwelveFiveCollectorScaleShapeRestoresCollectionsTheOldModelErased() {
        DayState state = fiveCollectorState();
        AnytimePlanResult result = commitmentAware(PRODUCTION).planWithStats(state);
        CommitmentAwarePlanEvaluation evaluation = result.commitmentAwareEvaluation().orElseThrow();
        OpponentCommitmentForecast forecast = forecastFor(state);

        assertEquals(6, forecast.observedAgentCount());
        assertEquals(5, forecast.collectionEligibleAgentCount());
        assertEquals(12, forecast.stockedSpotCount());
        assertEquals(11, forecast.forecastClaims(), "Five collectors, up to three targets each");
        assertEquals(2, forecast.observedNowClaims());
        assertEquals(5, forecast.directIntentClaims());
        assertEquals(4, forecast.followOnIntentClaims());
        assertEquals(2, forecast.hardConsumedPortions(),
                "Only the two standing opponents may delete forecast stock");

        assertEquals(12, evaluation.base().udonTotal());
        assertEquals(5, evaluation.oldForecastRealizableCollections());
        assertEquals(10, evaluation.commitmentRealizableCollections());
        assertTrue(evaluation.oldForecastRealizableCollections()
                        < evaluation.commitmentRealizableCollections(),
                "oldForecastRealizable < commitmentRealizable");
        assertEquals(5, evaluation.commitmentRealizableCollections()
                        - evaluation.oldForecastRealizableCollections(),
                "Five of twelve projected collections were being erased by hypothetical claims");
        assertEquals(2, evaluation.hardClaimedFirstCollections(),
                "The two genuinely lost portions are still lost");
        assertEquals(4, evaluation.commitmentRealizableBrandCount());
        assertTrue(evaluation.commitmentRealizableCollections() <= evaluation.base().udonTotal());
    }

    /**
     * Section 40, the most important M12 scaling invariant: adding two more opponent collectors that
     * can only produce FUTURE claims must not reduce the commitment-realizable count.
     */
    @Test
    void extraCollectorsProducingOnlyFutureClaimsDoNotReduceRealizableCollections() {
        DayState three = threeCollectorState();
        DayState five = threeCollectorsPlusTwoDistantState();
        OpponentCommitmentForecast threeForecast = forecastFor(three);
        OpponentCommitmentForecast fiveForecast = forecastFor(five);

        CommitmentAwarePlanEvaluation withThree = commitmentAware(PRODUCTION)
                .planWithStats(three).commitmentAwareEvaluation().orElseThrow();
        CommitmentAwarePlanEvaluation withFive = commitmentAware(PRODUCTION)
                .planWithStats(five).commitmentAwareEvaluation().orElseThrow();

        assertEquals(3, threeForecast.collectionEligibleAgentCount());
        assertEquals(5, fiveForecast.collectionEligibleAgentCount());
        assertEquals(threeForecast.observedNowClaims(), fiveForecast.observedNowClaims(),
                "The fixture only holds if the two extra collectors added no observed claim");
        assertTrue(fiveForecast.forecastClaims() > threeForecast.forecastClaims(),
                "The two extra collectors must genuinely add hypothetical claims");
        assertEquals(threeForecast.hardConsumedPortions(), fiveForecast.hardConsumedPortions());

        assertTrue(withFive.commitmentRealizableCollections()
                        >= withThree.commitmentRealizableCollections(),
                "Extra hypothetical future claims must never erase commitment-realizable collections");
        assertEquals(10, withThree.commitmentRealizableCollections());
        assertEquals(10, withFive.commitmentRealizableCollections());
        assertEquals(withThree.commitmentRealizableBrandCount(),
                withFive.commitmentRealizableBrandCount());

        // The same two extra collectors do lower the M10 count, which is the field error itself.
        CommitmentAwarePlanEvaluation stratifiedThree = withThree;
        assertTrue(stratifiedThree.oldForecastRealizableCollections()
                        > withFive.oldForecastRealizableCollections(),
                "The old model loses a collection purely from added hypothetical claims");
    }

    /**
     * Section 41: adding opponents that are actually standing on stocked spots may correctly reduce
     * the commitment-realizable count. M12 is a structural correction, not blanket optimism.
     */
    @Test
    void extraObservedClaimersCorrectlyReduceRealizableCollections() {
        DayState three = threeCollectorState();
        DayState observed = threeCollectorsPlusTwoStandingState();
        OpponentCommitmentForecast threeForecast = forecastFor(three);
        OpponentCommitmentForecast observedForecast = forecastFor(observed);

        CommitmentAwarePlanEvaluation withThree = commitmentAware(PRODUCTION)
                .planWithStats(three).commitmentAwareEvaluation().orElseThrow();
        CommitmentAwarePlanEvaluation withObserved = commitmentAware(PRODUCTION)
                .planWithStats(observed).commitmentAwareEvaluation().orElseThrow();

        assertEquals(2, threeForecast.observedNowClaims());
        assertEquals(4, observedForecast.observedNowClaims(),
                "The two extra collectors stand directly on stocked spots");
        assertEquals(4, observedForecast.hardConsumedPortions());
        assertTrue(withObserved.commitmentRealizableCollections()
                        < withThree.commitmentRealizableCollections(),
                "Observed claimers are hard evidence and must still delete forecast stock");
        assertEquals(8, withObserved.commitmentRealizableCollections());
        assertEquals(4, withObserved.hardClaimedFirstCollections());
        assertTrue(withObserved.oldForecastRealizableCollections()
                <= withObserved.commitmentRealizableCollections());
    }

    /**
     * Section 42: a complete-plan A/B decided only by {@link
     * CommitmentAwarePlanEvaluation#preference()}. Plan A collects more raw portions but runs into
     * hard-claimed spots; Plan B collects fewer and stays clean.
     */
    @Test
    void completePlanPreferenceRanksCleanCoverageOverRawVolume() {
        PlanEvaluation planABase = new PlanEvaluation(4, 9, 3, 40, 30, "plan-a");
        PlanEvaluation planBBase = new PlanEvaluation(4, 7, 3, 40, 30, "plan-b");
        CommitmentAwarePlanEvaluation planA = new CommitmentAwarePlanEvaluation(
                planABase, new CommitmentAdjustedCollectionScore(24), 3, 6, 3, 3, 2, 1, 0, 3);
        CommitmentAwarePlanEvaluation planB = new CommitmentAwarePlanEvaluation(
                planBBase, new CommitmentAdjustedCollectionScore(28), 4, 7, 5, 0, 1, 1, 0, 5);

        assertTrue(planB.betterThan(planA),
                "More commitment-realizable brands is the primary key, above raw volume");
        assertFalse(planA.betterThan(planB));
        assertTrue(planA.base().udonTotal() > planB.base().udonTotal(),
                "Plan A really does win on raw simulator collections");
        assertEquals(planB, List.of(planA, planB).stream()
                .min(CommitmentAwarePlanEvaluation.preference()).orElseThrow());

        // With brand coverage equal, the adjusted score decides before raw volume does.
        CommitmentAwarePlanEvaluation equalBrands = new CommitmentAwarePlanEvaluation(
                planABase, new CommitmentAdjustedCollectionScore(24), 4, 6, 3, 3, 2, 1, 0, 3);
        assertTrue(planB.betterThan(equalBrands));
        assertTrue(planB.adjustedCollectionScore().value()
                > equalBrands.adjustedCollectionScore().value());
        assertTrue(equalBrands.base().udonTotal() > planB.base().udonTotal());
    }

    /**
     * Section 19: with score, brands and realizable count all equal, the plan carrying the weaker
     * (follow-on) risk wins, because a direct intention is structurally stronger evidence.
     */
    @Test
    void directIntentRiskLosesToFollowOnRiskInTheTieBreak() {
        PlanEvaluation base = new PlanEvaluation(4, 8, 3, 40, 30, "same");
        CommitmentAwarePlanEvaluation direct = new CommitmentAwarePlanEvaluation(
                base, new CommitmentAdjustedCollectionScore(26), 4, 8, 4, 0, 2, 0, 1, 5);
        CommitmentAwarePlanEvaluation followOn = new CommitmentAwarePlanEvaluation(
                base, new CommitmentAdjustedCollectionScore(26), 4, 8, 4, 0, 1, 1, 1, 5);

        assertTrue(followOn.betterThan(direct));
        assertFalse(direct.betterThan(followOn));
    }

    /**
     * Section 43: one same-search A/B report. Both modes run the identical M11 architecture at the
     * identical budget; only the objective differs. Section 44 forbids asserting that M12 must win.
     */
    @Test
    void sameSearchAgainstOldModeReportsBothObjectivesAtTheIdenticalBudget() {
        DayState state = fiveCollectorState();
        AnytimePlanResult oldMode = stratifiedIntentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult newMode = commitmentAware(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation m10 = oldMode.intentAwareEvaluation().orElseThrow();
        CommitmentAwarePlanEvaluation m12 = newMode.commitmentAwareEvaluation().orElseThrow();
        StratifiedSearchStats oldDepth = oldMode.stratifiedSearchStats().orElseThrow();
        StratifiedSearchStats newDepth = newMode.stratifiedSearchStats().orElseThrow();

        // Raw simulator volume and local brand coverage.
        assertEquals(12, m10.base().udonTotal());
        assertEquals(12, m12.base().udonTotal());
        assertEquals(4, m10.base().teamBrandCount());
        assertEquals(4, m12.base().teamBrandCount());
        // Old-mode objective.
        assertEquals(4, m10.forecastRealizableBrandCount());
        assertEquals(5, m10.forecastRealizableCollections());
        assertEquals(18, m10.adjustedCollectionScore().value());
        // New-mode objective on the same forecast.
        assertEquals(4, m12.commitmentRealizableBrandCount());
        assertEquals(10, m12.commitmentRealizableCollections());
        assertEquals(29, m12.adjustedCollectionScore().value());
        assertEquals(m10.forecastRealizableCollections(), m12.oldForecastRealizableCollections(),
                "Both modes agree on what the old model would have kept for their own best plan");
        // Identical search work in both modes.
        assertEquals(PRODUCTION.maxExpandedStates(), oldMode.stats().expandedStates());
        assertEquals(PRODUCTION.maxExpandedStates(), newMode.stats().expandedStates());
        assertEquals(8, oldDepth.strategiesQualified());
        assertEquals(8, newDepth.strategiesQualified());
        assertEquals(13, newDepth.strategiesWithAtLeast2Expansions());
        assertEquals(10, newDepth.maxStrategyExpansionCount());
        assertEquals(oldDepth.discoveryExpansions(), newDepth.discoveryExpansions());
        assertEquals(oldDepth.qualificationExpansions(), newDepth.qualificationExpansions());
        assertEquals(oldDepth.exploitationExpansions(), newDepth.exploitationExpansions());
    }

    /** Section 37: the shipped M11 mode is untouched by the presence of the new mode. */
    @Test
    void oldModeObjectiveAndReportingAreUnchanged() {
        DayState state = liveShapedState();
        AnytimePlanResult oldMode = stratifiedIntentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult newMode = commitmentAware(PRODUCTION).planWithStats(state);

        assertEquals(objectiveOf(state, oldMode.plan(), oldMode.evaluation()),
                oldMode.intentAwareEvaluation().orElseThrow(),
                "The M11 mode still reports exactly the recomputed M10 objective");
        assertTrue(oldMode.commitmentAwareEvaluation().isEmpty(),
                "The M11 mode must not acquire an M12 evaluation");
        assertTrue(newMode.intentAwareEvaluation().isEmpty(),
                "The M12 mode must not impersonate the M10 objective");
        assertTrue(newMode.commitmentAwareEvaluation().isPresent());
        // The M10 comparator itself is untouched: its eleven keys still order as before.
        IntentAwarePlanEvaluation better = new IntentAwarePlanEvaluation(
                new PlanEvaluation(4, 7, 3, 40, 30, "a"),
                new IntentAdjustedCollectionScore(24), 4, 7, 0, 0, 7);
        IntentAwarePlanEvaluation worse = new IntentAwarePlanEvaluation(
                new PlanEvaluation(4, 9, 3, 40, 30, "b"),
                new IntentAdjustedCollectionScore(24), 3, 7, 2, 0, 7);
        assertTrue(better.betterThan(worse));
    }

    /** Section 22: the initial incumbent passes through the identical commitment pipeline. */
    @Test
    void initialIncumbentIsEvaluatedByTheSameCommitmentPipeline() {
        DayState state = fiveCollectorState();
        AnytimePlanResult zeroSearch = commitmentAware(NO_SEARCH).planWithStats(state);
        CommitmentAwarePlanEvaluation incumbent =
                zeroSearch.commitmentAwareEvaluation().orElseThrow();

        assertEquals(0, zeroSearch.stats().expandedStates());
        assertEquals(0, zeroSearch.stats().incumbentImprovements());
        assertEquals(incumbent.base(), zeroSearch.evaluation());
        assertEquals(recomputedObjective(state, zeroSearch.plan(), zeroSearch.evaluation()),
                incumbent,
                "Every field of the incumbent evaluation is reproducible from the public pipeline");
        assertTrue(incumbent.oldForecastRealizableCollections()
                <= incumbent.commitmentRealizableCollections());
    }

    /** Section 50: the M12 mode inherits the M11 bounds exactly, with no budget increase. */
    @Test
    void searchStaysWithinTheUnchangedM11Bounds() {
        for (DayState state : List.of(
                liveShapedState(), fiveCollectorState(), threeCollectorState(),
                threeCollectorsPlusTwoDistantState(), threeCollectorsPlusTwoStandingState())) {
            AnytimePlanResult result = commitmentAware(PRODUCTION).planWithStats(state);
            StratifiedSearchStats depth = result.stratifiedSearchStats().orElseThrow();

            assertTrue(result.stats().expandedStates() <= 64, "expanded <= 64");
            assertTrue(depth.frontierPeak() <= 48, "frontier <= 48");
            assertTrue(depth.strategiesQualified() <= 8, "qualified strategies <= 8");
            assertEquals(depth.discoveryExpansions() + depth.qualificationExpansions()
                            + depth.exploitationExpansions(), depth.totalExpansions(),
                    "discovery + qualification + exploitation == expanded");
            assertEquals(result.stats().expandedStates(), depth.totalExpansions());
            assertEquals(16, depth.discoveryExpansions());
            assertEquals(24, depth.qualificationExpansions());
            assertEquals(24, depth.exploitationExpansions());
            assertTrue(result.stats().candidateRetained()
                            <= result.stats().expandedStates() * PRODUCTION.topCandidatesPerState(),
                    "candidates per state <= 4");
        }
    }

    /** Section 51: identical input yields an identical plan, evaluation, stats and annotation. */
    @Test
    void repeatedPlanningIsBitForBitDeterministic() {
        DayState state = fiveCollectorState();
        AnytimePlanResult first = commitmentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult second = commitmentAware(PRODUCTION).planWithStats(fiveCollectorState());

        assertEquals(actionsOf(first.plan()), actionsOf(second.plan()));
        assertEquals(first.evaluation(), second.evaluation());
        assertEquals(first.commitmentAwareEvaluation(), second.commitmentAwareEvaluation());
        assertEquals(first.stats(), second.stats());
        assertEquals(first.stratifiedSearchStats(), second.stratifiedSearchStats());
        assertEquals(forecastFor(state), forecastFor(fiveCollectorState()),
                "The commitment annotation itself must be reproducible");
    }

    /** Section 45 to 49: one bounded summary per day, with the calibration numbers present. */
    @Test
    void diagnosticsCarryTheCommitmentSummaryAndStayBounded() {
        DayState state = fiveCollectorState();
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new CommitmentAwareStratifiedPlanner(
                    PRODUCTION,
                    OpponentIntentConfig.defaults(),
                    IntentAdjustmentWeights.defaults(),
                    CommitmentAdjustmentWeights.defaults(),
                    STAGES,
                    true).planWithStats(state);
        } finally {
            System.setOut(original);
        }
        String logs = captured.toString(StandardCharsets.UTF_8);

        // Section 45.
        assertEquals(1, count(logs, "OPPONENT_COMMITMENT_SUMMARY "), "One summary per day");
        String summary = line(logs, "OPPONENT_COMMITMENT_SUMMARY ");
        for (String field : List.of(
                "day=0", "observedAgents=6", "collectionEligibleAgents=5", "forecastClaims=11",
                "observedNowClaims=2", "directIntentClaims=5", "followOnIntentClaims=4",
                "hardConsumedPortions=2", "stockedSpots=12")) {
            assertTrue(summary.contains(field), "The commitment summary must report " + field);
        }
        // Section 46.
        assertEquals(1, count(logs, "ANYTIME_STRATIFIED_COMMITMENT_START "));
        String start = line(logs, "ANYTIME_STRATIFIED_COMMITMENT_START ");
        for (String field : List.of(
                "day=0", "incumbentLocalBrands=", "incumbentCommitmentBrands=", "incumbentRawUdon=",
                "incumbentCommitmentRealizable=", "incumbentCommitmentScore=",
                "oldForecastRealizable=", "budget=64", "discoveryBudget=16",
                "qualificationBudget=24", "exploitationBudget=24")) {
            assertTrue(start.contains(field), "The START event must report " + field);
        }
        // Sections 47 and 48: raw minus either realizable count is computable from this line alone.
        assertEquals(1, count(logs, "ANYTIME_STRATIFIED_COMMITMENT_DONE "));
        String done = line(logs, "ANYTIME_STRATIFIED_COMMITMENT_DONE ");
        for (String field : List.of(
                "day=0", "localBrands=", "commitmentBrands=", "rawUdon=12",
                "commitmentRealizableCollections=10", "commitmentAdjustedScore=",
                "oldForecastRealizableCollections=5", "hardClaimedFirst=", "directIntentBefore=",
                "followOnIntentBefore=", "tieCollections=", "expanded=64", "completedPlans=",
                "improvements=", "strategiesDiscovered=", "strategiesQualified=",
                "strategiesWithAtLeast2Expansions=", "strategiesWithAtLeast3Expansions=",
                "maxStrategyExpansionCount=", "discoveryExpansions=16", "qualificationExpansions=24",
                "exploitationExpansions=24", "frontierPeak=", "budgetExhausted=")) {
            assertTrue(done.contains(field), "The DONE event must report " + field);
        }
        // Section 49.
        assertTrue(count(logs, "STRATEGY_DEPTH_SUMMARY ") <= 8,
                "Per-strategy diagnostics stay bounded to the eight qualified strategies");
        assertFalse(logs.contains("OPPONENT_COMMITMENT_CLAIM"), "No per-claim logging");
        assertFalse(logs.contains("COMMITMENT_ATTRIBUTION"), "No per-attribution logging");
        assertFalse(logs.contains("MOVE_"), "Diagnostics must never dump actions");
        assertFalse(logs.contains("frontierState"), "Diagnostics must never dump the frontier");
        assertFalse(logs.contains("ANYTIME_STRATIFIED_INTENT_DONE"),
                "The new mode must not impersonate the M11 search event");
        assertFalse(logs.contains("ANYTIME_INTENT_AWARE_DONE"),
                "The new mode must not impersonate the M10 search event");
    }

    /** Section 3: no probability, no randomness and no cross-day state anywhere in the model. */
    @Test
    void theCommitmentModelIsPurelyStructural() {
        assertEquals(3, OpponentClaimCommitment.values().length,
                "Three discrete commitment classes, not a probability");
        assertEquals(6, CommitmentCollectionClassification.values().length,
                "Six attribution classes and no enum explosion");
        CommitmentAdjustmentWeights weights = CommitmentAdjustmentWeights.defaults();
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_LIKELY_AVAILABLE_WEIGHT,
                weights.likelyAvailableWeight());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_UNFORECASTED_WEIGHT,
                weights.unforecastedWeight());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_FOLLOW_ON_INTENT_BEFORE_WEIGHT,
                weights.followOnIntentBeforeWeight());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT,
                weights.directIntentBeforeWeight());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_CONTESTED_TIE_WEIGHT,
                weights.contestedTieWeight());
        assertEquals(CommitmentAdjustmentWeights.DEFAULT_HARD_CLAIMED_FIRST_WEIGHT,
                weights.hardClaimedFirstWeight());
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

    /** Recomputes the M12 objective from the public evaluation pipeline. */
    private static CommitmentAwarePlanEvaluation recomputedObjective(
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
    private static IntentAwarePlanEvaluation objectiveOf(
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

    /** Live-shaped 1v1 8x8 day with eight stocked spots over four brands. */
    private static DayState liveShapedState() {
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
                List.of(other(11, 0), other(12, 0), other(50, 0), other(19, 1)));
    }

    /**
     * The m-3598 shape: five opponent collectors and one rawKind-one non-collector, two of the
     * collectors already standing on stocked spots so both hard and soft claims are present.
     */
    private static DayState fiveCollectorState() {
        return twelveByTwelve(List.of(
                other(31, 0), other(52, 0), other(120, 0), other(131, 0), other(2, 0),
                other(74, 1)));
    }

    /** The same day with only the first three collectors observed. */
    private static DayState threeCollectorState() {
        return twelveByTwelve(List.of(
                other(31, 0), other(52, 0), other(120, 0), other(74, 1)));
    }

    /** Three collectors plus two more too far from any spot to produce an observed claim. */
    private static DayState threeCollectorsPlusTwoDistantState() {
        return twelveByTwelve(List.of(
                other(31, 0), other(52, 0), other(120, 0), other(74, 1),
                other(131, 0), other(2, 0)));
    }

    /** Three collectors plus two more standing directly on stocked spots. */
    private static DayState threeCollectorsPlusTwoStandingState() {
        return twelveByTwelve(List.of(
                other(31, 0), other(52, 0), other(120, 0), other(74, 1),
                other(37, 0), other(85, 0)));
    }

    private static DayState twelveByTwelve(List<ObservedOtherAgent> others) {
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
                List.of(spot("A", 31), spot("B", 34), spot("C", 37), spot("D", 40),
                        spot("A", 52), spot("B", 55), spot("C", 58), spot("D", 85),
                        spot("A", 88), spot("B", 91), spot("C", 106), spot("D", 109)),
                others);
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
}
