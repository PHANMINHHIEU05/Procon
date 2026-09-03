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

class M161HybridCalibratedMarginTest {

    private static final AgentId OWN = new AgentId(0);

    @Test
    void m5043ComparatorPrefersBaselineThroughputOverCoupledBrandGain() {
        HybridCalibratedMarginEvaluation a = evaluation(4, 13, 3, 6, 14, 10, "a");
        HybridCalibratedMarginEvaluation b = evaluation(4, 6, 4, 5, 14, 11, "b");

        assertEquals(45, a.hybridOwnScore4());
        assertEquals(52, a.hybridOpponentScore4());
        assertEquals(-7, a.hybridMarginScore4());
        assertEquals(23, b.hybridOwnScore4());
        assertEquals(53, b.hybridOpponentScore4());
        assertEquals(-30, b.hybridMarginScore4());
        assertTrue(a.betterThan(b));
        assertFalse(b.betterThan(a));
    }

    @Test
    void coupledSignalStillChangesRankingWhenBaselineAndSemiValuesTie() {
        HybridCalibratedMarginEvaluation stronger = evaluation(4, 10, 3, 8, 14, 10, "a");
        HybridCalibratedMarginEvaluation weaker = evaluation(4, 10, 4, 5, 14, 13, "b");

        assertEquals(stronger.ownSemiCollections(), weaker.ownSemiCollections());
        assertTrue(stronger.hybridMarginScore4() > weaker.hybridMarginScore4());
        assertTrue(stronger.betterThan(weaker));
    }

    @Test
    void semiBrandCoverageRemainsPrimaryOverACollectionMarginTrade() {
        HybridCalibratedMarginEvaluation fourBrands = evaluation(4, 10, 3, 5, 14, 20, "a");
        HybridCalibratedMarginEvaluation threeBrands = evaluation(3, 10, 4, 8, 14, 5, "b");

        assertTrue(threeBrands.hybridMarginScore4() > fourBrands.hybridMarginScore4());
        assertTrue(fourBrands.betterThan(threeBrands));
    }

    @Test
    void coupledBrandCoverageIsASecondaryTieBreak() {
        HybridCalibratedMarginEvaluation fourBrands = evaluation(4, 10, 4, 5, 14, 10, "a");
        HybridCalibratedMarginEvaluation threeBrands = evaluation(4, 10, 3, 5, 14, 10, "b");

        assertEquals(fourBrands.hybridMarginScore4(), threeBrands.hybridMarginScore4());
        assertEquals(fourBrands.hybridOwnScore4(), threeBrands.hybridOwnScore4());
        assertTrue(fourBrands.betterThan(threeBrands));
    }

    @Test
    void improvementDiagnosticNamesTheHybridCriterionThatChanged() {
        HybridCalibratedMarginEvaluation incumbent = evaluation(4, 6, 3, 5, 14, 11, "a");
        HybridCalibratedMarginEvaluation improved = evaluation(4, 13, 3, 6, 14, 10, "b");

        assertEquals("HYBRID_MARGIN", improved.improvementCriterion(incumbent));
    }

    @Test
    void horizonKeysAreSkippedOnTheFinalDay() {
        HybridCalibratedMarginEvaluation first = evaluation(4, 10, 3, 5, 14, 10, "a", 0, 1);
        HybridCalibratedMarginEvaluation second = evaluation(4, 10, 3, 5, 14, 10, "b", 0, 8);

        assertEquals(0, first.nextDayHarvestCapacity().remainingFutureDays());
        assertTrue(first.betterThan(second), "Signature should settle a final-day full tie");
    }

