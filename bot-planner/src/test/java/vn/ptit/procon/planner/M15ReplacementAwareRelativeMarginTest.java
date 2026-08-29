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
import vn.ptit.procon.engine.DayState;

/**
 * M15 replacement-aware relative margin: terminal key order, mode isolation and bounded diagnostics.
 *
 * <p>Nothing here fits a coefficient. The margin is the plain unweighted difference between our own
 * M12.1 semi-realizable collections and the opponent collections that survive the full-day rollout.</p>
 */
class M15ReplacementAwareRelativeMarginTest {

    private static final AgentId OWN = new AgentId(0);
    private static final BrandId BRAND = new BrandId("A");

    @Test
    void ownSemiRealizableBrandCoverageOutranksTheMargin() {
        ReplacementAwareRelativeMarginEvaluation fewerBrands = evaluation(3, 20, 0, 1, 65, "a");
        ReplacementAwareRelativeMarginEvaluation moreBrands = evaluation(4, 10, 9, 1, 60, "b");

        assertTrue(moreBrands.betterThan(fewerBrands));
        assertFalse(fewerBrands.betterThan(moreBrands));
    }

    @Test
    void theReplacementAwareMarginIsOwnCollectionsMinusResidualOpponentCollections() {
        ReplacementAwareRelativeMarginEvaluation evaluation = evaluation(4, 20, 7, 1, 65, "a");

        assertEquals(20, evaluation.ownSemiCollections());
        assertEquals(7, evaluation.residualOpponentCollections());
        assertEquals(13, evaluation.projectedReplacementAwareMargin());
    }

    @Test
    void aBetterMarginBeatsOneMoreOwnCollection() {
        ReplacementAwareRelativeMarginEvaluation ownTwenty = evaluation(4, 20, 8, 1, 65, "a");
        ReplacementAwareRelativeMarginEvaluation ownNineteen = evaluation(4, 19, 5, 1, 65, "b");

        assertEquals(12, ownTwenty.projectedReplacementAwareMargin());
        assertEquals(14, ownNineteen.projectedReplacementAwareMargin());
        assertTrue(ownNineteen.betterThan(ownTwenty));
    }

    @Test
    void ownCollectionsBreakAMarginTieBeforeTheHorizonKeys() {
        ReplacementAwareRelativeMarginEvaluation ownTwenty = evaluation(4, 20, 8, 1, 65, "a");
        ReplacementAwareRelativeMarginEvaluation ownTwelve = evaluation(4, 12, 0, 1, 65, "b");

        assertEquals(ownTwenty.projectedReplacementAwareMargin(),
                ownTwelve.projectedReplacementAwareMargin());
        assertTrue(ownTwenty.betterThan(ownTwelve));
    }

    @Test
    void theUnchangedHorizonKeysStillDecideWhenMarginAndOwnCountsTie() {
        ReplacementAwareRelativeMarginEvaluation poor = evaluation(4, 20, 8, 1, 65, "a", 3);
        ReplacementAwareRelativeMarginEvaluation rich = evaluation(4, 20, 8, 1, 65, "b", 4);

        assertTrue(rich.betterThan(poor));
        assertFalse(poor.betterThan(rich));
    }

    @Test
    void theHorizonKeysAreSkippedOnTheFinalDayAndResidualCountDecidesInstead() {
        ReplacementAwareRelativeMarginEvaluation lastDay = finalDayEvaluation(4, 20, 8, 65, "a");
        ReplacementAwareRelativeMarginEvaluation other = finalDayEvaluation(4, 20, 8, 65, "b");

        assertEquals(0, lastDay.nextDayHarvestCapacity().remainingFutureDays());
        assertEquals(lastDay.projectedReplacementAwareMargin(),
                other.projectedReplacementAwareMargin());
        assertTrue(lastDay.betterThan(other), "The deterministic signature must settle a full tie");
    }

