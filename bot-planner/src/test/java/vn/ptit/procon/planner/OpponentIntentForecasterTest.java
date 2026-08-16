package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentState;
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

class OpponentIntentForecasterTest {

    @Test
    void oneOpponentCannotThreatenEveryReachableSpot() {
        DayState state = state(12, 12, spots(1, 2, 3, 4, 5, 6),
                List.of(group(7, agent(0, 0))));

        OpponentIntentForecast forecast = new OpponentIntentForecaster().forecast(state);

        assertEquals(6, forecast.physicallyReachablePairs());
        assertEquals(3, forecast.retainedIntentTargets());
        assertEquals(3, forecast.pressureBySpot().size());
    }

    @Test
    void primaryHasGreaterPressureThanTertiary() {
        DayState state = state(6, 5, spots(1, 2),
                List.of(group(7, agent(0, 0))));

        OpponentIntentForecast forecast = new OpponentIntentForecaster().forecast(state);
        List<OpponentTargetIntent> targets = forecast.groups().get(0).agents().get(0).targets();

        assertEquals(IntentRank.PRIMARY, targets.get(0).rank());
        assertEquals(IntentRank.SECONDARY, targets.get(1).rank());
        assertTrue(targets.get(0).pressureUnits() > targets.get(1).pressureUnits());
    }

    @Test
    void routeBudgetStopsForecastContinuation() {
        DayState state = state(10, 6, spots(3, 5, 6),
                List.of(group(7, agent(4, 0))));

        OpponentIntentForecast forecast = new OpponentIntentForecaster().forecast(state);
        List<ForecastOpponentClaim> claims = forecast.pressureBySpot().values().stream()
                .flatMap(pressure -> pressure.claims().stream()).toList();

        assertEquals(2, claims.size());
        assertTrue(claims.stream().noneMatch(claim -> claim.spot().equals(position(6))));
    }

    @Test
    void stockCapKeepsClaimsAtAvailableStock() {
        UdonSpot target = new UdonSpot(new BrandId("A"), position(1), 1);
        DayState state = state(4, 3, List.of(target), List.of(
                group(1, agent(0, 0)), group(2, agent(0, 0)), group(3, agent(0, 0))));

        OpponentIntentForecast forecast = new OpponentIntentForecaster().forecast(state);

        assertEquals(9, forecast.pressureAt(position(1)).intentPressureUnits());
        assertEquals(1, forecast.pressureAt(position(1)).forecastClaimedPortions());
    }

    @Test
    void rawKindPolicyIsExplicitAndDeterministic() {
        DayState state = state(5, 4, spots(1, 2), List.of(group(1, agent(0, 0), agent(1, 1))));
        OpponentIntentForecaster forecaster = new OpponentIntentForecaster();

        OpponentIntentForecast all = forecaster.forecast(state,
                new OpponentIntentConfig(3, OpponentAgentPolicy.ALL_OBSERVED));
        OpponentIntentForecast zero = forecaster.forecast(state,
                new OpponentIntentConfig(3, OpponentAgentPolicy.LIKELY_COLLECTOR_RAW_KIND_ZERO));

        assertEquals(all, forecaster.forecast(state,
                new OpponentIntentConfig(3, OpponentAgentPolicy.ALL_OBSERVED)));
        assertEquals(2, all.includedAgentCount());
        assertEquals(1, zero.includedAgentCount());
    }

    @Test
    void noOpponentCreatesNoIntentPressure() {
        DayState state = state(5, 4, spots(1, 2), List.of());

        OpponentIntentForecast forecast = new OpponentIntentForecaster().forecast(state);

        assertTrue(forecast.pressureBySpot().isEmpty());
        assertEquals(0, forecast.physicallyReachablePairs());
    }

    @Test
    void noReachableStockCreatesNoArtificialPressure() {
        DayState state = state(8, 2, spots(7), List.of(group(1, agent(0, 0))));

        OpponentIntentForecast forecast = new OpponentIntentForecaster().forecast(state);

        assertEquals(1, forecast.observedAgentCount());
        assertEquals(0, forecast.physicallyReachablePairs());
        assertTrue(forecast.pressureBySpot().isEmpty());
    }

    @Test
    void defaultConfigurationIsBoundedAndValidated() {
        assertEquals(new OpponentIntentConfig(3, OpponentAgentPolicy.ALL_OBSERVED),
                OpponentIntentConfig.defaults());
        assertThrows(IllegalArgumentException.class,
                () -> new OpponentIntentConfig(0, OpponentAgentPolicy.ALL_OBSERVED));
        assertThrows(IllegalArgumentException.class,
                () -> new OpponentIntentConfig(4, OpponentAgentPolicy.ALL_OBSERVED));
    }

    @Test
    void groupOrderingDoesNotChangeForecast() {
        DayState first = state(7, 6, spots(1, 2, 3), List.of(
                group(2, agent(0, 0)), group(1, agent(1, 0))));
        DayState second = state(7, 6, spots(1, 2, 3), List.of(
                group(1, agent(1, 0)), group(2, agent(0, 0))));

        assertEquals(new OpponentIntentForecaster().forecast(first),
                new OpponentIntentForecaster().forecast(second));
    }

    private static List<UdonSpot> spots(int... positions) {
        return Arrays.stream(positions)
                .mapToObj(value -> new UdonSpot(new BrandId("A"), position(value), 1))
                .toList();
    }

    private static DayState state(
            int width,
            int budget,
            List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(width, 1, terrain),
                new DayStepBudgets(new int[] {budget}),
                List.of(),
                new vn.ptit.procon.domain.agent.FuelCapacity(30),
                spots);
        return new DayState(match, new DayIndex(0),
                List.of(AgentState.patrol(new vn.ptit.procon.domain.agent.AgentId(0), position(0), 30)),
                Map.of(), stock, others);
    }

    private static ObservedOtherAgent agent(int position, int rawKind) {
        return new ObservedOtherAgent(position(position), rawKind, 40);
    }

    private static ObservedOtherGroup group(int rawId, ObservedOtherAgent... agents) {
        return new ObservedOtherGroup(rawId, List.of(agents));
    }

    private static Position position(int value) {
        return new Position(value);
    }
}