package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

class IntentForecastEvaluatorTest {

    private static final AgentId PATROL = new AgentId(0);

    @Test
    void opponentArrivesFirstGetsZeroAndIsNotRealizable() {
        DayState state = state(1);
        DaySimulationResult simulation = simulate(state);
        OpponentIntentForecast forecast = forecastWithClaim(1, 1, 1);

        IntentCollectionAttribution attribution = evaluate(state, simulation, forecast);

        ForecastCollectionAssessment assessment = attribution.assessments().get(0);
        assertEquals(IntentCollectionClassification.LIKELY_CLAIMED_FIRST, assessment.classification());
        assertEquals(0, assessment.intentValueUnits());
        assertFalse(assessment.forecastRealizable());
    }

    @Test
    void weArriveFirstRetainsFullValueAsContestedLater() {
        DayState state = state(1);
        DaySimulationResult simulation = simulate(state);
        OpponentIntentForecast forecast = forecastWithClaim(1, 1, 7);

        ForecastCollectionAssessment assessment = evaluate(state, simulation, forecast)
                .assessments().get(0);

        assertEquals(IntentCollectionClassification.CONTESTED_LATER, assessment.classification());
        assertEquals(4, assessment.intentValueUnits());
        assertTrue(assessment.forecastRealizable());
    }

    @Test
    void equalArrivalIsTieWithoutDeterministicStockDeletion() {
        DayState state = state(1);
        DaySimulationResult simulation = simulate(state);
        OpponentIntentForecast forecast = forecastWithClaim(1, 1, 2);

        ForecastCollectionAssessment assessment = evaluate(state, simulation, forecast)
                .assessments().get(0);

        assertEquals(IntentCollectionClassification.CONTESTED_TIE, assessment.classification());
        assertEquals(2, assessment.intentValueUnits());
        assertTrue(assessment.forecastRealizable());
    }

    @Test
    void unforecastedCollectionHasFullValue() {
        DayState state = state(1);
        DaySimulationResult simulation = simulate(state);
        OpponentIntentForecast forecast = new OpponentIntentForecast(
                List.of(), Map.of(), 1, 1, 1, 1, 0, 0);

        ForecastCollectionAssessment assessment = evaluate(state, simulation, forecast)
                .assessments().get(0);

        assertEquals(IntentCollectionClassification.UNFORECASTED, assessment.classification());
        assertEquals(4, assessment.intentValueUnits());
        assertTrue(assessment.forecastRealizable());
    }

    @Test
    void multipleStockLeavesLaterOwnCollectionRealizable() {
        UdonSpot spot = new UdonSpot(new BrandId("A"), position(1), 3);
        DayState state = state(3, List.of(spot), 4);
        DaySimulationResult simulation = simulate(state);
        OpponentIntentForecast forecast = forecastWithClaims(spot, 1, 1, 1, 1);

        IntentCollectionAttribution attribution = evaluate(state, simulation, forecast);

        assertTrue(attribution.assessments().stream().anyMatch(ForecastCollectionAssessment::forecastRealizable));
        assertEquals(1, attribution.assessments().get(0).forecastRemainingStockAtOurArrival());
    }

    private IntentCollectionAttribution evaluate(
            DayState state, DaySimulationResult simulation, OpponentIntentForecast forecast) {
        return new IntentForecastEvaluator().evaluate(
                state, simulation, forecast, IntentAdjustmentWeights.defaults());
    }

    private DaySimulationResult simulate(DayState state) {
        return simulate(state, List.of(new MoveAction(Direction.RIGHT), new WaitAction(2)));
    }

    private DaySimulationResult simulate(DayState state, List<? extends AgentAction> actions) {
        Map<AgentId, List<? extends AgentAction>> byAgent = new LinkedHashMap<>();
        byAgent.put(PATROL, actions);
        return new DaySimulator().simulate(state, new TeamPlan(byAgent));
    }

    private OpponentIntentForecast forecastWithClaim(int groupRawId, int agentIndex, int arrival) {
        return forecastWithClaims(new UdonSpot(new BrandId("A"), position(1), 1),
                groupRawId, agentIndex, arrival);
    }

    private OpponentIntentForecast forecastWithClaims(
            UdonSpot spot, int groupRawId, int agentIndex, int... arrivals) {
        List<ForecastOpponentClaim> claims = Arrays.stream(arrivals)
                .mapToObj(arrival -> new ForecastOpponentClaim(
                        groupRawId, agentIndex, 0, spot.position(), arrival, IntentRank.PRIMARY, 1))
                .toList();
        SpotIntentPressure pressure = new SpotIntentPressure(
                spot.position(), spot.stockCapacity(), 3, 1, claims.size(),
                java.util.OptionalInt.of(arrivals[0]), claims);
        OpponentAgentIntentForecast agent = new OpponentAgentIntentForecast(
                agentIndex, position(0), 0, 40, 1,
                List.of(new OpponentTargetIntent(spot.position(), spot.brand(), IntentRank.PRIMARY,
                        2, java.util.OptionalInt.of(arrivals[0]), true)));
        OpponentGroupIntentForecast group = new OpponentGroupIntentForecast(groupRawId, List.of(agent));
        return new OpponentIntentForecast(
                List.of(group), Map.of(spot.position(), pressure), 1, 1, 1, 1, 1, claims.size());
    }

    private DayState state(int stock) {
        return state(2, List.of(new UdonSpot(new BrandId("A"), position(1), stock)), 4);
    }

    private DayState state(int width, List<UdonSpot> spots, int budget) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(width, 1, terrain), new DayStepBudgets(new int[] {budget}), List.of(),
                new vn.ptit.procon.domain.agent.FuelCapacity(20), spots);
        return new DayState(match, new DayIndex(0),
                List.of(AgentState.patrol(PATROL, position(0), 20)), Map.of(), stock);
    }

    private static Position position(int value) {
        return new Position(value);
    }
}