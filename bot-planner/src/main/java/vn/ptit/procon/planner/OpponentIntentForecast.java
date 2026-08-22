package vn.ptit.procon.planner;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/**
 * Immutable current-DayState opponent intent and stock-pressure forecast.
 *
 * <p>Every observed agent stays visible in {@link #groups()}. Only agents the
 * active {@link OpponentCollectionEligibility} accepts as Udon collectors may
 * produce retained intent targets or forecast claims.</p>
 */
public record OpponentIntentForecast(
        List<OpponentGroupIntentForecast> groups,
        Map<Position, SpotIntentPressure> pressureBySpot,
        int observedAgentCount,
        int collectionEligibleAgentCount,
        int stockedSpotCount,
        int physicalPairsAllObserved,
        int physicalPairsCollectionEligible,
        int retainedIntentTargets,
        int forecastClaims) {

    public OpponentIntentForecast {
        groups = List.copyOf(Objects.requireNonNull(groups, "Group forecasts must not be null"));
        pressureBySpot = Map.copyOf(Objects.requireNonNull(pressureBySpot, "Spot pressure must not be null"));
        if (observedAgentCount < 0 || collectionEligibleAgentCount < 0 || stockedSpotCount < 0
                || physicalPairsAllObserved < 0 || physicalPairsCollectionEligible < 0
                || retainedIntentTargets < 0 || forecastClaims < 0) {
            throw new IllegalArgumentException("Opponent intent summary metrics must be non-negative");
        }
        if (collectionEligibleAgentCount > observedAgentCount
                || physicalPairsCollectionEligible > physicalPairsAllObserved) {
            throw new IllegalArgumentException(
                    "Collection-eligible metrics cannot exceed observed metrics");
        }
    }

    public SpotIntentPressure pressureAt(Position spot) {
        return pressureBySpot.get(spot);
    }
}
