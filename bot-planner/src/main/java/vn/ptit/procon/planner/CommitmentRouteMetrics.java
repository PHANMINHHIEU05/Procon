package vn.ptit.procon.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.udon.BrandId;

/**
 * Branch-local M12 route attribution used for candidate and partial-state guidance.
 *
 * <p>{@code commitmentRealizableBrands} holds the brands this route collects whose hard forecast
 * stock still remains at our arrival, so a brand whose only source is claimed by an observed
 * collector contributes no brand gain, while a brand contested only by future intent still does.</p>
 */
record CommitmentRouteMetrics(
        int projectedCollectionGain,
        int adjustedScore,
        int commitmentRealizableCollections,
        int hardClaimedFirstCollections,
        int directIntentBeforeCollections,
        int followOnIntentBeforeCollections,
        int tieCollections,
        int unforecastedCollections,
        Set<BrandId> commitmentRealizableBrands,
        int commitmentRealizableBrandGain) {

    CommitmentRouteMetrics {
        commitmentRealizableBrands = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(
                        commitmentRealizableBrands, "Commitment-realizable brands must not be null")));
        if (commitmentRealizableBrandGain < 0
                || commitmentRealizableBrandGain > commitmentRealizableBrands.size()) {
            throw new IllegalArgumentException(
                    "Commitment-realizable brand gain must be within the realizable brand set");
        }
    }

    static CommitmentRouteMetrics empty() {
        return new CommitmentRouteMetrics(0, 0, 0, 0, 0, 0, 0, 0, Set.of(), 0);
    }
}
