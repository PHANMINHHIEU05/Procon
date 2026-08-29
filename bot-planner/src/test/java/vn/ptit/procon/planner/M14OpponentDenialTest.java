package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
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
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/** M14 stock-denial counterfactuals; all tests use deterministic hand-built commitment claims. */
class M14OpponentDenialTest {

    private static final AgentId OWN = new AgentId(0);
    private static final BrandId BRAND = new BrandId("A");
    private static final OpponentDenialEvaluator EVALUATOR = new OpponentDenialEvaluator();

    @Test
    void oneDirectClaimStrictlyAfterOwnCollectionIsDenied() {
        DayState state = state(List.of(spot(1, 1)));
        OpponentClaimBaseline baseline = EVALUATOR.baseline(state, forecast(state, 1,
                direct(1, 10, 0)));
        OpponentResidualClaimEvaluation result = EVALUATOR.evaluate(
                state, baseline, List.of(own(1, 5)));

        assertEquals(1, baseline.directIntentRealizable());
        assertEquals(0, result.residualDirectIntent());
        assertEquals(1, result.deniedDirectIntent());
        assertEquals(1, result.strongDeniedOpponentCollections());
    }

    @Test
    void equalStepIsTieAndCannotDeny() {
        DayState state = state(List.of(spot(1, 1)));
        OpponentResidualClaimEvaluation result = EVALUATOR.evaluate(
                state, EVALUATOR.baseline(state, forecast(state, 1, direct(1, 10, 0))),
                List.of(own(1, 10)));

        assertEquals(1, result.residualDirectIntent());
        assertEquals(0, result.deniedDirectIntent());
    }

    @Test
    void ownCollectionAfterOpponentCannotDeny() {
        DayState state = state(List.of(spot(1, 1)));
        OpponentResidualClaimEvaluation result = EVALUATOR.evaluate(
                state, EVALUATOR.baseline(state, forecast(state, 1, direct(1, 5, 0))),
                List.of(own(1, 10)));

        assertEquals(1, result.residualDirectIntent());
        assertEquals(0, result.deniedDirectIntent());
    }

    @Test
    void fiveDirectClaimsOnOneStockDenyAtMostOneBaselinePortion() {
        DayState state = state(List.of(spot(1, 1)));
        OpponentCommitmentForecast forecast = forecast(state, 5,
                direct(1, 10, 0), direct(1, 11, 1), direct(1, 12, 2),
                direct(1, 13, 3), direct(1, 14, 4));
        OpponentClaimBaseline baseline = EVALUATOR.baseline(state, forecast);
        OpponentResidualClaimEvaluation result = EVALUATOR.evaluate(
                state, baseline, List.of(own(1, 5)));

        assertEquals(1, baseline.totalRealizable());
        assertEquals(1, result.deniedDirectIntent());
        assertTrue(result.deniedDirectIntent() <= baseline.directIntentRealizable());
    }

    @Test
    void oneOwnPortionAgainstTwoStockDeniesExactlyOne() {
        DayState state = state(List.of(spot(1, 2)));
        OpponentClaimBaseline baseline = EVALUATOR.baseline(state, forecast(state, 2,
                direct(1, 10, 0), direct(1, 11, 1)));
        OpponentResidualClaimEvaluation result = EVALUATOR.evaluate(
                state, baseline, List.of(own(1, 5)));

        assertEquals(2, baseline.totalRealizable());
        assertEquals(1, result.residualDirectIntent());
        assertEquals(1, result.deniedDirectIntent());
    }

    @Test
    void capacityRemainsWhenStockFiveHasOneOpponentClaim() {
        DayState state = state(List.of(spot(1, 5)));
        OpponentResidualClaimEvaluation result = EVALUATOR.evaluate(
                state, EVALUATOR.baseline(state, forecast(state, 1, direct(1, 10, 0))),
                List.of(own(1, 5)));

        assertEquals(1, result.residualDirectIntent());
        assertEquals(0, result.deniedDirectIntent());
    }

    @Test
    void separateSpotsHaveIndependentDenialCapacity() {
        DayState state = state(List.of(spot(1, 1), spot(2, 1)));
        OpponentCommitmentForecast forecast = forecast(state, 2,
                direct(1, 10, 0), direct(2, 10, 1));
        OpponentResidualClaimEvaluation result = EVALUATOR.evaluate(
                state, EVALUATOR.baseline(state, forecast), List.of(own(1, 5), own(2, 6)));

        assertEquals(2, result.deniedDirectIntent());
    }

    @Test
    void followOnDenialIsSoftAndSeparatedFromStrongDenial() {
        DayState state = state(List.of(spot(1, 2)));
        OpponentCommitmentForecast forecast = forecast(state, 2,
                direct(1, 10, 0), followOn(1, 11, 1));
        OpponentResidualClaimEvaluation result = EVALUATOR.evaluate(
                state, EVALUATOR.baseline(state, forecast), List.of(own(1, 5)));

        assertEquals(0, result.deniedDirectIntent());
        assertEquals(1, result.deniedFollowOnIntent());
        assertEquals(0, result.strongDeniedOpponentCollections());
        assertEquals(1, result.softDeniedOpponentCollections());
    }

    @Test
    void unavailableOwnM121EventCannotDenyOpponent() {
        DayState state = state(List.of(spot(1, 1)));
        TeamPlan plan = new TeamPlan(Map.of(OWN, List.of(
                new MoveAction(Direction.RIGHT))));
        ValidDaySimulationResult simulation = (ValidDaySimulationResult) new DaySimulator().simulate(state, plan);
        OpponentCommitmentForecast forecast = forecast(state, 1, observed(1, 0));
        SemiCommitmentCollectionAttribution attribution = new SemiCommitmentForecastEvaluator().evaluate(
                state, simulation, forecast, SemiCommitmentAdjustmentWeights.defaults());
        OpponentResidualClaimEvaluation result = EVALUATOR.evaluate(
                state, EVALUATOR.baseline(state, forecast), simulation, attribution);

        assertFalse(attribution.assessments().getFirst().semiCommitmentRealizable());
        assertEquals(1, result.residualObservedNow());
        assertEquals(0, result.deniedObservedNow());
    }

