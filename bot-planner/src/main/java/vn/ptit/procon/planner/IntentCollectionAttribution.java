package vn.ptit.procon.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.udon.BrandId;

/**
 * Complete-plan M10 collection attribution from simulator timeline events.
 *
 * <p>{@code localProjectedBrands} is the unique brand coverage our simulator
 * projects. {@code forecastRealizableBrands} keeps only the brands with at least
 * one projected collection whose forecast stock still remains at our arrival, so a
 * brand with redundant sources survives when a single source is claimed first.</p>
 */
public record IntentCollectionAttribution(
        IntentAdjustedCollectionScore adjustedScore,
        int forecastRealizableCollections,
        int likelyAvailableCollections,
        int contestedLaterCollections,
        int tieCollections,
        int likelyClaimedFirstCollections,
        int unforecastedCollections,
        Set<BrandId> localProjectedBrands,
        Set<BrandId> forecastRealizableBrands,
        List<ForecastCollectionAssessment> assessments) {

    public IntentCollectionAttribution {
        Objects.requireNonNull(adjustedScore, "Intent-adjusted score must not be null");
        assessments = List.copyOf(Objects.requireNonNull(assessments, "Assessments must not be null"));
        localProjectedBrands = immutableBrands(localProjectedBrands, "Local projected brands");
        forecastRealizableBrands = immutableBrands(forecastRealizableBrands, "Realizable brands");
        if (forecastRealizableCollections < 0 || likelyAvailableCollections < 0
                || contestedLaterCollections < 0 || tieCollections < 0
                || likelyClaimedFirstCollections < 0 || unforecastedCollections < 0
                || likelyAvailableCollections + contestedLaterCollections + tieCollections
                        + likelyClaimedFirstCollections + unforecastedCollections != assessments.size()) {
            throw new IllegalArgumentException("Intent collection attribution metrics are inconsistent");
        }
        if (forecastRealizableCollections > assessments.size()
                || !localProjectedBrands.containsAll(forecastRealizableBrands)) {
            throw new IllegalArgumentException(
                    "Forecast-realizable attribution cannot exceed local projected attribution");
        }
    }

    private static Set<BrandId> immutableBrands(Set<BrandId> brands, String name) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(brands, name + " must not be null")));
    }
}
