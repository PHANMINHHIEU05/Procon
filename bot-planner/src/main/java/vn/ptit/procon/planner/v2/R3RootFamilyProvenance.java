package vn.ptit.procon.planner.v2;

import java.util.List;
import java.util.Objects;

/** Immutable R3 root identity used for diagnostics, never for strategic state deduplication. */
public record R3RootFamilyProvenance(
        int supportServiceCount,
        List<Integer> supportedPatrolIds,
        Integer refuelActorId,
        String supportSkeletonSignature) implements Comparable<R3RootFamilyProvenance> {

    public R3RootFamilyProvenance {
        if (supportServiceCount < 0 || supportServiceCount > 3) {
            throw new IllegalArgumentException("R3 support service count must be in [0, 3]");
        }
        supportedPatrolIds = supportedPatrolIds.stream().sorted().distinct().toList();
        supportSkeletonSignature = Objects.requireNonNull(supportSkeletonSignature,
                "Support skeleton signature must not be null");
        if (supportServiceCount == 0) {
            if (!supportedPatrolIds.isEmpty() || refuelActorId != null || !supportSkeletonSignature.equals("NO_REFUEL")) {
                throw new IllegalArgumentException("NO_REFUEL provenance must not contain support data");
            }
        } else if (supportedPatrolIds.size() != supportServiceCount || refuelActorId == null) {
            throw new IllegalArgumentException("Support provenance must describe every service and REFUEL actor");
        }
    }

    public static R3RootFamilyProvenance noRefuel() {
        return new R3RootFamilyProvenance(0, List.of(), null, "NO_REFUEL");
    }

    @Override
    public int compareTo(R3RootFamilyProvenance other) {
        return supportSkeletonSignature.compareTo(other.supportSkeletonSignature);
    }
}
