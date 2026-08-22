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
 * M11 strategy-depth correction over the unchanged M10 corrected complete-plan objective.
 *
 * <p>Every comparison runs M10, M11 and the correction at exactly the same production budget
 * ({@code 64/48/4}) on the same {@link DayState} and the same opponent forecast, and the winner is
 * always decided by {@link IntentAwarePlanEvaluation#preference()} alone. The live diagnostics that
 * motivated the correction were {@code expanded=64} with {@code maxStrategyExpansionCount=46}: many
 * openings touched once, one opening absorbing roughly seventy percent of the budget.</p>
 */
class StratifiedIntentAwareAnytimePlannerTest {

    private static final AnytimePlannerConfig PRODUCTION = AnytimePlannerConfig.defaults();

    /** Zero expansions: the search returns exactly its own initial M10 corrected incumbent. */
    private static final AnytimePlannerConfig NO_SEARCH = new AnytimePlannerConfig(0, 48, 4);

    private static final StratifiedSearchConfig STAGES = StratifiedSearchConfig.defaults();

    @Test
    void threeStagesSpendExactlyTheUnchangedExpansionBudget() {
        AnytimePlanResult result = corrected(PRODUCTION).planWithStats(dominantStrategyState());
        StratifiedSearchStats depth = result.stratifiedSearchStats().orElseThrow();

        assertEquals(PRODUCTION.maxExpandedStates(), result.stats().expandedStates());
        assertEquals(result.stats().expandedStates(), depth.totalExpansions(),
                "The three stage counters must sum to the expansions actually performed");
        assertEquals(16, depth.discoveryExpansions());
        assertEquals(24, depth.qualificationExpansions());
        assertEquals(24, depth.exploitationExpansions());
        assertTrue(depth.discoveryExpansions() <= STAGES.discoveryBudget());
        assertTrue(depth.qualificationExpansions() <= STAGES.qualificationBudget());
        assertTrue(depth.exploitationExpansions() <= STAGES.exploitationBudget());
        // Completing and evaluating a plan for every expanded state must not buy extra expansions.
        assertEquals(result.stats().expandedStates() + 1, result.stats().completedPlans(),
                "Only the initial incumbent is completed outside the expansion budget");
        assertInvariants(depth);
    }

    @Test
    void hiddenDeeperStrategyIsFoundOnlyByTheCorrectedSearchAtTheSameBudget() {
        DayState state = dominantStrategyState();

        AnytimePlanResult m10 = intentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11 = diverse(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11c = corrected(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation quality = m10.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation shallow = m11.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation deep = m11c.intentAwareEvaluation().orElseThrow();
        DiverseSearchStats oldDepth = m11.diverseSearchStats().orElseThrow();
        StratifiedSearchStats newDepth = m11c.stratifiedSearchStats().orElseThrow();

        assertEquals(PRODUCTION.maxExpandedStates(), m10.stats().expandedStates());
        assertEquals(PRODUCTION.maxExpandedStates(), m11.stats().expandedStates());
        assertEquals(PRODUCTION.maxExpandedStates(), m11c.stats().expandedStates());
        // Only the unchanged eleven-criterion M10 objective decides these three verdicts.
        assertTrue(IntentAwarePlanEvaluation.preference().compare(deep, shallow) < 0);
        assertTrue(deep.betterThan(shallow), "The correction must find what M11 walked past");
        assertTrue(deep.betterThan(quality));
        assertFalse(shallow.betterThan(deep));
        assertFalse(quality.betterThan(deep));
        assertTrue(quality.betterThan(shallow),
                "The pathology: shallow M11 diversity is worse than plain quality-first here");

        // M11 touched nineteen openings but only two of them twice, one of them forty-five times.
        assertEquals(19, oldDepth.uniqueStrategyKeysExpanded());
        assertEquals(2, oldDepth.strategiesWithAtLeast2Expansions());
        assertEquals(45, oldDepth.maxStrategyExpansionCount());
        // The correction spends the same sixty-four expansions on fewer, deeper openings.
        assertEquals(8, newDepth.strategiesWithAtLeast2Expansions());
        assertEquals(7, newDepth.strategiesWithAtLeast3Expansions());
        assertEquals(21, newDepth.maxStrategyExpansionCount());
        assertEquals(3, newDepth.medianStrategyExpansionCount());
        assertTrue(newDepth.strategiesWithAtLeast2Expansions()
                > oldDepth.strategiesWithAtLeast2Expansions());
        assertTrue(newDepth.maxStrategyExpansionCount() < oldDepth.maxStrategyExpansionCount());
        assertInvariants(newDepth);
    }

    @Test
    void shallowStrategyDiversityIsNotEnoughToBeatQualityFirst() {
        DayState state = shallowDiversityState();

        AnytimePlanResult m10 = intentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11 = diverse(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11c = corrected(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation quality = m10.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation shallow = m11.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation deep = m11c.intentAwareEvaluation().orElseThrow();
        DiverseSearchStats oldDepth = m11.diverseSearchStats().orElseThrow();
        StratifiedSearchStats newDepth = m11c.stratifiedSearchStats().orElseThrow();

        // High strategic breadth, almost no strategic depth: nineteen openings, two of them twice.
        assertEquals(19, oldDepth.uniqueStrategyKeysExpanded());
        assertEquals(2, oldDepth.strategiesWithAtLeast2Expansions());
        assertEquals(32, oldDepth.maxStrategyExpansionCount());
        assertTrue(quality.betterThan(shallow), "Breadth alone loses to plain quality-first");
        assertEquals(26, shallow.adjustedCollectionScore().value());

        assertEquals(11, newDepth.strategiesWithAtLeast2Expansions());
        assertEquals(8, newDepth.strategiesWithAtLeast3Expansions());
        assertTrue(newDepth.strategiesWithAtLeast2Expansions()
                > oldDepth.strategiesWithAtLeast2Expansions());
        assertTrue(deep.betterThan(shallow), "Real depth must recover a strictly better plan");
        assertEquals(30, deep.adjustedCollectionScore().value());
        assertFalse(quality.betterThan(deep), "And must never be worse than quality-first here");
        assertInvariants(newDepth);
    }

    /**
     * Live-shaped fixture: 8x8, eight stocked spots over four brands, three own PATROL agents plus
     * one REFUEL, one observed opponent group with three collection-eligible agents and one
     * raw-kind-one non-collector. Every number the M11 correction report quotes comes from this one
     * same-budget run, so the report never mixes two numeric scales.
     */
    @Test
    void liveShapedSameBudgetComparisonReportsTheSameQuantitiesForAllThreeModes() {
        DayState state = liveShapedState();
        OpponentIntentForecast forecast = new OpponentIntentForecaster().forecast(state);

        AnytimePlanResult m10 = intentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11 = diverse(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11c = corrected(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation quality = m10.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation shallow = m11.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation deep = m11c.intentAwareEvaluation().orElseThrow();
        DiverseSearchStats oldDepth = m11.diverseSearchStats().orElseThrow();
        StratifiedSearchStats newDepth = m11c.stratifiedSearchStats().orElseThrow();

        assertEquals(8, forecast.stockedSpotCount());
        assertEquals(4, forecast.observedAgentCount());
        assertEquals(3, forecast.collectionEligibleAgentCount());

        // Criteria one to six tie, so the deeper search wins on movement steps.
        assertEquals(4, quality.forecastRealizableBrandCount());
        assertEquals(4, shallow.forecastRealizableBrandCount());
        assertEquals(4, deep.forecastRealizableBrandCount());
        assertEquals(28, quality.adjustedCollectionScore().value());
        assertEquals(28, shallow.adjustedCollectionScore().value());
        assertEquals(28, deep.adjustedCollectionScore().value());
        assertEquals(7, quality.forecastRealizableCollections());
        assertEquals(7, shallow.forecastRealizableCollections());
        assertEquals(7, deep.forecastRealizableCollections());
        assertEquals(8, quality.base().udonTotal());
        assertEquals(8, shallow.base().udonTotal());
        assertEquals(8, deep.base().udonTotal());
        assertEquals(1, quality.likelyClaimedFirstCollections());
        assertEquals(1, shallow.likelyClaimedFirstCollections());
        assertEquals(1, deep.likelyClaimedFirstCollections());
        assertEquals(34, quality.base().movementSteps());
        assertEquals(34, shallow.base().movementSteps());
        assertEquals(32, deep.base().movementSteps());
        assertEquals(64, m10.stats().expandedStates());
        assertEquals(64, m11.stats().expandedStates());
        assertEquals(64, m11c.stats().expandedStates());
        assertEquals(65, m10.stats().completedPlans());
        assertEquals(65, m11.stats().completedPlans());
        assertEquals(65, m11c.stats().completedPlans());
        assertEquals(3, m10.stats().incumbentImprovements());
        assertEquals(2, m11.stats().incumbentImprovements());
        assertEquals(4, m11c.stats().incumbentImprovements());

        assertTrue(deep.betterThan(shallow), "The correction must return a strictly better plan");
        assertTrue(deep.betterThan(quality));
        assertFalse(shallow.betterThan(deep));

        assertEquals(17, oldDepth.uniqueStrategyKeysExpanded());
        assertEquals(5, oldDepth.strategiesWithAtLeast2Expansions());
        assertEquals(16, oldDepth.maxStrategyExpansionCount());
        assertEquals(25, newDepth.strategiesDiscovered());
        assertEquals(8, newDepth.strategiesQualified());
        assertEquals(13, newDepth.strategiesExpanded());
        assertEquals(12, newDepth.strategiesWithAtLeast2Expansions());
        assertEquals(12, newDepth.strategiesWithAtLeast3Expansions());
        assertEquals(12, newDepth.maxStrategyExpansionCount());
        assertEquals(4, newDepth.medianStrategyExpansionCount());
        assertEquals(8, newDepth.qualifiedStrategiesMeetingMinimumDepth());
        assertEquals(0, newDepth.qualifiedStrategiesExhaustedBeforeMinimum());
        assertTrue(newDepth.strategiesWithAtLeast2Expansions()
                        > oldDepth.strategiesWithAtLeast2Expansions(),
                "The live-shaped fixture must give strictly more openings real depth than M11");
        assertInvariants(newDepth);
    }

    /**
     * Fixture expectation, not a universal runtime invariant: while at least four qualified
     * strategies stay expandable through qualification, no single opening may own half the budget.
     */
    @Test
    void liveShapedFixtureKeepsTheDeepestStrategyUnderHalfTheBudget() {
        StratifiedSearchStats depth = corrected(PRODUCTION)
                .planWithStats(liveShapedState()).stratifiedSearchStats().orElseThrow();

        assertTrue(depth.qualifiedStrategiesMeetingMinimumDepth() >= 4,
                "Fixture precondition: at least four qualified strategies stayed expandable");
        assertTrue(depth.maxStrategyExpansionCount() <= 32,
                "Fixture target: the deepest strategy consumed "
                        + depth.maxStrategyExpansionCount() + " of 64 expansions");
    }

    @Test
    void correctedSearchIsNeverWorseThanItsOwnInitialIncumbent() {
        for (DayState state : List.of(
                dominantStrategyState(), shallowDiversityState(), liveShapedState())) {
            IntentAwarePlanEvaluation baseline = corrected(NO_SEARCH)
                    .planWithStats(state).intentAwareEvaluation().orElseThrow();
            AnytimePlanResult searched = corrected(PRODUCTION).planWithStats(state);
            IntentAwarePlanEvaluation deep = searched.intentAwareEvaluation().orElseThrow();

            assertFalse(baseline.betterThan(deep),
                    "The correction may never return a plan worse than its own M10 incumbent");
            assertTrue(deep.betterThan(baseline), "And it must improve on it on all three fixtures");
            assertTrue(searched.stats().incumbentImprovements() > 0);
        }
    }

    @Test
    void completedPlanObjectiveIsTheUnchangedM10Evaluator() {
        DayState state = dominantStrategyState();

        AnytimePlanResult m10 = intentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11c = corrected(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation quality = m10.intentAwareEvaluation().orElseThrow();
        IntentAwarePlanEvaluation deep = m11c.intentAwareEvaluation().orElseThrow();

        // Each mode's reported objective must equal the objective recomputed from the raw
        // production forecast pipeline, so one evaluator scores both already-completed plans.
        IntentAwarePlanEvaluation recomputedQuality = objectiveOf(state, m10.plan(), m10.evaluation());
        IntentAwarePlanEvaluation recomputedDeep = objectiveOf(state, m11c.plan(), m11c.evaluation());

        assertEquals(quality, recomputedQuality);
        assertEquals(deep, recomputedDeep);
        assertTrue(recomputedDeep.betterThan(recomputedQuality));
        assertFalse(recomputedQuality.betterThan(recomputedDeep));
        assertEquals(
                IntentAwarePlanEvaluation.preference().compare(deep, quality) < 0,
                IntentAwarePlanEvaluation.preference().compare(recomputedDeep, recomputedQuality) < 0);
    }

    @Test
    void qualificationStaysBoundedAndExhaustedStrategiesDoNotWasteBudget() {
        DayState state = deadEndState();
        AnytimePlanResult result = corrected(PRODUCTION).planWithStats(state);
        StratifiedSearchStats depth = result.stratifiedSearchStats().orElseThrow();
        IntentAwarePlanEvaluation shallow = diverse(PRODUCTION)
                .planWithStats(state).intentAwareEvaluation().orElseThrow();

        assertTrue(depth.strategiesDiscovered() > STAGES.maxQualifiedStrategies());
        assertEquals(STAGES.maxQualifiedStrategies(), depth.strategiesQualified());
        assertTrue(depth.qualifiedStrategiesExhaustedBeforeMinimum() > 0,
                "This fixture must actually exercise the exhausted-before-minimum path");
        assertEquals(depth.strategiesQualified(),
                depth.qualifiedStrategiesMeetingMinimumDepth()
                        + depth.qualifiedStrategiesExhaustedBeforeMinimum(),
                "Every qualified strategy either reached the minimum or ran out of states");
        // The unused obligations were reassigned: all three stages still spent their full share.
        assertEquals(PRODUCTION.maxExpandedStates(), result.stats().expandedStates());
        assertEquals(16, depth.discoveryExpansions());
        assertEquals(24, depth.qualificationExpansions());
        assertEquals(24, depth.exploitationExpansions());
        assertFalse(shallow.betterThan(result.intentAwareEvaluation().orElseThrow()),
                "Bounding qualification to the strongest subset must not lose to M11");
        assertInvariants(depth);
    }

    @Test
    void anEmptyFrontierEndsTheSearchWithoutFabricatingExpansions() {
        AnytimePlanResult result = corrected(PRODUCTION).planWithStats(exhaustibleState());
        StratifiedSearchStats depth = result.stratifiedSearchStats().orElseThrow();

        assertTrue(result.stats().expandedStates() < PRODUCTION.maxExpandedStates(),
                "This fixture must run out of states before the budget");
        assertFalse(result.stats().budgetExhausted());
        assertFalse(depth.budgetExhausted());
        assertEquals(result.stats().expandedStates(), depth.totalExpansions());
        assertEquals(0, depth.exploitationExpansions(),
                "The frontier emptied inside qualification, so exploitation never ran");
        assertTrue(depth.strategiesQualified() <= STAGES.maxQualifiedStrategies());
        assertInvariants(depth);
    }

    @Test
    void singleStrategicOpeningFallsBackToQualityFirstWithoutDelay() {
        DayState state = lineState(9, 8, List.of(spot("A", 1), spot("B", 4), spot("C", 7)));

        AnytimePlanResult m10 = intentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult m11c = corrected(PRODUCTION).planWithStats(state);
        StratifiedSearchStats depth = m11c.stratifiedSearchStats().orElseThrow();

        assertEquals(actionsOf(m10.plan()), actionsOf(m11c.plan()),
                "With no strategy to balance against, the correction is plain quality-first");
        assertEquals(m10.evaluation(), m11c.evaluation());
        assertEquals(m10.intentAwareEvaluation(), m11c.intentAwareEvaluation());
        assertEquals(m10.stats().expandedStates(), m11c.stats().expandedStates(),
                "No artificial delay: the same states are expanded as by quality-first");
        assertEquals(m11c.stats().expandedStates(), depth.discoveryExpansions());
        assertEquals(0, depth.qualificationExpansions());
        assertEquals(0, depth.exploitationExpansions());
        assertInvariants(depth);
    }

    @Test
    void searchStaysBoundedInStatesFrontierAndCandidatesPerState() {
        AnytimePlanResult result = corrected(PRODUCTION).planWithStats(liveShapedState());
        AnytimeSearchStats stats = result.stats();
        StratifiedSearchStats depth = result.stratifiedSearchStats().orElseThrow();

        assertTrue(stats.expandedStates() <= PRODUCTION.maxExpandedStates());
        assertTrue(depth.frontierPeak() <= PRODUCTION.maxFrontierSize());
        assertEquals(PRODUCTION.maxFrontierSize(), depth.frontierPeak(),
                "Frontier capacity must never be left unused to satisfy strategy balancing");
        assertTrue(stats.candidateRetained()
                <= PRODUCTION.topCandidatesPerState() * stats.expandedStates());
        assertTrue(depth.strategiesDiscovered() <= stats.generatedStates(),
                "Per-strategy metadata is bounded by the states the search generated");
        assertTrue(result.diverseSearchStats().isEmpty(),
                "The correction must not report M11 search state");
    }

    @Test
    void repeatedRunsProduceIdenticalPlansStatisticsAndDiagnostics() {
        DayState state = liveShapedState();

        AnytimePlanResult first = corrected(PRODUCTION).planWithStats(state);
        AnytimePlanResult second = corrected(PRODUCTION).planWithStats(state);

        assertEquals(actionsOf(first.plan()), actionsOf(second.plan()));
        assertEquals(first.evaluation(), second.evaluation());
        assertEquals(first.evaluation().deterministicSignature(),
                second.evaluation().deterministicSignature());
        assertEquals(first.intentAwareEvaluation(), second.intentAwareEvaluation());
        assertEquals(first.stats(), second.stats());
        assertEquals(first.stratifiedSearchStats(), second.stratifiedSearchStats());
    }

    @Test
    void diagnosticsExposeStageBudgetsAndPerStrategyDepth() {
        DayState state = liveShapedState();
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new StratifiedIntentAwareAnytimePlanner(
                    PRODUCTION,
                    OpponentIntentConfig.defaults(),
                    IntentAdjustmentWeights.defaults(),
                    STAGES,
                    true).planWithStats(state);
        } finally {
            System.setOut(original);
        }
        String logs = captured.toString(StandardCharsets.UTF_8);

        assertTrue(logs.contains("ANYTIME_STRATIFIED_INTENT_START day=0"));
        assertTrue(logs.contains("incumbentForecastBrands="));
        assertTrue(logs.contains("incumbentIntentScore="));
        assertTrue(logs.contains("incumbentForecastRealizable="));
        assertTrue(logs.contains("incumbentRawUdon="));
        assertTrue(logs.contains("budget=64"));
        assertTrue(logs.contains("discoveryBudget=16"));
        assertTrue(logs.contains("qualificationBudget=24"));
        assertTrue(logs.contains("exploitationBudget=24"));
        assertTrue(logs.contains("maxQualifiedStrategies=8"));
        assertTrue(logs.contains("minimumQualificationDepth=2"));

        assertTrue(logs.contains("ANYTIME_STRATIFIED_INTENT_DONE day=0"));
        for (String field : List.of(
                "forecastBrands=", "intentAdjustedScore=", "forecastRealizableCollections=",
                "rawUdon=", "likelyClaimedFirst=", "expanded=", "completedPlans=", "improvements=",
                "strategiesDiscovered=", "strategiesQualified=", "strategiesExpanded=",
                "strategiesWithAtLeast2Expansions=", "strategiesWithAtLeast3Expansions=",
                "maxStrategyExpansionCount=", "medianStrategyExpansionCount=",
                "qualifiedStrategiesMeetingMinimumDepth=",
                "qualifiedStrategiesExhaustedBeforeMinimum=", "discoveryExpansions=",
                "qualificationExpansions=", "exploitationExpansions=", "frontierPeak=",
                "budgetExhausted=")) {
            assertTrue(logs.contains(field), "The DONE event must report " + field);
        }

        List<String> summary = logs.lines()
                .filter(line -> line.startsWith("STRATEGY_DEPTH_SUMMARY "))
                .toList();
        assertFalse(summary.isEmpty());
        assertTrue(summary.size() <= 8, "Per-strategy diagnostics must stay bounded to eight rows");
        summary.forEach(row -> {
            assertTrue(row.contains("strategyKey="));
            assertTrue(row.contains("totalExpansions="));
            assertTrue(row.contains("qualificationExpansions="));
            assertTrue(row.contains("bestFrontierRankOrOrdinal="));
            assertTrue(row.contains("bestCompleteFound="));
        });
        assertEquals(1, logs.lines()
                .filter(line -> line.startsWith("ANYTIME_STRATIFIED_INTENT_DONE ")).count());
        assertFalse(logs.contains("ANYTIME_DIVERSE_INTENT_DONE"),
                "The correction must not impersonate the M11 search event");
        assertFalse(logs.contains("ANYTIME_INTENT_AWARE_DONE"),
                "The correction must not impersonate the M10 search event");
        assertFalse(logs.contains("SEARCH_DIVERSITY_SUMMARY"));
        assertFalse(logs.contains("MOVE_"), "Diagnostics must never dump actions");
        assertFalse(logs.contains("frontierState"), "Diagnostics must never dump the frontier");
    }

    /** Invariants the specification requires of every stratified run, on every fixture. */
    private static void assertInvariants(StratifiedSearchStats depth) {
        assertEquals(depth.discoveryExpansions() + depth.qualificationExpansions()
                + depth.exploitationExpansions(), depth.totalExpansions());
        assertTrue(depth.strategiesQualified() <= STAGES.maxQualifiedStrategies());
        assertTrue(depth.strategiesWithAtLeast3Expansions()
                <= depth.strategiesWithAtLeast2Expansions());
        assertTrue(depth.strategiesWithAtLeast2Expansions() <= depth.strategiesExpanded());
        assertTrue(depth.strategiesExpanded() <= depth.strategiesDiscovered());
        assertTrue(depth.maxStrategyExpansionCount() <= depth.totalExpansions());
        assertTrue(depth.medianStrategyExpansionCount() <= depth.maxStrategyExpansionCount());
        assertTrue(depth.qualifiedStrategiesMeetingMinimumDepth() <= depth.strategiesQualified());
        assertTrue(depth.qualifiedStrategiesExhaustedBeforeMinimum() <= depth.strategiesQualified());
        assertTrue(depth.frontierPeak() <= PRODUCTION.maxFrontierSize());
    }

    /** Structural view of a plan, since {@link TeamPlan} has reference identity semantics. */
    private static Map<Integer, List<String>> actionsOf(TeamPlan plan) {
        Map<Integer, List<String>> actions = new java.util.TreeMap<>();
        plan.actionsByAgent().forEach((agent, list) -> actions.put(
                agent.value(), list.stream().map(Object::toString).toList()));
        return actions;
    }

    /** Recomputes the M10 corrected intent layer from the raw production evaluation pipeline. */
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

    private static IntentAwareAnytimePlanner intentAware(AnytimePlannerConfig config) {
        return new IntentAwareAnytimePlanner(
                config, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults());
    }

    private static DiverseIntentAwareAnytimePlanner diverse(AnytimePlannerConfig config) {
        return new DiverseIntentAwareAnytimePlanner(
                config,
                OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(),
                DiverseSearchConfig.defaults(),
                false);
    }

    private static StratifiedIntentAwareAnytimePlanner corrected(AnytimePlannerConfig config) {
        return new StratifiedIntentAwareAnytimePlanner(
                config,
                OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()),
                false);
    }

    /**
     * One opening dominates the M10 frontier comparator here, exactly as in the live m-3540 day
     * where {@code maxStrategyExpansionCount=46}. A different opening only reveals a better
     * complete plan two or three expansions deep, so M11 never gets there.
     */
    private static DayState dominantStrategyState() {
        return gridState(18, List.of(
                spot("A", 6), spot("B", 12), spot("C", 18), spot("D", 24), spot("A", 30),
                spot("B", 36), spot("C", 42), spot("D", 48), spot("A", 54), spot("B", 60)));
    }

    /** Wide but shallow: M11 touches nineteen openings and still loses to plain quality-first. */
    private static DayState shallowDiversityState() {
        return gridState(16, List.of(
                spot("A", 4), spot("B", 9), spot("C", 14), spot("D", 19), spot("A", 24),
                spot("B", 29), spot("C", 34), spot("D", 39), spot("A", 44), spot("B", 49)));
    }

    /** Live-shaped 1v1 8x8 day with eight stocked spots over four brands. */
    private static DayState liveShapedState() {
        return gridState(19, List.of(
                spot("A", 7), spot("B", 15), spot("C", 23), spot("D", 31),
                spot("A", 39), spot("B", 47), spot("C", 55), spot("D", 63)));
    }

    /** Tight step budget: several qualified openings run out of states below minimum depth. */
    private static DayState deadEndState() {
        return gridState(12, List.of(
                spot("A", 4), spot("B", 17), spot("C", 30), spot("D", 43)));
    }

    /** Small enough that the frontier empties before the expansion budget is spent. */
    private static DayState exhaustibleState() {
        return gridState(14, List.of(spot("A", 4), spot("B", 17), spot("C", 30)));
    }

    private static DayState gridState(int budget, List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[64];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<AgentState> agents = new ArrayList<>();
        int[] patrolStarts = {27, 28, 36};
        for (int index = 0; index < patrolStarts.length; index++) {
            agents.add(AgentState.patrol(new AgentId(index), new Position(patrolStarts[index]), 30));
        }
        agents.add(AgentState.refuel(new AgentId(3), new Position(35)));
        List<ObservedOtherAgent> others = List.of(
                other(11, 0), other(12, 0), other(50, 0), other(19, 1));
        return state(new HexMap(8, 8, terrain), budget, agents, spots,
                List.of(new ObservedOtherGroup(5, others)));
    }

    private static DayState lineState(int width, int budget, List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        return state(
                new HexMap(width, 1, terrain),
                budget,
                List.of(AgentState.patrol(new AgentId(0), new Position(0), 30)),
                spots,
                List.of());
    }

    private static DayState state(
            HexMap map,
            int budget,
            List<AgentState> agents,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                map,
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new FuelCapacity(30),
                spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock, others);
    }

    private static ObservedOtherAgent other(int position, int rawKind) {
        return new ObservedOtherAgent(new Position(position), rawKind, 40);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), new Position(position), 1);
    }
}
