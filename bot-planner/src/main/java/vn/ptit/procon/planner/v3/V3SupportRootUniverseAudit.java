package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.planner.v2.R3SupportRootExportSet;

/**
 * V3_SUPPORT_ROOT_UNIVERSE_AUDIT: what the V3 support axis actually contains, and where it came from.
 *
 * <p>{@link #v3GeneratedNewRefuelTours()} is the PART 48 hard diagnostic. It counts roots whose
 * provenance is not the existing R3 universe, so a future regression that starts searching tanker tours
 * inside V3 fails the audit instead of quietly improving a score.
 */
public record V3SupportRootUniverseAudit(boolean noRefuelIncluded, int r3RootsGenerated,
        int r3RootsAvailableToV3, int uniqueSupportRootSignatures, int service0Count, int service1Count,
        int service2Count, int service3Count, int v3GeneratedNewRefuelTours,
        int tourCatalogPathfindingExecutions, int skeletonsConsidered, int skeletonsValidated,
        int skeletonsValid, int skeletonsRetained, List<String> signatures, List<String> provenances) {

    public V3SupportRootUniverseAudit {
        signatures = List.copyOf(Objects.requireNonNull(signatures, "Signatures must not be null"));
        provenances = List.copyOf(Objects.requireNonNull(provenances, "Provenances must not be null"));
    }

    static V3SupportRootUniverseAudit of(List<V3SupportRootContext> roots, R3SupportRootExportSet exported) {
        List<V3SupportRootContext> mobile = roots.stream().filter(V3SupportRootContext::present).toList();
        int foreign = (int) mobile.stream().filter(root -> !root.existingR3Root()).count();
        return new V3SupportRootUniverseAudit(roots.stream().anyMatch(root -> !root.present()),
                exported.roots().size(), mobile.size(),
                (int) roots.stream().map(V3SupportRootContext::signature).distinct().count(),
                (int) roots.stream().filter(root -> root.serviceCount() == 0).count(),
                services(mobile, 1), services(mobile, 2), services(mobile, 3), foreign,
                exported.tourCatalogPathfindingExecutions(), exported.skeletonsConsidered(),
                exported.skeletonsValidated(), exported.skeletonsValid(), exported.skeletonsRetained(),
                roots.stream().map(V3SupportRootContext::signature).toList(),
                roots.stream().map(V3SupportRootContext::provenance).distinct().sorted().toList());
    }

    private static int services(List<V3SupportRootContext> mobile, int count) {
        return (int) mobile.stream().filter(root -> root.serviceCount() == count).count();
    }

    @Override
    public String toString() {
        return "noRefuelIncluded=" + noRefuelIncluded + " r3RootsGenerated=" + r3RootsGenerated
                + " r3RootsAvailableToV3=" + r3RootsAvailableToV3 + " uniqueSupportRootSignatures="
                + uniqueSupportRootSignatures + " service0=" + service0Count + " service1=" + service1Count
                + " service2=" + service2Count + " service3=" + service3Count
                + " v3GeneratedNewRefuelTours=" + v3GeneratedNewRefuelTours
                + " tourCatalogPathfinding=" + tourCatalogPathfindingExecutions
                + " skeletons=" + skeletonsConsidered + "/" + skeletonsValidated + "/" + skeletonsValid
                + "/" + skeletonsRetained + " provenances=" + provenances;
    }
}
