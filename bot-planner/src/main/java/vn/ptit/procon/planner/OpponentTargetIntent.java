package vn.ptit.procon.planner;

import java.util.Objects;
import java.util.OptionalInt;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/** One retained target for an observed opponent agent. */
public record OpponentTargetIntent(
        Position spot,
        BrandId brand,
        IntentRank rank,
        int optimisticTravelSteps,
        OptionalInt forecastArrivalStep,
        boolean forecastClaimed) {

    public OpponentTargetIntent {
        Objects.requireNonNull(spot, "Intent spot must not be null");
        Objects.requireNonNull(brand, "Intent brand must not be null");
        Objects.requireNonNull(rank, "Intent rank must not be null");
        Objects.requireNonNull(forecastArrivalStep, "Forecast arrival step must not be null");
        if (optimisticTravelSteps < 0 || forecastArrivalStep.orElse(0) < 0) {
            throw new IllegalArgumentException("Intent travel steps must be non-negative");
        }
        if (forecastClaimed != forecastArrivalStep.isPresent()) {
            throw new IllegalArgumentException("Claimed intent must have exactly one forecast arrival step");
        }
    }

    public int pressureUnits() {
        return rank.pressureUnits();
    }
}