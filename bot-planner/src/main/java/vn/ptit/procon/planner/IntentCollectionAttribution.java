package vn.ptit.procon.planner;

import java.util.List;
import java.util.Objects;

/** Complete-plan M10 collection attribution from simulator timeline events. */
public record IntentCollectionAttribution(
        IntentAdjustedCollectionScore adjustedScore,
        int forecastRealizableCollections,
        int likelyAvailableCollections,
        int contestedLaterCollections,
        int tieCollections,
        int likelyClaimedFirstCollections,
        int unforecastedCollections,
        List<ForecastCollectionAssessment> assessments) {

    public IntentCollectionAttribution {
        Objects.requireNonNull(adjustedScore, "Intent-adjusted score must not be null");
        assessments = List.copyOf(Objects.requireNonNull(assessments, "Assessments must not be null"));
        if (forecastRealizableCollections < 0 || likelyAvailableCollections < 0
                || contestedLaterCollections < 0 || tieCollections < 0
                || likelyClaimedFirstCollections < 0 || unforecastedCollections < 0
                || likelyAvailableCollections + contestedLaterCollections + tieCollections
                        + likelyClaimedFirstCollections + unforecastedCollections != assessments.size()) {
            throw new IllegalArgumentException("Intent collection attribution metrics are inconsistent");
        }
    }
}