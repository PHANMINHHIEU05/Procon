package vn.ptit.procon.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.udon.BrandId;

/**
 * M12.1 semi-commitment interpretation of one candidate route, used for candidate ordering only.
 *
 * <p>Mirrors {@link CommitmentRouteMetrics} with the one extra class the bounded middle model can
 * produce, {@code semiClaimedFirstCollections}. Kept separate so the M12 candidate comparator keeps
 * reading exactly the record it read before.</p>
 */
record SemiCommitmentRouteMetrics(
        int projectedCollectionGain,
        int adjustedScore,
        int semiCommitmentRealizableCollections,
        int hardClaimedFirstCollections,
        int semiClaimedFirstCollections,
        int directIntentBeforeCollections,
        int followOnIntentBeforeCollections,
        int tieCollections,
        int unforecastedCollections,
        Set<BrandId> semiCommitmentRealizableBrands,
        int semiCommitmentRealizableBrandGain) {

    SemiCommitmentRouteMetrics {
        semiCommitmentRealizableBrands = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(semiCommitmentRealizableBrands,
                        "Semi-commitment-realizable route brands must not be null")));
        if (projectedCollectionGain < 0 || adjustedScore < 0
                || semiCommitmentRealizableCollections < 0 || hardClaimedFirstCollections < 0
                || semiClaimedFirstCollections < 0 || directIntentBeforeCollections < 0
                || followOnIntentBeforeCollections < 0 || tieCollections < 0
                || unforecastedCollections < 0) {
            throw new IllegalArgumentException(
                    "Semi-commitment route metrics must be non-negative");
        }
        if (semiCommitmentRealizableBrandGain < 0
                || semiCommitmentRealizableBrandGain > semiCommitmentRealizableBrands.size()) {
            throw new IllegalArgumentException(
                    "Semi-commitment-realizable brand gain must be within the realizable brands");
        }
    }

    static SemiCommitmentRouteMetrics empty() {
        return new SemiCommitmentRouteMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, Set.of(), 0);
    }
}
