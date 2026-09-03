package vn.ptit.procon.planner.v3;

import java.util.List;

/** Immutable team-level strategic allocation. */
public record TeamAllocation(List<PatrolAllocation> patrols, int coverageOverlap,
        int unallocatedHighValueOpportunities, int estimatedTeamCollections, int estimatedTeamBrands) {
    public TeamAllocation {
        patrols = List.copyOf(patrols);
        if (coverageOverlap < 0 || unallocatedHighValueOpportunities < 0 || estimatedTeamCollections < 0
                || estimatedTeamBrands < 0) throw new IllegalArgumentException("Team allocation metrics must be non-negative");
    }
}
