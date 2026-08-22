package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
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
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;

class IntentForecastEvaluatorTest {

    private static final AgentId PATROL = new AgentId(0);
    private static final BrandId BRAND_D = new BrandId("D");
    private static final BrandId BRAND_E = new BrandId("E");

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
        OpponentIntentForecast forecast = emptyForecast();

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

    @Test
    void redundantBrandSourceKeepsBrandRealizableWhenOneSourceIsLost() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, rightMoves(3));
        OpponentIntentForecast forecast = forecastClaiming(state, Map.of(position(1), List.of(0)));

        IntentCollectionAttribution attribution = evaluate(state, simulation, forecast);

        assertEquals(3, attribution.assessments().size());
        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.localProjectedBrands());
        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.forecastRealizableBrands());
        assertEquals(1, attribution.likelyClaimedFirstCollections());
        assertEquals(2, attribution.forecastRealizableCollections());
    }

    @Test
    void brandWithEveryProjectedSourceLostStopsBeingRealizable() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, rightMoves(3));
        OpponentIntentForecast forecast = forecastClaiming(
                state, Map.of(position(1), List.of(0), position(2), List.of(0)));

        IntentCollectionAttribution attribution = evaluate(state, simulation, forecast);

        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.localProjectedBrands());
        assertEquals(Set.of(BRAND_E), attribution.forecastRealizableBrands());
        assertEquals(2, attribution.likelyClaimedFirstCollections());
        assertEquals(1, attribution.forecastRealizableCollections());
    }

    @Test
    void equalStepTieStillContributesRealizableBrand() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, rightMoves(3));
        OpponentIntentForecast forecast = forecastClaiming(state, Map.of(position(1), List.of(2)));

        IntentCollectionAttribution attribution = evaluate(state, simulation, forecast);

        assertEquals(IntentCollectionClassification.CONTESTED_TIE,
                attribution.assessments().get(0).classification());
        assertEquals(1, attribution.tieCollections());
        assertTrue(attribution.assessments().get(0).forecastRealizable());
        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.localProjectedBrands());
        assertEquals(Set.of(BRAND_D, BRAND_E), attribution.forecastRealizableBrands());
    }

    @Test
    void withoutOpponentsRealizableBrandsAndCollectionsMatchRawProjection() {
        DayState state = brandState();
        DaySimulationResult simulation = simulate(state, rightMoves(3));
        ValidDaySimulationResult valid = (ValidDaySimulationResult) simulation;
        long rawCollections = valid.events().stream()
                .filter(UdonCollectedEvent.class::isInstance).count();

        IntentCollectionAttribution attribution = evaluate(state, simulation, emptyForecast());

        assertEquals(3, rawCollections);
        assertEquals(attribution.localProjectedBrands(), attribution.forecastRealizableBrands());
        assertEquals(valid.brandsCollected().size(), attribution.forecastRealizableBrands().size());
        assertEquals(rawCollections, attribution.forecastRealizableCollections());
        assertEquals(rawCollections, attribution.unforecastedCollections());
    }

    @Test
    void realizableAttributionCanNeverExceedLocalProjectedAttribution() {
        assertThrows(IllegalArgumentException.class, () -> new IntentCollectionAttribution(
                new IntentAdjustedCollectionScore(0), 0, 0, 0, 0, 0, 0,
                Set.of(BRAND_D), Set.of(BRAND_D, BRAND_E), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new IntentCollectionAttribution(
                new IntentAdjustedCollectionScore(0), 1, 0, 0, 0, 0, 0,
                Set.of(BRAND_D), Set.of(BRAND_D), List.of()));
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

    private static List<AgentAction> rightMoves(int count) {
        List<AgentAction> actions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            actions.add(new MoveAction(Direction.RIGHT));
        }
        return actions;
    }

    private static OpponentIntentForecast emptyForecast() {
        return new OpponentIntentForecast(List.of(), Map.of(), 1, 1, 1, 1, 1, 0, 0);
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
                OptionalInt.of(arrivals[0]), claims);
        OpponentAgentIntentForecast agent = new OpponentAgentIntentForecast(
                agentIndex, position(0), 0, 40, 1, true,
                List.of(new OpponentTargetIntent(spot.position(), spot.brand(), IntentRank.PRIMARY,
                        2, OptionalInt.of(arrivals[0]), true)));
        OpponentGroupIntentForecast group = new OpponentGroupIntentForecast(groupRawId, List.of(agent));
        return new OpponentIntentForecast(
                List.of(group), Map.of(spot.position(), pressure), 1, 1, 1, 1, 1, 1, claims.size());
    }

    /** Builds a rawKind-zero collector forecast that claims the given spots at the given steps. */
    private OpponentIntentForecast forecastClaiming(
            DayState state, Map<Position, List<Integer>> arrivalsBySpot) {
        Map<Position, SpotIntentPressure> pressure = new LinkedHashMap<>();
        List<OpponentTargetIntent> targets = new ArrayList<>();
        int totalClaims = 0;
        for (UdonSpot spot : state.matchData().udonSpots()) {
            List<Integer> arrivals = arrivalsBySpot.get(spot.position());
            if (arrivals == null) {
                continue;
            }
            List<ForecastOpponentClaim> claims = arrivals.stream()
                    .map(arrival -> new ForecastOpponentClaim(
                            5, 0, 0, spot.position(), arrival, IntentRank.PRIMARY, 1))
                    .toList();
            pressure.put(spot.position(), new SpotIntentPressure(
                    spot.position(), spot.stockCapacity(), 3, 1, claims.size(),
                    OptionalInt.of(arrivals.get(0)), claims));
            targets.add(new OpponentTargetIntent(spot.position(), spot.brand(), IntentRank.PRIMARY,
                    1, OptionalInt.of(arrivals.get(0)), true));
            totalClaims += claims.size();
        }
        OpponentAgentIntentForecast agent = new OpponentAgentIntentForecast(
                0, position(0), 0, 40, targets.size(), true, targets);
        return new OpponentIntentForecast(
                List.of(new OpponentGroupIntentForecast(5, List.of(agent))),
                pressure, 1, 1, state.matchData().udonSpots().size(),
                targets.size(), targets.size(), targets.size(), totalClaims);
    }

    private DayState state(int stock) {
        return state(2, List.of(new UdonSpot(new BrandId("A"), position(1), stock)), 4);
    }

    /** Two redundant brand-D sources followed by a single brand-E source. */
    private DayState brandState() {
        return state(5, List.of(
                new UdonSpot(BRAND_D, position(1), 1),
                new UdonSpot(BRAND_D, position(2), 1),
                new UdonSpot(BRAND_E, position(3), 1)), 6);
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
