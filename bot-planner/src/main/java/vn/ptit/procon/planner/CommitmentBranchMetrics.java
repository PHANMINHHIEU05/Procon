package vn.ptit.procon.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.udon.BrandId;

/**
 * Accumulated M12 commitment attribution of one partial search branch.
 *
 * <p>Held as a single immutable field on the search state so the M12 mode adds one branch component
 * instead of six, leaving the M10 and M11 branch fields exactly as the shipped modes read them.</p>
 */
record CommitmentBranchMetrics(
        Set<BrandId> realizableTeamBrands,
        int adjustedScore,
        int realizableCollections,
        int hardClaimedFirstCollections,
        int directIntentBeforeCollections,
        int followOnIntentBeforeCollections,
        int tieCollections) {

    CommitmentBranchMetrics {
        realizableTeamBrands = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(
                        realizableTeamBrands, "Commitment-realizable brands must not be null")));
        if (adjustedScore < 0 || realizableCollections < 0 || hardClaimedFirstCollections < 0
                || directIntentBeforeCollections < 0 || followOnIntentBeforeCollections < 0
                || tieCollections < 0) {
            throw new IllegalArgumentException("Commitment branch metrics must be non-negative");
        }
    }

    static CommitmentBranchMetrics empty() {
        return new CommitmentBranchMetrics(Set.of(), 0, 0, 0, 0, 0, 0);
    }

    /** Folds one accepted candidate route into this branch. */
    CommitmentBranchMetrics plus(CommitmentRouteMetrics route) {
        Set<BrandId> brands = new LinkedHashSet<>(realizableTeamBrands);
        brands.addAll(route.commitmentRealizableBrands());
        return new CommitmentBranchMetrics(
                brands,
                Math.addExact(adjustedScore, route.adjustedScore()),
                realizableCollections + route.commitmentRealizableCollections(),
                hardClaimedFirstCollections + route.hardClaimedFirstCollections(),
                directIntentBeforeCollections + route.directIntentBeforeCollections(),
                followOnIntentBeforeCollections + route.followOnIntentBeforeCollections(),
                tieCollections + route.tieCollections());
    }

    /** Sorted brand identifiers, so exact state dedup never depends on set iteration order. */
    List<String> realizableBrandKey() {
        return realizableTeamBrands.stream().map(BrandId::value).sorted().toList();
    }
}
