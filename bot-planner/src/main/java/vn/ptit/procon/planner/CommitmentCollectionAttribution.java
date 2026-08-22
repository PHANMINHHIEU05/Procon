package vn.ptit.procon.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.udon.BrandId;

/**
 * Complete-plan M12 collection attribution from simulator timeline events.
 *
 * <p>{@code commitmentRealizableCollections} counts the projected own collections that survive
 * <em>hard</em> forecast depletion only. It is deliberately less pessimistic than the M10
 * forecast-realizable count, which subtracted every claim regardless of commitment class.</p>
 *
 * <p>{@code oldForecastRealizableCollections} is the unchanged M10 number for the very same plan
 * and the very same forecast, computed in the same pass so the two can always be compared without
 * re-simulating anything.</p>
 */
public record CommitmentCollectionAttribution(
        CommitmentAdjustedCollectionScore adjustedScore,
        int commitmentRealizableCollections,
        int oldForecastRealizableCollections,
        int hardClaimedFirstCollections,
        int directIntentBeforeCollections,
        int followOnIntentBeforeCollections,
        int tieCollections,
        int likelyAvailableCollections,
        int unforecastedCollections,
        Set<BrandId> localProjectedBrands,
        Set<BrandId> commitmentRealizableBrands,
        List<CommitmentCollectionAssessment> assessments) {

    public CommitmentCollectionAttribution {
        Objects.requireNonNull(adjustedScore, "Commitment-adjusted score must not be null");
        assessments = List.copyOf(Objects.requireNonNull(assessments, "Assessments must not be null"));
        localProjectedBrands = immutableBrands(localProjectedBrands, "Local projected brands");
        commitmentRealizableBrands = immutableBrands(
                commitmentRealizableBrands, "Commitment-realizable brands");
        if (commitmentRealizableCollections < 0 || oldForecastRealizableCollections < 0
                || hardClaimedFirstCollections < 0 || directIntentBeforeCollections < 0
                || followOnIntentBeforeCollections < 0 || tieCollections < 0
                || likelyAvailableCollections < 0 || unforecastedCollections < 0) {
            throw new IllegalArgumentException("Commitment attribution metrics must be non-negative");
        }
        if (hardClaimedFirstCollections + directIntentBeforeCollections
                + followOnIntentBeforeCollections + tieCollections + likelyAvailableCollections
                + unforecastedCollections != assessments.size()) {
            throw new IllegalArgumentException(
                    "Commitment classification counts must cover every projected collection");
        }
        if (commitmentRealizableCollections > assessments.size()
                || !localProjectedBrands.containsAll(commitmentRealizableBrands)) {
            throw new IllegalArgumentException(
                    "Commitment-realizable attribution cannot exceed local projected attribution");
        }
        if (oldForecastRealizableCollections > commitmentRealizableCollections) {
            throw new IllegalArgumentException(
                    "M10 forecast-realizable collections cannot exceed the M12 commitment-realizable"
                            + " count for the same plan and forecast");
        }
    }

    private static Set<BrandId> immutableBrands(Set<BrandId> brands, String name) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(brands, name + " must not be null")));
    }
}
