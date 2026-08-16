package vn.ptit.procon.planner;

import java.util.Objects;
import java.util.OptionalInt;
import vn.ptit.procon.domain.map.Position;

/** Immutable arrival-aware contention metrics for a candidate Udon collection. */
public record ArrivalContentionMetrics(
        Position targetPosition,
        int ourArrivalStep,
        OptionalInt otherHexDistanceLowerBound,
        OptionalInt arrivalAdvantage,
        ArrivalContentionClassification classification) {

    public ArrivalContentionMetrics {
        Objects.requireNonNull(targetPosition, "Target position must not be null");
        if (ourArrivalStep < 0) {
            throw new IllegalArgumentException("Our arrival step must be non-negative");
        }
        Objects.requireNonNull(otherHexDistanceLowerBound, "Other hex distance lower bound must not be null");
        Objects.requireNonNull(arrivalAdvantage, "Arrival advantage must not be null");
        Objects.requireNonNull(classification, "Classification must not be null");
    }
}
