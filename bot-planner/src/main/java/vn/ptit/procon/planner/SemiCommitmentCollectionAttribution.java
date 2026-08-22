package vn.ptit.procon.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.udon.BrandId;

/**
 * Complete-plan M12.1 collection attribution from simulator timeline events.
 *
 * <p>Carries all three calibration numbers for the identical plan, forecast and event ordering, so the
 * middle model can always be checked against the two it sits between:</p>
 *
 * <ul>
 *   <li>{@code oldForecastRealizableCollections} — the unchanged M10 binary count, every claim
 *       deleting a portion;</li>
 *   <li>{@code semiCommitmentRealizableCollections} — M12.1: hard depletion plus one bounded direct
 *       reservation per spot;</li>
 *   <li>{@code commitmentRealizableCollections} — the unchanged M12 count, hard depletion only.</li>
 * </ul>
 *
 * <p>The ordering {@code old <= semi <= commitment} is structural, not incidental: the hard portions
 * plus at most one reserved portion per spot can never exceed the total claims before us, and the semi
 * reservation is never negative.</p>
 */
public record SemiCommitmentCollectionAttribution(
        SemiCommitmentAdjustedCollectionScore adjustedScore,
        int semiCommitmentRealizableCollections,
        int commitmentRealizableCollections,
        int oldForecastRealizableCollections,
        int hardClaimedFirstCollections,
        int semiClaimedFirstCollections,
        int directIntentBeforeCollections,
        int followOnIntentBeforeCollections,
        int tieCollections,
        int likelyAvailableCollections,
        int unforecastedCollections,
        Set<BrandId> localProjectedBrands,
        Set<BrandId> semiCommitmentRealizableBrands,
        List<SemiCommitmentCollectionAssessment> assessments) {

    public SemiCommitmentCollectionAttribution {
        Objects.requireNonNull(adjustedScore, "Semi-commitment-adjusted score must not be null");
        assessments = List.copyOf(Objects.requireNonNull(assessments, "Assessments must not be null"));
        localProjectedBrands = immutableBrands(localProjectedBrands, "Local projected brands");
        semiCommitmentRealizableBrands = immutableBrands(
                semiCommitmentRealizableBrands, "Semi-commitment-realizable brands");
        if (semiCommitmentRealizableCollections < 0 || commitmentRealizableCollections < 0
                || oldForecastRealizableCollections < 0 || hardClaimedFirstCollections < 0
                || semiClaimedFirstCollections < 0 || directIntentBeforeCollections < 0
                || followOnIntentBeforeCollections < 0 || tieCollections < 0
                || likelyAvailableCollections < 0 || unforecastedCollections < 0) {
            throw new IllegalArgumentException(
                    "Semi-commitment attribution metrics must be non-negative");
        }
        if (hardClaimedFirstCollections + semiClaimedFirstCollections
                + directIntentBeforeCollections + followOnIntentBeforeCollections + tieCollections
                + likelyAvailableCollections + unforecastedCollections != assessments.size()) {
            throw new IllegalArgumentException(
                    "Semi-commitment classification counts must cover every projected collection");
        }
        if (semiCommitmentRealizableCollections > assessments.size()
                || !localProjectedBrands.containsAll(semiCommitmentRealizableBrands)) {
            throw new IllegalArgumentException(
                    "Semi-commitment-realizable attribution cannot exceed local projected attribution");
        }
        if (commitmentRealizableCollections > assessments.size()) {
            throw new IllegalArgumentException(
                    "M12 commitment-realizable collections cannot exceed the projected collections");
        }
        if (semiCommitmentRealizableCollections > commitmentRealizableCollections) {
            throw new IllegalArgumentException(
                    "M12.1 semi-commitment-realizable collections cannot exceed the M12"
                            + " commitment-realizable count for the same plan and forecast");
        }
        if (oldForecastRealizableCollections > semiCommitmentRealizableCollections) {
            throw new IllegalArgumentException(
                    "M10 forecast-realizable collections cannot exceed the M12.1"
                            + " semi-commitment-realizable count for the same plan and forecast");
        }
    }

    private static Set<BrandId> immutableBrands(Set<BrandId> brands, String name) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(brands, name + " must not be null")));
    }
}
