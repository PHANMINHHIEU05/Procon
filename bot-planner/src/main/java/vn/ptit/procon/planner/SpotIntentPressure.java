package vn.ptit.procon.planner;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import vn.ptit.procon.domain.map.Position;

/** Combined retained-target pressure and stock-capped forecast claims for one spot. */
public record SpotIntentPressure(
        Position spot,
        int currentStock,
        int intentPressureUnits,
        int intentSourceCount,
        int forecastClaimedPortions,
        OptionalInt earliestClaimStep,
        List<ForecastOpponentClaim> claims) {

    public SpotIntentPressure {
        Objects.requireNonNull(spot, "Pressure spot must not be null");
        Objects.requireNonNull(earliestClaimStep, "Earliest claim step must not be null");
        claims = List.copyOf(Objects.requireNonNull(claims, "Forecast claims must not be null"));
        if (currentStock < 0 || intentPressureUnits < 0 || intentSourceCount < 0
                || forecastClaimedPortions < 0 || forecastClaimedPortions > currentStock
                || forecastClaimedPortions != claims.size()
                || earliestClaimStep.isPresent() != !claims.isEmpty()) {
            throw new IllegalArgumentException("Spot intent pressure metrics are inconsistent");
        }
    }
}