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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * M16 coupled competitive margin: terminal key order, mode isolation and bounded diagnostics.
 *
 * <p>Nothing here fits a coefficient. The margin is the plain unweighted difference between the
 * collections our fixed routes really realize on the shared coupled timeline and the collections the
 * adaptive opponent realizes on that same timeline.</p>
 */
class M16CoupledCompetitiveMarginTest {

    private static final AgentId OWN = new AgentId(0);
    private static final BrandId BRAND = new BrandId("A");

    /**
     * Brand safety outranks every margin trade, and the guard is deliberately extreme.
     *
     * <p>A plan that really realizes four coupled brands on a margin of minus ten still beats a
     * three-brand plan on plus twenty, because a lost daily brand costs a whole daily bonus that no
     * margin can buy back.</p>
     */
    @Test
    void aFourBrandCoupledPlanBeatsAThreeBrandPlanWhateverTheMarginSays() {
        CoupledCompetitiveMarginEvaluation fourBrands = evaluation(4, 4, 14, 60, "b");
        CoupledCompetitiveMarginEvaluation threeBrands = evaluation(3, 23, 3, 65, "a");

        assertEquals(-10, fourBrands.projectedCoupledMargin());
        assertEquals(20, threeBrands.projectedCoupledMargin());
        assertTrue(fourBrands.betterThan(threeBrands));
        assertFalse(threeBrands.betterThan(fourBrands));
    }

    @Test
    void theCoupledMarginIsCoupledOwnCollectionsMinusCoupledOpponentCollections() {
        CoupledCompetitiveMarginEvaluation evaluation = evaluation(4, 20, 7, 65, "a");

        assertEquals(20, evaluation.coupledOwnCollections());
        assertEquals(7, evaluation.coupledOpponentCollections());
        assertEquals(13, evaluation.projectedCoupledMargin());
    }

    /** A genuine trade: one fewer own collection buying three fewer opponent collections wins. */
    @Test
    void aBetterCoupledMarginBeatsOneMoreCoupledOwnCollection() {
        CoupledCompetitiveMarginEvaluation ownTwenty = evaluation(4, 20, 12, 65, "a");
        CoupledCompetitiveMarginEvaluation ownNineteen = evaluation(4, 19, 9, 65, "b");

        assertEquals(8, ownTwenty.projectedCoupledMargin());
        assertEquals(10, ownNineteen.projectedCoupledMargin());
        assertTrue(ownNineteen.betterThan(ownTwenty));
        assertFalse(ownTwenty.betterThan(ownNineteen));
    }

    /**
     * At an equal margin the plan that really collects more is the conservative choice.
     *
     * <p>Our own coupled collections sit on fixed routes we control; opponent denial rests on a forecast
     * of an adaptive opponent. Preferring the larger own count at an identical margin therefore leans on
     * the more reliable half of the difference.</p>
     */
    @Test
    void atAnEqualMarginTheLargerOwnCollectionCountWins() {
        CoupledCompetitiveMarginEvaluation ownTwenty = evaluation(4, 20, 10, 65, "a");
        CoupledCompetitiveMarginEvaluation ownNineteen = evaluation(4, 19, 9, 65, "b");

        assertEquals(ownTwenty.projectedCoupledMargin(), ownNineteen.projectedCoupledMargin());
        assertTrue(ownTwenty.betterThan(ownNineteen));
        assertFalse(ownNineteen.betterThan(ownTwenty));
    }

    @Test
    void theUnchangedM131HorizonKeysStillDecideWhenBrandsMarginAndOwnCountsTie() {
        CoupledCompetitiveMarginEvaluation poor = evaluation(4, 20, 10, 65, "a", 3);
        CoupledCompetitiveMarginEvaluation rich = evaluation(4, 20, 10, 65, "b", 4);

        assertTrue(rich.betterThan(poor));
        assertFalse(poor.betterThan(rich));
    }

