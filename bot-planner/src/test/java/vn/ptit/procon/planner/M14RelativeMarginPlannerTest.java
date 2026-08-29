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
import vn.ptit.procon.engine.TeamPlan;

/** M14 mode integration, production bounds and old-mode isolation. */
class M14RelativeMarginPlannerTest {

    @Test
    void liveShapedFivePatrolFixtureIsDeterministicAndKeepsProductionBounds() {
        DayStateFixture fixture = liveFivePatrolFixture();
        RelativeMarginAwarePlanner planner = new RelativeMarginAwarePlanner(
                AnytimePlannerConfig.defaults(), OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(), SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.defaults(), true);

        Run first = run(planner, fixture.state());
        Run second = run(planner, fixture.state());
        RelativeMarginPlanEvaluation evaluation = first.result().relativeMarginEvaluation().orElseThrow();
        StratifiedSearchStats depth = first.result().stratifiedSearchStats().orElseThrow();

        assertEquals(actions(first.result().plan()), actions(second.result().plan()));
        assertEquals(first.result().relativeMarginEvaluation(), second.result().relativeMarginEvaluation());
        assertEquals(first.result().stats(), second.result().stats());
        assertEquals(first.result().stratifiedSearchStats(), second.result().stratifiedSearchStats());
        assertEquals(first.logs(), second.logs());
        assertTrue(first.result().stats().expandedStates() <= 64);
        assertTrue(depth.frontierPeak() <= 48);
        assertTrue(depth.strategiesQualified() <= 8);
        if (first.result().stats().budgetExhausted()) {
            assertEquals(64, depth.totalExpansions());
            assertEquals(16, depth.discoveryExpansions());
            assertEquals(24, depth.qualificationExpansions());
            assertEquals(24, depth.exploitationExpansions());
        }
        assertEquals(8, evaluation.nextDayHarvestCapacity().pathfindingExecutions());
        assertTrue(evaluation.opponentClaims().baseline().forecastClaims() > 0);
        assertEquals(1, count(first.logs(), "OPPONENT_DENIAL_BASELINE "));
        assertEquals(1, count(first.logs(), "ANYTIME_STRATIFIED_RELATIVE_MARGIN_START "));
        assertEquals(1, count(first.logs(), "ANYTIME_STRATIFIED_RELATIVE_MARGIN_DONE "));
        assertTrue(first.logs().contains("NEXT_DAY_HARVEST_CAPACITY_SUMMARY"));
    }

