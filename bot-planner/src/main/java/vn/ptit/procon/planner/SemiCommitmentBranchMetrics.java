package vn.ptit.procon.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.udon.BrandId;

/**
 * Running M12.1 semi-commitment totals carried along one search branch.
 *
 * <p>Purely additive over the routes committed so far, exactly like {@link CommitmentBranchMetrics}:
 * the branch never re-reads the forecast, it only accumulates what each route already assessed.</p>
 */
record SemiCommitmentBranchMetrics(
        Set<BrandId> realizableTeamBrands,
        int adjustedScore,
        int realizableCollections,
        int hardClaimedFirstCollections,
        int semiClaimedFirstCollections,
        int directIntentBeforeCollections,
        int followOnIntentBeforeCollections,
        int tieCollections) {

    SemiCommitmentBranchMetrics {
        realizableTeamBrands = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(realizableTeamBrands,
                        "Semi-commitment-realizable brands must not be null")));
        if (adjustedScore < 0 || realizableCollections < 0 || hardClaimedFirstCollections < 0
                || semiClaimedFirstCollections < 0 || directIntentBeforeCollections < 0
                || followOnIntentBeforeCollections < 0 || tieCollections < 0) {
            throw new IllegalArgumentException(
                    "Semi-commitment branch metrics must be non-negative");
        }
    }

    static SemiCommitmentBranchMetrics empty() {
        return new SemiCommitmentBranchMetrics(Set.of(), 0, 0, 0, 0, 0, 0, 0);
    }

    SemiCommitmentBranchMetrics plus(SemiCommitmentRouteMetrics route) {
        Set<BrandId> brands = new LinkedHashSet<>(realizableTeamBrands);
        brands.addAll(route.semiCommitmentRealizableBrands());
        return new SemiCommitmentBranchMetrics(
                brands,
                Math.addExact(adjustedScore, route.adjustedScore()),
                realizableCollections + route.semiCommitmentRealizableCollections(),
                hardClaimedFirstCollections + route.hardClaimedFirstCollections(),
                semiClaimedFirstCollections + route.semiClaimedFirstCollections(),
                directIntentBeforeCollections + route.directIntentBeforeCollections(),
                followOnIntentBeforeCollections + route.followOnIntentBeforeCollections(),
                tieCollections + route.tieCollections());
    }

    /** Stable brand key for the deterministic state signature. */
    List<String> realizableBrandKey() {
        return realizableTeamBrands.stream().map(BrandId::value).sorted().toList();
    }
}
