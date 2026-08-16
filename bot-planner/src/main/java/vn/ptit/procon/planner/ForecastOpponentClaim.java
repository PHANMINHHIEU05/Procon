package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** One stock-capped forecast collection opportunity; not server truth. */
public record ForecastOpponentClaim(
        int groupRawId,
        int agentIndex,
        int rawKind,
        Position spot,
        int forecastArrivalStep,
        IntentRank rank,
        int forecastClaimedPortion) {

    public ForecastOpponentClaim {
        if (agentIndex < 0 || forecastArrivalStep < 0 || forecastClaimedPortion != 1) {
            throw new IllegalArgumentException("Forecast claim values are invalid");
        }
        Objects.requireNonNull(spot, "Forecast claim spot must not be null");
        Objects.requireNonNull(rank, "Forecast claim rank must not be null");
    }

    public int pressureUnits() {
        return rank.pressureUnits();
    }
}