    /**
     * The residual key is reachable only as a consistency guard.
     *
     * <p>Because the margin is exactly {@code own - residual}, a tie on both the margin and the own
     * collection count forces the residual counts to be equal as well, so key 7 can never flip a
     * decision that keys 2 and 3 left open. This test pins that algebraic fact rather than pretending
     * the key discriminates.</p>
     */
    @Test
    void theResidualKeyIsAConsistencyGuardBecauseTheMarginAndOwnCountsAlreadyPinIt() {
        ReplacementAwareRelativeMarginEvaluation lowResidual = evaluation(4, 8, 2, 1, 65, "b");
        ReplacementAwareRelativeMarginEvaluation highResidual = evaluation(4, 14, 8, 1, 65, "a");

        assertEquals(6, lowResidual.projectedReplacementAwareMargin());
        assertEquals(6, highResidual.projectedReplacementAwareMargin());
        assertTrue(highResidual.betterThan(lowResidual),
                "Own collections rank above residual opponent collections");
        ReplacementAwareRelativeMarginEvaluation tied = evaluation(4, 14, 8, 1, 65, "b");
        assertEquals(tied.residualOpponentCollections(), highResidual.residualOpponentCollections());
        assertTrue(highResidual.betterThan(tied),
                "With the margin and own count tied the residual must tie too, leaving the signature");
    }

    @Test
    void theSignedNetRemovedAndReplacementCountsAreExposedForDiagnostics() {
        ReplacementAwareRelativeMarginEvaluation improved = evaluation(4, 20, 12, 3, 65, "a");

        assertEquals(14, improved.baselineOpponentCollections());
        assertEquals(12, improved.residualOpponentCollections());
        assertEquals(2, improved.netOpponentCollectionsRemoved());
        assertEquals(3, improved.replacementCollections());
    }

    @Test
    void aReplacementRicherThanTheBaselineYieldsANegativeNetRemoved() {
        ReplacementAwareRelativeMarginEvaluation worse = evaluationWithBaseline(4, 20, 1, 3, 2, 65, "a");

        assertEquals(1, worse.baselineOpponentCollections());
        assertEquals(3, worse.residualOpponentCollections());
        assertEquals(-2, worse.netOpponentCollectionsRemoved());
        assertEquals(17, worse.projectedReplacementAwareMargin());
    }

    @Test
    void theModeIsIsolatedAndEmitsItsOwnBoundedDiagnostics() {
        DayState state = contestedState();
        ReplacementAwareRelativeMarginPlanner planner = new ReplacementAwareRelativeMarginPlanner(
                new AnytimePlannerConfig(0, 48, 4), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(0), true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        AnytimePlanResult result;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            result = planner.planWithStats(state);
        } finally {
            System.setOut(original);
        }
        String logs = output.toString(StandardCharsets.UTF_8);

        assertTrue(result.replacementAwareEvaluation().isPresent());
        assertTrue(result.relativeMarginEvaluation().isEmpty());
        assertTrue(result.harvestHorizonAwareEvaluation().isEmpty());
        assertTrue(result.horizonAwareEvaluation().isEmpty());
        assertTrue(result.semiCommitmentAwareEvaluation().isEmpty());
        assertTrue(result.commitmentAwareEvaluation().isEmpty());
        assertTrue(logs.contains("OPPONENT_FULL_DAY_BASELINE"));
        assertTrue(logs.contains("OPPONENT_FULL_DAY_ROUTE"));
        assertTrue(logs.contains("ANYTIME_STRATIFIED_REPLACEMENT_MARGIN_START"));
        assertTrue(logs.contains("ANYTIME_STRATIFIED_REPLACEMENT_MARGIN_DONE"));
        assertFalse(logs.contains("OPPONENT_DENIAL_BASELINE"));
    }

    @Test
    void theM14ModeKeepsItsOwnObjectiveAndDiagnosticsBesideM15() {
        DayState state = contestedState();
        RelativeMarginAwarePlanner m14 = new RelativeMarginAwarePlanner(
                new AnytimePlannerConfig(0, 48, 4), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(0), false);
        AnytimePlanResult result = m14.planWithStats(state);

        assertTrue(result.relativeMarginEvaluation().isPresent());
        assertTrue(result.replacementAwareEvaluation().isEmpty());
    }

    @Test
    void theFullDayBaselineIsComputedOncePerPlanningRun() {
        DayState state = contestedState();
        ReplacementAwareRelativeMarginPlanner planner = new ReplacementAwareRelativeMarginPlanner(
                new AnytimePlannerConfig(64, 48, 4), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.defaults(), true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        AnytimePlanResult result;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            result = planner.planWithStats(state);
        } finally {
            System.setOut(original);
        }
        long baselineLines = output.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("OPPONENT_FULL_DAY_BASELINE")).count();