    @Test
    void actualHybridSearchUsesItsOwnComparatorAndDiagnostics() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        AnytimePlanResult result;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            result = new HybridCalibratedMarginPlanner(
                    new AnytimePlannerConfig(64, 48, 4), OpponentIntentConfig.defaults(),
                    IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                    StratifiedSearchConfig.defaults(), false).planWithStats(contestedState());
        } finally {
            System.setOut(original);
        }
        String logs = output.toString(StandardCharsets.UTF_8);

        assertTrue(result.hybridCalibratedMarginEvaluation().isPresent());
        assertTrue(result.coupledCompetitiveEvaluation().isEmpty());
        assertTrue(result.stats().expandedStates() <= 64);
        assertTrue(logs.contains("ANYTIME_STRATIFIED_HYBRID_MARGIN_START"));
        assertTrue(logs.contains("ANYTIME_STRATIFIED_HYBRID_MARGIN_DONE"));
        assertFalse(logs.contains("ANYTIME_STRATIFIED_COUPLED_MARGIN_START"));
        assertTrue(logs.contains("hybridBaseWeight=3"));
        assertTrue(logs.contains("hybridCoupledWeight=1"));
    }

    @Test
    void m17DiscoveryUsesAllFourFamiliesWithinTheFrozenDiscoveryBudget() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        AnytimePlanResult result;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            result = new HybridDiverseCandidatePlanner(
                    new AnytimePlannerConfig(64, 48, 4), OpponentIntentConfig.defaults(),
                    IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                    StratifiedSearchConfig.defaults(), false).planWithStats(richM17State());
        } finally {
            System.setOut(original);
        }
        String logs = output.toString(StandardCharsets.UTF_8);

        assertTrue(result.hybridCalibratedMarginEvaluation().isPresent());
        assertTrue(result.stats().expandedStates() <= 64);
        assertEquals(4, countLinesContaining(logs, "M17_DISCOVERY_FAMILY", "family=THROUGHPUT"));
        assertEquals(4, countLinesContaining(logs, "M17_DISCOVERY_FAMILY", "family=SPATIAL_SEPARATION"));
        assertEquals(4, countLinesContaining(logs, "M17_DISCOVERY_FAMILY", "family=ROUTE_ORDER"));
        assertEquals(4, countLinesContaining(logs, "M17_DISCOVERY_FAMILY", "family=CONTENTION_AVOIDANCE"));
        assertTrue(logs.contains("M17_CANDIDATE_DIVERSITY_SUMMARY"));
        assertTrue(logs.contains("M17_DISCOVERY_CANDIDATE"));
        assertTrue(logs.contains("throughputGenerated="));
        assertTrue(logs.contains("spatialSeparationUnique="));
        assertTrue(logs.contains("routeOrderGenerated="));
        assertTrue(logs.contains("contentionAvoidanceUnique="));
        assertTrue(logs.contains("distinctPlanSignatures="));
        assertTrue(logs.contains("distinctFirstTargetAssignmentSignatures="));
        assertTrue(logs.contains("distinctRoutePrefixSignatures="));
        assertTrue(lastField(logs, "distinctFirstTargetAssignmentSignatures") > 1);
        assertTrue(lastField(logs, "distinctRoutePrefixSignatures") > 1);
        assertTrue(logs.contains("selectedCandidateOrigin="));
        assertTrue(logs.contains("routeCostCacheEntries="));
        assertTrue(logs.contains("pathfindingExecutions="));
        assertFalse(logs.contains("ANYTIME_STRATIFIED_HYBRID_MARGIN_START"));
    }

    @Test
    void duplicateCompletePlansAreRejectedBeforeTheyCanBeEvaluatedTwice() {
        M17CandidateSignatureRegistry registry = new M17CandidateSignatureRegistry();

        assertTrue(registry.accept("0:MRIGHT,W2;"));
        assertFalse(registry.accept("0:MRIGHT,W2;"));
        assertEquals(1, registry.size());
    }

    @Test
    void m18AllocatesWholeTeamOpportunitiesWithinTheFrozenBudget() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        AnytimePlanResult result;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            result = new HybridTeamAllocatedPlanner(
                    new AnytimePlannerConfig(64, 48, 4), OpponentIntentConfig.defaults(),
                    IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                    StratifiedSearchConfig.defaults(), false).planWithStats(richM17State());
        } finally {
            System.setOut(original);
        }
        String logs = output.toString(StandardCharsets.UTF_8);

        assertTrue(result.hybridCalibratedMarginEvaluation().isPresent());
        assertTrue(result.stats().expandedStates() <= 64);
        assertTrue(logs.contains("ANYTIME_STRATIFIED_TEAM_ALLOCATED_HYBRID_START"));
        assertTrue(logs.contains("M18_ALLOCATION_CANDIDATE"));
        assertTrue(logs.contains("M18_TEAM_ALLOCATION_SUMMARY"));
        assertEquals(16, lastField(logs, "allocationAttempts"));
        assertTrue(lastField(logs, "validAllocations") > 0);
        assertTrue(lastField(logs, "maxDistinctFirstAssignments") > 1);
        assertFalse(logs.contains("M17_DISCOVERY_FAMILY"));
        assertFalse(logs.contains("ANYTIME_STRATIFIED_HYBRID_DIVERSE_CANDIDATES_START"));
    }

    @Test
    void m19UsesCapacityClaimsAndDoesNotPathfindDuringCandidateGeneration() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        AnytimePlanResult result;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            result = new CapacityCompetitivePlanner(
                    new AnytimePlannerConfig(64, 48, 4), OpponentIntentConfig.defaults(),
                    IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                    StratifiedSearchConfig.defaults(), false).planWithStats(richM17State());
        } finally {
            System.setOut(original);
        }
        String logs = output.toString(StandardCharsets.UTF_8);

        assertTrue(result.hybridCalibratedMarginEvaluation().isPresent());
        assertTrue(result.stats().expandedStates() <= 64);
        assertTrue(logs.contains("ANYTIME_STRATIFIED_CAPACITY_COMPETITIVE_START"));
        assertTrue(logs.contains("M19_CAPACITY_CANDIDATE"));
        assertTrue(logs.contains("M19_CAPACITY_SUMMARY"));
        assertEquals(16, lastField(logs, "claimCandidateAttempts"));
        assertEquals(0, lastField(logs, "candidateGenerationPathfindingExecutions"));
        assertTrue(lastField(logs, "capacityThroughputUnique") > 0);
    }

    private static long countLinesContaining(String logs, String required, String family) {
        return logs.lines().filter(line -> line.contains(required) && line.contains(family)).count();
    }

    private static int lastField(String logs, String field) {
        String line = logs.lines().filter(value -> value.contains(field + "=")).reduce((a, b) -> b)
                .orElseThrow();
        int start = line.indexOf(field + "=") + field.length() + 1;
        int end = line.indexOf(' ', start);
        return Integer.parseInt(line.substring(start, end < 0 ? line.length() : end));
    }

    private static HybridCalibratedMarginEvaluation evaluation(
            int semiBrands, int semiCollections, int coupledBrands, int coupledCollections,
            int baselineOpponent, int coupledOpponent, String signature) {
        return evaluation(semiBrands, semiCollections, coupledBrands, coupledCollections,
                baselineOpponent, coupledOpponent, signature, 1, 4);
    }

    private static HybridCalibratedMarginEvaluation evaluation(
            int semiBrands, int semiCollections, int coupledBrands, int coupledCollections,
            int baselineOpponent, int coupledOpponent, String signature,
            int remainingDays, int capacitySpots) {
        PlanEvaluation base = new PlanEvaluation(Math.max(semiBrands, coupledBrands), 20, 1, 10, 1, signature);
        SemiCommitmentAwarePlanEvaluation semi = new SemiCommitmentAwarePlanEvaluation(
                base, new SemiCommitmentAdjustedCollectionScore(20), semiBrands, semiCollections,
                semiCollections, semiCollections, 0, 0, 0, 0, 0, 0);
        return new HybridCalibratedMarginEvaluation(semi,
                coupled(coupledBrands, coupledCollections, baselineOpponent, coupledOpponent),
                capacity(remainingDays, capacitySpots));
    }

    private static CoupledCompetitiveRolloutResult coupled(
            int brands, int own, int baselineOpponent, int opponent) {
        List<PlannedOwnOpportunityEvent> events = new ArrayList<>();
        List<CoupledOwnEventResult> results = new ArrayList<>();
        Set<BrandId> brandSet = new LinkedHashSet<>();
        for (int index = 0; index < own; index++) {
            PlannedOwnOpportunityEvent event = new PlannedOwnOpportunityEvent(
                    OWN, new Position(index + 1), index + 1, index);
            BrandId brand = new BrandId("K" + (index % brands));
            events.add(event);
            results.add(new CoupledOwnEventResult(event, brand,
                    CoupledOwnEventOutcome.COLLECTED, false));
            brandSet.add(brand);
        }
        List<OpponentFullDayClaim> claims = claims(opponent);
        return new CoupledCompetitiveRolloutResult(
                baseline(baselineOpponent), events, results, claims, own, brandSet, 0, 0,
                0, 0, 0, opponent, 0, opponent + events.size() + 1, opponent);
    }

    private static CoupledCompetitiveBaseline baseline(int collections) {
        List<OpponentFullDayClaim> claims = claims(collections);
        Map<Position, List<OpponentFullDayClaim>> bySpot = new LinkedHashMap<>();
        claims.forEach(claim -> bySpot.computeIfAbsent(claim.spot(), ignored -> new ArrayList<>()).add(claim));
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
                        false, remainingDays == 0 ? 0 : spots,
                        remainingDays == 0 ? 0 : 1, 0, 0)), 0, 0);
    }

    private static DayState contestedState() {
        Terrain[] terrain = new Terrain[7];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<UdonSpot> spots = List.of(
                new UdonSpot(new BrandId("A"), new Position(2), 1),
                new UdonSpot(new BrandId("B"), new Position(4), 1),
                new UdonSpot(new BrandId("C"), new Position(5), 1));
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        return new DayState(
                new StaticMatchData(new HexMap(7, 1, terrain), new DayStepBudgets(new int[] {6, 6}),
                        List.of(), new FuelCapacity(20), spots), new DayIndex(0),
                List.of(AgentState.patrol(OWN, new Position(0), 20)), Map.of(), stock,
                List.of(new ObservedOtherGroup(7, List.of(
                        new ObservedOtherAgent(new Position(6), 0, -1)))));
    }

    private static DayState richM17State() {
        Terrain[] terrain = new Terrain[32];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<UdonSpot> spots = new ArrayList<>();
        for (int index = 2; index <= 30; index += 2) {
            spots.add(new UdonSpot(new BrandId("M17-" + index), new Position(index), 2));
        }
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        return new DayState(
                new StaticMatchData(new HexMap(32, 1, terrain),
                        new DayStepBudgets(new int[] {26, 26}), List.of(), new FuelCapacity(60), spots),
                new DayIndex(0),
                List.of(AgentState.patrol(OWN, new Position(0), 60),
                        AgentState.patrol(new AgentId(1), new Position(1), 60)),
                Map.of(), stock,
                List.of(new ObservedOtherGroup(7, List.of(
                        new ObservedOtherAgent(new Position(31), 0, -1)))));
    }
}