    @Test
    void theHorizonKeysAreSkippedOnTheFinalDayAndTheSignatureSettlesTheTie() {
        CoupledCompetitiveMarginEvaluation first = finalDayEvaluation(4, 20, 10, 65, "a");
        CoupledCompetitiveMarginEvaluation second = finalDayEvaluation(4, 20, 10, 65, "b");

        assertEquals(0, first.nextDayHarvestCapacity().remainingFutureDays());
        assertEquals(first.projectedCoupledMargin(), second.projectedCoupledMargin());
        assertTrue(first.betterThan(second), "The deterministic signature must settle a full tie");
        assertFalse(second.betterThan(first));
    }

    @Test
    void theM121SemiScoreSurvivesOnlyAsALaterRiskKey() {
        CoupledCompetitiveMarginEvaluation lowRisk = evaluation(4, 20, 10, 65, "b");
        CoupledCompetitiveMarginEvaluation highRisk = evaluation(4, 20, 10, 60, "a");

        assertEquals(65, lowRisk.semiScore());
        assertEquals(60, highRisk.semiScore());
        assertTrue(lowRisk.betterThan(highRisk),
                "M12.1 must not be injected as guaranteed stock removal, only ranked at key 7");
    }

    @Test
    void everyMandatedCoupledDiagnosticIsExposedOnTheEvaluation() {
        CoupledCompetitiveMarginEvaluation evaluation =
                build(4, 20, 3, 12, 14, 2, 65, "a", capacity(1, 4));

        assertEquals(23, evaluation.plannedOwnOpportunityEvents());
        assertEquals(4, evaluation.coupledOwnBrands());
        assertEquals(20, evaluation.coupledOwnCollections());
        assertEquals(3, evaluation.ownPlannedEventsInvalidatedByOpponent());
        assertEquals(14, evaluation.opponentBaselineCollections());
        assertEquals(12, evaluation.coupledOpponentCollections());
        assertEquals(2, evaluation.opponentCollectionsRemovedVsBaseline());
        assertEquals(2, evaluation.opponentReplacementCollections());
        assertEquals(8, evaluation.projectedCoupledMargin());
    }

    @Test
    void handingTheOpponentACheaperRouteShowsUpAsANegativeRemovedCount() {
        CoupledCompetitiveMarginEvaluation worse =
                build(4, 20, 0, 3, 1, 2, 65, "a", capacity(1, 4));

        assertEquals(1, worse.opponentBaselineCollections());
        assertEquals(3, worse.coupledOpponentCollections());
        assertEquals(-2, worse.opponentCollectionsRemovedVsBaseline());
        assertEquals(17, worse.projectedCoupledMargin());
    }

    @Test
    void theModeIsIsolatedAndEmitsItsOwnBoundedDiagnostics() {
        DayState state = contestedState();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        AnytimePlanResult result;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            result = planner(0, true).planWithStats(state);
        } finally {
            System.setOut(original);
        }
        String logs = output.toString(StandardCharsets.UTF_8);

