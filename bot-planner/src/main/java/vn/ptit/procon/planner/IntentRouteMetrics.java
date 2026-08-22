package vn.ptit.procon.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.udon.BrandId;

/**
 * Branch-local M10 route attribution used for candidate and partial-state guidance.
 *
 * <p>{@code forecastRealizableBrands} holds the brands this route collects whose
 * forecast stock still remains at our arrival. {@code forecastRealizableBrandGain}
 * counts how many of those are new relative to the parent branch, so a route whose
 * only source of a brand is already forecast claimed contributes no brand gain.</p>
 */
record IntentRouteMetrics(
        int projectedCollectionGain,
        int adjustedScore,
        int forecastRealizableCollections,
        int likelyClaimedFirstCollections,
        int tieCollections,
        int unforecastedCollections,
        Set<BrandId> forecastRealizableBrands,
        int forecastRealizableBrandGain) {

    IntentRouteMetrics {
        forecastRealizableBrands = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(forecastRealizableBrands, "Realizable brands must not be null")));
        if (forecastRealizableBrandGain < 0
                || forecastRealizableBrandGain > forecastRealizableBrands.size()) {
            throw new IllegalArgumentException(
                    "Realizable brand gain must be within the realizable brand set");
        }
    }

    static IntentRouteMetrics empty() {
        return new IntentRouteMetrics(0, 0, 0, 0, 0, 0, Set.of(), 0);
    }
}