    @Test
    void baselineIsDeterministicAcrossClaimInsertionOrder() {
        DayState state = state(List.of(spot(1, 2)));
        OpponentCommitmentForecast first = forecast(state, 2,
                followOn(1, 11, 0), direct(1, 10, 1));
        OpponentCommitmentForecast second = forecast(state, 2,
                direct(1, 10, 1), followOn(1, 11, 0));

        assertEquals(EVALUATOR.baseline(state, first), EVALUATOR.baseline(state, second));
    }

    @Test
    void relativeMarginModeIsIsolatedAndEmitsBoundedBaselineAndDoneDiagnostics() {
        DayState state = state(List.of(spot(1, 1)));
        AnytimePlannerConfig config = new AnytimePlannerConfig(0, 48, 4);
        RelativeMarginAwarePlanner planner = new RelativeMarginAwarePlanner(
                config, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(), StratifiedSearchConfig.forBudget(0), true);
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

        assertTrue(result.relativeMarginEvaluation().isPresent());
        assertTrue(result.semiCommitmentAwareEvaluation().isEmpty());
        assertTrue(result.horizonAwareEvaluation().isEmpty());
        assertTrue(result.harvestHorizonAwareEvaluation().isEmpty());
        assertTrue(logs.contains("OPPONENT_DENIAL_BASELINE"));
        assertTrue(logs.contains("ANYTIME_STRATIFIED_RELATIVE_MARGIN_START"));
        assertTrue(logs.contains("ANYTIME_STRATIFIED_RELATIVE_MARGIN_DONE"));
    }

    private static OpponentResidualClaimEvaluation evaluate(
            DayState state, int stock, List<CommittedOpponentClaim> claims, List<OpponentDenialEvaluator.OwnCollection> own) {
        OpponentClaimBaseline baseline = EVALUATOR.baseline(state, forecast(state, stock,
                claims.toArray(CommittedOpponentClaim[]::new)));
        return EVALUATOR.evaluate(state, baseline, own);
    }

    private static OpponentDenialEvaluator.OwnCollection own(int spot, int step) {
        return new OpponentDenialEvaluator.OwnCollection(new Position(spot), step, OWN.value(), step);
    }

    private static CommittedOpponentClaim direct(int spot, int step, int agent) {
        return claim(spot, step, agent, OpponentClaimCommitment.DIRECT_INTENT);
    }

    private static CommittedOpponentClaim followOn(int spot, int step, int agent) {
        return claim(spot, step, agent, OpponentClaimCommitment.FOLLOW_ON_INTENT);
    }

    private static CommittedOpponentClaim observed(int spot, int agent) {
        return claim(spot, 0, agent, OpponentClaimCommitment.OBSERVED_NOW);
    }

    private static CommittedOpponentClaim claim(
            int spot, int step, int agent, OpponentClaimCommitment commitment) {
        return new CommittedOpponentClaim(
                new ForecastOpponentClaim(7, agent, 0, new Position(spot), step, IntentRank.PRIMARY, 1),
                commitment);
    }

    private static OpponentCommitmentForecast forecast(
            DayState state, int ignoredStock, CommittedOpponentClaim... claims) {
        Map<Position, SpotCommitmentPressure> pressure = new LinkedHashMap<>();
        Map<Position, List<CommittedOpponentClaim>> bySpot = new LinkedHashMap<>();
        for (CommittedOpponentClaim claim : claims) {
            bySpot.computeIfAbsent(claim.spot(), ignored -> new java.util.ArrayList<>()).add(claim);
        }
        int observed = 0;
        int direct = 0;
        int followOn = 0;
        int hard = 0;
        for (UdonSpot spot : state.matchData().udonSpots()) {
            List<CommittedOpponentClaim> values = bySpot.getOrDefault(spot.position(), List.of());
            int observedAtSpot = (int) values.stream().filter(v -> v.commitment() == OpponentClaimCommitment.OBSERVED_NOW).count();
            int directAtSpot = (int) values.stream().filter(v -> v.commitment() == OpponentClaimCommitment.DIRECT_INTENT).count();
            int followAtSpot = (int) values.stream().filter(v -> v.commitment() == OpponentClaimCommitment.FOLLOW_ON_INTENT).count();
            pressure.put(spot.position(), new SpotCommitmentPressure(spot.position(), spot.stockCapacity(),
                    observedAtSpot, directAtSpot, followAtSpot,
                    Math.min(spot.stockCapacity(), observedAtSpot), values));
            observed += observedAtSpot;
            direct += directAtSpot;
            followOn += followAtSpot;
            hard += Math.min(spot.stockCapacity(), observedAtSpot);
        }
        return new OpponentCommitmentForecast(pressure, 1, 1, state.matchData().udonSpots().size(),
                observed + direct + followOn, observed, direct, followOn, hard);
    }

    private static UdonSpot spot(int position, int stock) {
        return new UdonSpot(BRAND, new Position(position), stock);
    }

    private static DayState state(List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[3];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(value -> stock.put(value.position(), value.stockCapacity()));
        return new DayState(new StaticMatchData(new HexMap(3, 1, terrain),
                new DayStepBudgets(new int[] {2}), List.of(), new FuelCapacity(20), spots),
                new DayIndex(0), List.of(AgentState.patrol(OWN, new Position(0), 20)), Map.of(), stock);
    }
}