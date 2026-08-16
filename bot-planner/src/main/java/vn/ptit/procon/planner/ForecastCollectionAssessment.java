package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** Forecast interpretation for one simulator-projected own collection event. */
public record ForecastCollectionAssessment(
        Position spot,
        int ourCollectionStep,
        IntentCollectionClassification classification,
        int forecastRemainingStockAtOurArrival,
        boolean forecastRealizable,
        int intentValueUnits) {

    public ForecastCollectionAssessment {
        Objects.requireNonNull(spot, "Assessment spot must not be null");
        Objects.requireNonNull(classification, "Assessment classification must not be null");
        if (ourCollectionStep < 0 || forecastRemainingStockAtOurArrival < 0 || intentValueUnits < 0) {
            throw new IllegalArgumentException("Forecast collection assessment metrics must be non-negative");
        }
        if (forecastRealizable != (forecastRemainingStockAtOurArrival > 0)) {
            throw new IllegalArgumentException("Forecast realizability must match remaining stock");
        }
    }
}