        assertTrue(result.coupledCompetitiveEvaluation().isPresent());
        assertTrue(result.replacementAwareEvaluation().isEmpty());
        assertTrue(result.relativeMarginEvaluation().isEmpty());
        assertTrue(result.harvestHorizonAwareEvaluation().isEmpty());
        assertTrue(result.horizonAwareEvaluation().isEmpty());
        assertTrue(result.semiCommitmentAwareEvaluation().isEmpty());
        assertTrue(result.commitmentAwareEvaluation().isEmpty());
        assertTrue(logs.contains("OPPONENT_COUPLED_BASELINE"));
        assertTrue(logs.contains("COUPLED_COMPETITIVE_EVENT"));
        assertTrue(logs.contains("ANYTIME_STRATIFIED_COUPLED_MARGIN_START"));
        assertTrue(logs.contains("ANYTIME_STRATIFIED_COUPLED_MARGIN_DONE"));
        assertFalse(logs.contains("OPPONENT_FULL_DAY_BASELINE"),
                "M16 must not reuse the M15 plan-conditioned baseline diagnostics");
        assertFalse(logs.contains("OPPONENT_DENIAL_BASELINE"));
    }

    @Test
    void theDoneLineCarriesEveryMandatedCoupledAndSearchMetric() {
        DayState state = contestedState();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            planner(64, false).planWithStats(state);
        } finally {
            System.setOut(original);
        }
        String done = output.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("ANYTIME_STRATIFIED_COUPLED_MARGIN_DONE"))
                .findFirst().orElseThrow();

        for (String key : List.of("day=", "remainingFutureDays=", "plannedOwnOpportunityEvents=",
                "coupledOwnBrands=", "coupledOwnCollections=", "opponentBaselineCollections=",
                "coupledOpponentCollections=", "opponentCollectionsRemovedVsBaseline=",
                "ownPlannedEventsInvalidatedByOpponent=", "opponentReplacementCollections=",
                "projectedCoupledMargin=", "semiScore=", "equalStepContests=",
                "minimumPatrolDistinctSpots=", "totalPatrolDistinctSpotCapacity=",
                "totalPatrolDistinctBrandCapacity=", "baselineRolloutEvents=",
                "coupledRolloutEvents=", "maxCoupledCollectorCollections=",
                "routeCostCacheEntries=", "pathfindingExecutions=", "expanded=",
                "strategiesQualified=", "frontierPeak=", "budgetExhausted=")) {
            assertTrue(done.contains(key), "The DONE line must report " + key);
        }
    }

    @Test
    void theCoupledBaselineIsComputedOncePerPlanningRunAndNeverPerCandidate() {
        DayState state = contestedState();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        AnytimePlanResult result;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            result = planner(64, true).planWithStats(state);
        } finally {
            System.setOut(original);
        }
        long baselineLines = output.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("OPPONENT_COUPLED_BASELINE")).count();

        assertEquals(1, baselineLines);
        assertEquals(state.matchData().udonSpots().size(),
                result.coupledCompetitiveEvaluation().orElseThrow().coupled().baseline()
                        .pathfindingExecutions());
    }

    /**
     * Zero terminal Dijkstra: raising the budget adds terminal plan evaluations, not pathfinding.
     *
     * <p>The reverse-Pareto cache is built once in {@code forState}, so the pathfinding count is fixed
     * at one pass per Udon spot no matter how many complete plans the search evaluates or how many times
     * the opponent reroutes inside a coupled rollout.</p>
     */
    @Test
    void raisingTheBudgetAddsTerminalEvaluationsWithoutAddingAnyPathfinding() {
        DayState state = contestedState();
        AnytimePlanResult small = planner(0, false).planWithStats(state);
        AnytimePlanResult large = planner(64, false).planWithStats(state);
        int spots = state.matchData().udonSpots().size();

        CoupledCompetitiveBaseline cheap =
                small.coupledCompetitiveEvaluation().orElseThrow().coupled().baseline();
        CoupledCompetitiveBaseline rich =
                large.coupledCompetitiveEvaluation().orElseThrow().coupled().baseline();

        assertTrue(large.stats().completedPlans() > small.stats().completedPlans());
        assertEquals(spots, cheap.pathfindingExecutions());
        assertEquals(spots, rich.pathfindingExecutions());
        assertEquals(cheap.routeCostCacheEntries(), rich.routeCostCacheEntries());
        assertTrue(rich.routeCostCacheEntries() > 0);
    }

    @Test
    void theMandatedSearchBoundsAreNeverExceededByTheNewMode() {
        DayState state = contestedState();
        AnytimePlanResult result = planner(64, false).planWithStats(state);
        StratifiedSearchStats depth = result.stratifiedSearchStats().orElseThrow();

        assertTrue(result.stats().expandedStates() <= 64);
        assertTrue(depth.frontierPeak() <= 48);
        assertTrue(depth.strategiesQualified() <= 8);
        assertTrue(depth.discoveryExpansions() <= 16);
        assertTrue(depth.qualificationExpansions() <= 24);
        assertTrue(depth.exploitationExpansions() <= 24);
    }

    @Test
    void theCoupledOwnOutcomesAlwaysPartitionThePlannedOwnOpportunityEvents() {
        DayState state = contestedState();
        CoupledCompetitiveRolloutResult coupled = planner(64, false).planWithStats(state)
                .coupledCompetitiveEvaluation().orElseThrow().coupled();

        assertTrue(coupled.plannedOwnOpportunityEvents().size() > 0,
                "The contested fixture must plan at least one arrival on a stocked spot");
        assertEquals(coupled.plannedOwnOpportunityEvents().size(),
                coupled.coupledOwnCollections() + coupled.ownPlannedEventsInvalidatedByOpponent()
                        + coupled.ownPlannedEventsExhaustedByOwnTeam());
        assertTrue(coupled.coupledOwnBrands() <= coupled.coupledOwnCollections());
        assertTrue(coupled.rolloutEvents() > 0);
    }

    @Test
    void theM16SearchBoundsAreExactlyTheOnesM11ThroughM15Shipped() {
        AnytimePlannerConfig config = AnytimePlannerConfig.defaults();
        StratifiedSearchConfig stratified = StratifiedSearchConfig.defaults();

        assertEquals(64, config.maxExpandedStates());
        assertEquals(48, config.maxFrontierSize());
        assertEquals(4, config.topCandidatesPerState());
        assertEquals(16, stratified.discoveryBudget());
        assertEquals(24, stratified.qualificationBudget());
        assertEquals(24, stratified.exploitationBudget());
        assertEquals(8, stratified.maxQualifiedStrategies());
        assertEquals(2, stratified.minimumQualificationExpansionsPerStrategy());
    }

    @Test
    void theM19PolicyIsTheLastEnumConstantAndLeavesEveryOlderModeInPlace() {
        List<AnytimeSearchPolicy> policies = List.of(AnytimeSearchPolicy.values());

        assertEquals(20, policies.size());
        assertEquals(AnytimeSearchPolicy.ANYTIME_STRATIFIED_CAPACITY_COMPETITIVE,
                policies.getLast());
        assertEquals(AnytimeSearchPolicy.ANYTIME_STRATIFIED_TEAM_ALLOCATED_HYBRID,
                policies.get(policies.size() - 2));
        assertEquals(AnytimeSearchPolicy.ANYTIME_STRATIFIED_HYBRID_DIVERSE_CANDIDATES,
                policies.get(policies.size() - 3));
        assertEquals(AnytimeSearchPolicy.ANYTIME_STRATIFIED_HYBRID_CALIBRATED_MARGIN,
                policies.get(policies.size() - 4));
        assertTrue(policies.contains(
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_REPLACEMENT_AWARE_RELATIVE_MARGIN));
        assertTrue(policies.contains(AnytimeSearchPolicy.ANYTIME_STRATIFIED_RELATIVE_MARGIN_AWARE));
        assertTrue(policies.contains(
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_SEMI_COMMITMENT_HARVEST_HORIZON_AWARE));
    }

    @Test
    void everyOlderModeKeepsItsOwnObjectiveBesideM16() {
        DayState state = contestedState();

        AnytimePlanResult m121 = new SemiCommitmentAwareStratifiedPlanner(
                bounded(), OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), StratifiedSearchConfig.forBudget(0),
                false).planWithStats(state);
        AnytimePlanResult m13 = new HorizonAwareSemiCommitmentPlanner(
                bounded(), OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), StratifiedSearchConfig.forBudget(0),
                false).planWithStats(state);
        AnytimePlanResult m131 = new HarvestHorizonAwareSemiCommitmentPlanner(
                bounded(), OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), StratifiedSearchConfig.forBudget(0),
                false).planWithStats(state);
        AnytimePlanResult m14 = new RelativeMarginAwarePlanner(
                bounded(), OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), StratifiedSearchConfig.forBudget(0),
                false).planWithStats(state);
        AnytimePlanResult m15 = new ReplacementAwareRelativeMarginPlanner(
                bounded(), OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), StratifiedSearchConfig.forBudget(0),
                false).planWithStats(state);

        assertTrue(m121.semiCommitmentAwareEvaluation().isPresent());
        assertTrue(m13.horizonAwareEvaluation().isPresent());
        assertTrue(m131.harvestHorizonAwareEvaluation().isPresent());
        assertTrue(m14.relativeMarginEvaluation().isPresent());
        assertTrue(m15.replacementAwareEvaluation().isPresent());
        for (AnytimePlanResult older : List.of(m121, m13, m131, m14, m15)) {
            assertTrue(older.coupledCompetitiveEvaluation().isEmpty(),
                    "M16 must stay isolated from every older mode");
        }
    }

    @Test
    void candidateGuidanceScoresAWholeDayCoupledTargetAndNeverDecidesAPlan() {
        CoupledCompetitiveCandidateMetrics deepTarget = new CoupledCompetitiveCandidateMetrics(
                false, 1, 3, 1, 60, 1, 4, 2, 10, new Position(5), OWN);
        CoupledCompetitiveCandidateMetrics shallowTarget = new CoupledCompetitiveCandidateMetrics(
                false, 1, 0, 0, 60, 1, 4, 2, 10, new Position(3), OWN);

        assertEquals(4, deepTarget.projectedContestGain());
        assertEquals(1, shallowTarget.projectedContestGain());
        assertTrue(CoupledCompetitiveCandidateMetrics.harvestPreference()
                .compare(deepTarget, shallowTarget) < 0);
        assertTrue(CoupledCompetitiveCandidateMetrics.coveragePreference()
                .compare(deepTarget, shallowTarget) < 0);
    }

    @Test
    void aNewOwnSemiBrandStillOutranksEveryCoupledContestGainInCandidateGuidance() {
        CoupledCompetitiveCandidateMetrics newBrand = new CoupledCompetitiveCandidateMetrics(
                true, 1, 0, 0, 60, 1, 4, 2, 10, new Position(3), OWN);
        CoupledCompetitiveCandidateMetrics richContest = new CoupledCompetitiveCandidateMetrics(
                false, 1, 5, 3, 60, 1, 4, 2, 10, new Position(5), OWN);

        assertTrue(CoupledCompetitiveCandidateMetrics.coveragePreference()
                .compare(newBrand, richContest) < 0);
    }

    private static AnytimePlannerConfig bounded() {
        return new AnytimePlannerConfig(0, 48, 4);
    }

    private static CoupledCompetitiveMarginPlanner planner(int budget, boolean diagnostics) {
        return new CoupledCompetitiveMarginPlanner(
                new AnytimePlannerConfig(budget, 48, 4), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                budget == 0 ? StratifiedSearchConfig.forBudget(0) : StratifiedSearchConfig.defaults(),
                diagnostics);
    }

    private static CoupledCompetitiveMarginEvaluation evaluation(
            int brands, int own, int opponent, int score, String signature) {
        return evaluation(brands, own, opponent, score, signature, 4);
    }

    private static CoupledCompetitiveMarginEvaluation evaluation(
            int brands, int own, int opponent, int score, String signature, int capacitySpots) {
        return build(brands, own, 0, opponent, opponent, 0, score, signature,
                capacity(1, capacitySpots));
    }

    private static CoupledCompetitiveMarginEvaluation finalDayEvaluation(
            int brands, int own, int opponent, int score, String signature) {
        return build(brands, own, 0, opponent, opponent, 0, score, signature, capacity(0, 0));
    }

    private static CoupledCompetitiveMarginEvaluation build(
            int coupledBrands, int coupledOwn, int invalidated, int coupledOpponent,
            int baselineOpponent, int replacement, int score, String signature,
            TeamNextDayHarvestCapacity capacity) {
        PlanEvaluation base = new PlanEvaluation(coupledBrands, coupledOwn, 1, 10, 1, signature);
        SemiCommitmentAwarePlanEvaluation semi = new SemiCommitmentAwarePlanEvaluation(
                base, new SemiCommitmentAdjustedCollectionScore(score), coupledBrands, coupledOwn,
                coupledOwn, coupledOwn, 0, 0, 0, 0, 0, 0);
        return new CoupledCompetitiveMarginEvaluation(
                semi,
                coupled(coupledBrands, coupledOwn, invalidated, coupledOpponent, baselineOpponent,
                        replacement),
                capacity);
    }

    /** A synthetic rollout result honouring every partition invariant the record enforces. */
    private static CoupledCompetitiveRolloutResult coupled(
            int brands, int own, int invalidated, int opponent, int baselineOpponent,
            int replacement) {
        List<PlannedOwnOpportunityEvent> events = new ArrayList<>();
        List<CoupledOwnEventResult> results = new ArrayList<>();
        Set<BrandId> brandSet = new LinkedHashSet<>();
        for (int index = 0; index < own + invalidated; index++) {
            PlannedOwnOpportunityEvent event = new PlannedOwnOpportunityEvent(
                    OWN, new Position(index + 1), index + 1, index);
            events.add(event);
            boolean collected = index < own;
            BrandId brand = new BrandId(
                    "K" + (collected ? Math.min(index, Math.max(brands - 1, 0)) : "x" + index));
            if (collected) {
                brandSet.add(brand);
            }
            results.add(new CoupledOwnEventResult(event, brand,
                    collected ? CoupledOwnEventOutcome.COLLECTED
                            : CoupledOwnEventOutcome.INVALIDATED_BY_OPPONENT, false));
        }
        List<OpponentFullDayClaim> claims = claims(opponent);
        return new CoupledCompetitiveRolloutResult(
                baseline(baselineOpponent), events, results, claims, own, brandSet, invalidated, 0,
                Math.min(replacement, claims.size()), 0, 0, opponent, 0,
                opponent + events.size() + 1, opponent);
    }

    private static CoupledCompetitiveBaseline baseline(int collections) {
        List<OpponentFullDayClaim> claims = claims(collections);
        Map<Position, List<OpponentFullDayClaim>> bySpot = new LinkedHashMap<>();
        claims.forEach(claim -> bySpot
                .computeIfAbsent(claim.spot(), ignored -> new ArrayList<>()).add(claim));
        return new CoupledCompetitiveBaseline(claims, bySpot, 0, 0, collections, 1, 1, 20,
                collections + 1, collections, 1, 1);
    }

    private static List<OpponentFullDayClaim> claims(int collections) {
        List<OpponentFullDayClaim> claims = new ArrayList<>();
        for (int index = 0; index < collections; index++) {
            claims.add(new OpponentFullDayClaim(7, 0, 0, new Position(1), 10 + index, index, 2, 1,
                    OpponentClaimCommitment.FOLLOW_ON_INTENT));
        }
        return List.copyOf(claims);
    }

    private static TeamNextDayHarvestCapacity capacity(int remainingDays, int spots) {
        return TeamNextDayHarvestCapacity.aggregate(remainingDays, 10, List.of(
                new PatrolNextDayHarvestCapacity(OWN, new Position(0), 10, remainingDays,
                        false, spots, remainingDays == 0 ? 0 : 1, 0, 0)), 0, 0);
    }

    /** One PLAIN row with three stocked spots and one observed opponent collector. */
    private static DayState contestedState() {
        Terrain[] terrain = new Terrain[7];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<UdonSpot> spots = List.of(
                new UdonSpot(BRAND, new Position(2), 1),
                new UdonSpot(new BrandId("B"), new Position(4), 1),
                new UdonSpot(new BrandId("C"), new Position(5), 1));
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        return new DayState(
                new StaticMatchData(new HexMap(7, 1, terrain), new DayStepBudgets(new int[] {6, 6}),
                        List.of(), new FuelCapacity(20), spots),
                new DayIndex(0),
                List.of(AgentState.patrol(OWN, new Position(0), 20)),
                Map.of(), stock,
                List.of(new ObservedOtherGroup(7, List.of(
                        new ObservedOtherAgent(new Position(6), 0, -1)))));
    }
}
