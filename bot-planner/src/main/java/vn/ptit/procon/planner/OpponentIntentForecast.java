package vn.ptit.procon.planner;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** Immutable current-DayState opponent intent and stock-pressure forecast. */
public record OpponentIntentForecast(
        List<OpponentGroupIntentForecast> groups,
        Map<Position, SpotIntentPressure> pressureBySpot,
        int observedAgentCount,
        int includedAgentCount,
        int stockedSpotCount,
        int physicallyReachablePairs,
        int retainedIntentTargets,
        int forecastClaims) {

    public OpponentIntentForecast {
        groups = List.copyOf(Objects.requireNonNull(groups, "Group forecasts must not be null"));
        pressureBySpot = Map.copyOf(Objects.requireNonNull(pressureBySpot, "Spot pressure must not be null"));
        if (observedAgentCount < 0 || includedAgentCount < 0 || stockedSpotCount < 0
                || physicallyReachablePairs < 0 || retainedIntentTargets < 0 || forecastClaims < 0
                || includedAgentCount > observedAgentCount) {
            throw new IllegalArgumentException("Opponent intent summary metrics must be non-negative");
        }
    }

    public SpotIntentPressure pressureAt(Position spot) {
        return pressureBySpot.get(spot);
    }
}