        assertEquals(1, baselineLines);
        assertEquals(state.matchData().udonSpots().size(),
                result.replacementAwareEvaluation().orElseThrow().opponentFullDay().baseline()
                        .pathfindingExecutions());
    }

    @Test
    void theM15SearchBoundsAreExactlyTheOnesM11ThroughM14Shipped() {
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
    void thePolicyKeepsItsOwnEnumConstantAndLeavesEveryOlderModeInPlace() {
        List<AnytimeSearchPolicy> policies = List.of(AnytimeSearchPolicy.values());

        assertEquals(16, policies.size());
        assertEquals(14, policies.indexOf(
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_REPLACEMENT_AWARE_RELATIVE_MARGIN));
        assertTrue(policies.contains(AnytimeSearchPolicy.ANYTIME_STRATIFIED_RELATIVE_MARGIN_AWARE));
        assertTrue(policies.contains(
                AnytimeSearchPolicy.ANYTIME_STRATIFIED_SEMI_COMMITMENT_HARVEST_HORIZON_AWARE));
    }

    @Test
    void candidateGuidanceScoresAFullDayTargetInsteadOfAThreeTargetPrefix() {
        ReplacementAwareCandidateMetrics deepTarget = new ReplacementAwareCandidateMetrics(
                false, 1, 3, 1, 60, 1, 4, 2, 10, new Position(5), OWN);
        ReplacementAwareCandidateMetrics shallowTarget = new ReplacementAwareCandidateMetrics(
                false, 1, 0, 0, 60, 1, 4, 2, 10, new Position(3), OWN);

        assertEquals(4, deepTarget.projectedContestGain());
        assertEquals(1, shallowTarget.projectedContestGain());
        assertTrue(ReplacementAwareCandidateMetrics.harvestPreference()
                .compare(deepTarget, shallowTarget) < 0);
        assertTrue(ReplacementAwareCandidateMetrics.coveragePreference()
                .compare(deepTarget, shallowTarget) < 0);
    }

    private static ReplacementAwareRelativeMarginEvaluation evaluation(
            int brands, int own, int residual, int replacement, int score, String signature) {
        return evaluation(brands, own, residual, replacement, score, signature, 4);
    }

    private static ReplacementAwareRelativeMarginEvaluation evaluation(
            int brands, int own, int residual, int replacement, int score, String signature,
            int capacitySpots) {
        return build(brands, own, residual + 2, residual, replacement, score, signature,
                capacity(1, capacitySpots));
    }

    private static ReplacementAwareRelativeMarginEvaluation evaluationWithBaseline(
            int brands, int own, int baseline, int residual, int replacement, int score,
            String signature) {
        return build(brands, own, baseline, residual, replacement, score, signature, capacity(1, 4));
    }

    private static ReplacementAwareRelativeMarginEvaluation finalDayEvaluation(
            int brands, int own, int residual, int score, String signature) {
        return build(brands, own, residual + 2, residual, 1, score, signature, capacity(0, 0));
    }

    private static ReplacementAwareRelativeMarginEvaluation build(
            int brands, int own, int baselineCollections, int residualCollections,
            int replacement, int score, String signature, TeamNextDayHarvestCapacity capacity) {
        PlanEvaluation base = new PlanEvaluation(brands, own, 1, 10, 1, signature);
        SemiCommitmentAwarePlanEvaluation semi = new SemiCommitmentAwarePlanEvaluation(
                base, new SemiCommitmentAdjustedCollectionScore(score), brands, own, own, own,
                0, 0, 0, 0, 0, 0);
        OpponentFullDayBaseline baseline = baseline(baselineCollections);
        OpponentFullDayResidualEvaluation residual = new OpponentFullDayResidualEvaluation(
                baseline, claims(residualCollections), 0, 0, residualCollections,
                Math.min(replacement, residualCollections), 1, Math.min(residualCollections, 1));
        return new ReplacementAwareRelativeMarginEvaluation(semi, residual, capacity);
    }

    private static OpponentFullDayBaseline baseline(int collections) {
        List<OpponentFullDayClaim> claims = claims(collections);
        Map<Position, List<OpponentFullDayClaim>> bySpot = new LinkedHashMap<>();
        claims.forEach(claim -> bySpot
                .computeIfAbsent(claim.spot(), ignored -> new ArrayList<>()).add(claim));
        return new OpponentFullDayBaseline(claims, bySpot, 0, 0, collections, 1, 1, 20,
                collections + 1, Math.min(collections, 1), 1, 1);
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
