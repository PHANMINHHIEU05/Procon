package vn.ptit.procon.planner;

import java.util.Objects;
import java.util.OptionalInt;
import vn.ptit.procon.domain.map.Position;

/** Immutable current-snapshot geometric contention metrics for one map position. */
public record ContentionMetrics(
        Position targetPosition,
        OptionalInt ourNearestHexDistance,
        OptionalInt otherNearestHexDistance,
        int otherAgentsWithinRadius1,
        int otherAgentsWithinRadius2,
        OptionalInt distanceAdvantage,
        ContentionClassification classification) {

    public ContentionMetrics {
        Objects.requireNonNull(targetPosition, "Target position must not be null");
        Objects.requireNonNull(ourNearestHexDistance, "Our distance must not be null");
        Objects.requireNonNull(otherNearestHexDistance, "Other distance must not be null");
        Objects.requireNonNull(distanceAdvantage, "Distance advantage must not be null");
        Objects.requireNonNull(classification, "Classification must not be null");
        if (otherAgentsWithinRadius1 < 0 || otherAgentsWithinRadius2 < otherAgentsWithinRadius1) {
            throw new IllegalArgumentException("Contention radius counts are invalid");
        }
    }
}