package vn.ptit.procon.domain.traffic;

import java.util.Objects;

/** Exact normalized-flow boundaries for congested and jammed road states. */
public record TrafficThresholds(TrafficFlow congestedThreshold, TrafficFlow jammedThreshold) {

    public TrafficThresholds {
        Objects.requireNonNull(congestedThreshold, "Congested threshold must not be null");
        Objects.requireNonNull(jammedThreshold, "Jammed threshold must not be null");
        if (jammedThreshold.compareTo(congestedThreshold) < 0) {
            throw new IllegalArgumentException(
                    "Jammed threshold must be greater than or equal to congested threshold");
        }
    }

    public static TrafficThresholds of(long congestedThreshold, long jammedThreshold) {
        return new TrafficThresholds(
                TrafficFlow.of(congestedThreshold), TrafficFlow.of(jammedThreshold));
    }
}