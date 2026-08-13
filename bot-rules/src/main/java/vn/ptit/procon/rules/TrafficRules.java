package vn.ptit.procon.rules;

import java.util.Objects;
import vn.ptit.procon.domain.traffic.TrafficFlow;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.traffic.TrafficThresholds;

/** Pure traffic normalization and threshold classification rules. */
public final class TrafficRules {

    private TrafficRules() {
    }

    public static TrafficFlow normalize(long totalStoppedSteps, int teamCount) {
        if (totalStoppedSteps < 0) {
            throw new IllegalArgumentException(
                    "Total stopped steps must be non-negative: " + totalStoppedSteps);
        }
        if (teamCount <= 0) {
            throw new IllegalArgumentException("Team count must be positive: " + teamCount);
        }
        return new TrafficFlow(totalStoppedSteps, teamCount);
    }

    public static TrafficStatus classify(
            TrafficFlow normalizedFlow, TrafficThresholds thresholds) {
        Objects.requireNonNull(normalizedFlow, "Normalized traffic flow must not be null");
        Objects.requireNonNull(thresholds, "Traffic thresholds must not be null");

        if (normalizedFlow.compareTo(thresholds.jammedThreshold()) >= 0) {
            return TrafficStatus.JAMMED;
        }
        if (normalizedFlow.compareTo(thresholds.congestedThreshold()) >= 0) {
            return TrafficStatus.CONGESTED;
        }
        return TrafficStatus.CLEAR;
    }

    public static TrafficStatus classify(
            long totalStoppedSteps, int teamCount, TrafficThresholds thresholds) {
        return classify(normalize(totalStoppedSteps, teamCount), thresholds);
    }
}