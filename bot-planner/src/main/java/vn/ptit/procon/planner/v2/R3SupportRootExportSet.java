package vn.ptit.procon.planner.v2;

import java.util.List;
import java.util.Objects;

/**
 * The bounded set of support roots {@link JointTeamBeamR3Planner} retains for one day state, exported
 * read-only together with the existing R3 pipeline accounting that produced it.
 *
 * <p>{@link #tourCatalogPathfindingExecutions()} is the pre-existing R3 tour-catalog counter. It is
 * reported separately so that consumers can prove they added no pathfinding of their own.
 */
public record R3SupportRootExportSet(List<R3SupportRootExport> roots, int tourCatalogPathfindingExecutions,
        int partialToursGenerated, int skeletonsConsidered, int skeletonsValidated, int skeletonsValid,
        int skeletonsRetained) {

    public R3SupportRootExportSet {
        roots = List.copyOf(Objects.requireNonNull(roots, "Exported roots must not be null"));
    }

    public static R3SupportRootExportSet empty() {
        return new R3SupportRootExportSet(List.of(), 0, 0, 0, 0, 0, 0);
    }

    /** Distinct R3 root signatures; the caller uses this as the support-root identity axis. */
    public long uniqueSignatures() {
        return roots.stream().map(R3SupportRootExport::signature).distinct().count();
    }

    public long rootsWithServiceCount(int services) {
        return roots.stream().filter(root -> root.serviceCount() == services).count();
    }
}