    @Test
    void m121M13AndM131RemainModeIsolatedAndDeterministic() {
        DayStateFixture fixture = liveFivePatrolFixture();
        AnytimePlannerConfig noSearch = new AnytimePlannerConfig(0, 48, 4);
        StratifiedSearchConfig noStages = StratifiedSearchConfig.forBudget(0);
        AnytimePlanResult m121a = new SemiCommitmentAwareStratifiedPlanner(
                noSearch, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), noStages, false).planWithStats(fixture.state());
        AnytimePlanResult m121b = new SemiCommitmentAwareStratifiedPlanner(
                noSearch, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), noStages, false).planWithStats(fixture.state());
        AnytimePlanResult m13a = new HorizonAwareSemiCommitmentPlanner(
                noSearch, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), noStages, false).planWithStats(fixture.state());
        AnytimePlanResult m13b = new HorizonAwareSemiCommitmentPlanner(
                noSearch, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), noStages, false).planWithStats(fixture.state());
        AnytimePlanResult m131a = new HarvestHorizonAwareSemiCommitmentPlanner(
                noSearch, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), noStages, false).planWithStats(fixture.state());
        AnytimePlanResult m131b = new HarvestHorizonAwareSemiCommitmentPlanner(
                noSearch, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), noStages, false).planWithStats(fixture.state());

        assertEquals(actions(m121a.plan()), actions(m121b.plan()));
        assertEquals(m121a.semiCommitmentAwareEvaluation(), m121b.semiCommitmentAwareEvaluation());
        assertEquals(actions(m13a.plan()), actions(m13b.plan()));
        assertEquals(m13a.horizonAwareEvaluation(), m13b.horizonAwareEvaluation());
        assertEquals(actions(m131a.plan()), actions(m131b.plan()));
        assertEquals(m131a.harvestHorizonAwareEvaluation(), m131b.harvestHorizonAwareEvaluation());
        assertTrue(m121a.relativeMarginEvaluation().isEmpty());
        assertTrue(m13a.relativeMarginEvaluation().isEmpty());
        assertTrue(m131a.relativeMarginEvaluation().isEmpty());
    }

    @Test
    void candidateGuidancePrioritizesStrongSwingThenPreservedFuelBeforeFollowOn() {
        RelativeMarginCandidateMetrics ownAndDenial = candidate(2, 1, 0, 8);
        RelativeMarginCandidateMetrics ownOnly = candidate(2, 0, 2, 20);
        assertTrue(RelativeMarginCandidateMetrics.harvestPreference()
                .compare(ownAndDenial, ownOnly) < 0);

        RelativeMarginCandidateMetrics preserved = candidate(2, 1, 0, 12);
        RelativeMarginCandidateMetrics depleted = candidate(2, 1, 3, 8);
        assertTrue(RelativeMarginCandidateMetrics.harvestPreference()
                .compare(preserved, depleted) < 0);
    }

    @Test
    void finalDayStillUsesDenialWithNeutralHarvestCapacity() {
        TeamNextDayHarvestCapacity neutral = TeamNextDayHarvestCapacity.aggregate(0, 0,
                List.of(new PatrolNextDayHarvestCapacity(
                        new AgentId(0), new Position(0), 10, 0, false, 0, 0, 0, 0)), 0, 0);
        RelativeMarginPlanEvaluation noDenial = value(neutral, 10, 0, "a");
        RelativeMarginPlanEvaluation denial = value(neutral, 10, 1, "b");

        assertTrue(denial.betterThan(noDenial));
        assertEquals(0, denial.nextDayHarvestCapacity().remainingFutureDays());
    }

    private static RelativeMarginCandidateMetrics candidate(
            int own, int strongDenial, int followOn, int fuel) {
        return new RelativeMarginCandidateMetrics(
                false, own, strongDenial, followOn, 10, own, 4, 4, fuel,
                new Position(1), new AgentId(0));
    }

    private static RelativeMarginPlanEvaluation value(
            TeamNextDayHarvestCapacity capacity, int own, int denied, String signature) {
        SemiCommitmentAwarePlanEvaluation semi = new SemiCommitmentAwarePlanEvaluation(
                new PlanEvaluation(1, own, 1, 10, 1, signature),
                new SemiCommitmentAdjustedCollectionScore(40), 1, own, own, own,
                0, 0, 0, 0, 0, 0);
        List<BaselineOpponentClaim> claims = denied == 0 ? List.of() : List.of(
                new BaselineOpponentClaim(new CommittedOpponentClaim(
                        new ForecastOpponentClaim(5, 0, 0, new Position(1), 10,
                                IntentRank.PRIMARY, 1), OpponentClaimCommitment.DIRECT_INTENT), 0));
        OpponentClaimBaseline baseline = new OpponentClaimBaseline(
                claims.isEmpty() ? Map.of() : Map.of(new Position(1), claims),
                denied, 0, denied, 0, 1);
        OpponentResidualClaimEvaluation residual = new OpponentResidualClaimEvaluation(
                baseline, 0, 0, 0, 0, denied, 0);
        return new RelativeMarginPlanEvaluation(semi, residual, capacity);
    }

    private static Run run(RelativeMarginAwarePlanner planner, vn.ptit.procon.engine.DayState state) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            return new Run(planner.planWithStats(state), output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    private static Map<Integer, List<String>> actions(TeamPlan plan) {
        Map<Integer, List<String>> result = new TreeMap<>();
        plan.actionsByAgent().forEach((id, list) ->
                result.put(id.value(), list.stream().map(Object::toString).toList()));
        return result;
    }

    private static int count(String logs, String prefix) {
        return (int) logs.lines().filter(line -> line.startsWith(prefix)).count();
    }

    private static DayStateFixture liveFivePatrolFixture() {
        Terrain[] terrain = new Terrain[144];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<AgentState> agents = new ArrayList<>();
        int[] starts = {62, 63, 64, 74, 75};
        for (int index = 0; index < starts.length; index++) {
            agents.add(AgentState.patrol(new AgentId(index), new Position(starts[index]), 30));
        }
        agents.add(AgentState.refuel(new AgentId(5), new Position(76)));
        List<UdonSpot> spots = List.of(
                spot("A", 31, 3), spot("B", 34, 1), spot("C", 37, 1), spot("D", 40, 1),
                spot("A", 52, 3), spot("B", 55, 1), spot("C", 58, 1), spot("D", 85, 1));
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(12, 12, terrain), new DayStepBudgets(new int[] {24, 24, 24}),
                List.of(), new FuelCapacity(30), spots);
        List<ObservedOtherAgent> others = List.of(
                other(31, 0), other(33, 0), other(51, 0), other(53, 0), other(86, 0), other(74, 1));
        return new DayStateFixture(new vn.ptit.procon.engine.DayState(
                match, new DayIndex(0), agents, Map.of(), stock,
                List.of(new ObservedOtherGroup(5, others))));
    }

    private static ObservedOtherAgent other(int position, int rawKind) {
        return new ObservedOtherAgent(new Position(position), rawKind, 40);
    }

    private static UdonSpot spot(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }

    private record DayStateFixture(vn.ptit.procon.engine.DayState state) { }

    private record Run(AnytimePlanResult result, String logs) { }
}