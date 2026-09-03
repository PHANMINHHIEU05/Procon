package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Optional;

/** Complete strategic skeleton with optional existing R3 support provenance. */
public record TeamRouteSkeleton(List<RouteSkeleton> patrols, Optional<String> supportRootProvenance) {
    public TeamRouteSkeleton { patrols = List.copyOf(patrols); supportRootProvenance = supportRootProvenance == null ? Optional.empty() : supportRootProvenance; }
}
