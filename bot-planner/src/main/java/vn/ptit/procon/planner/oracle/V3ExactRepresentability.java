package vn.ptit.procon.planner.oracle;

/** Exact-skeleton audit against V3 graph, chains, and allocation representation. */
public record V3ExactRepresentability(int optimalOpportunitiesInGraph, int optimalEdgesInGraph,
        int optimalTransitionsInAnyChain, boolean optimalRegionTransitionsRepresentable,
        boolean optimalTeamAllocationRepresentable, boolean optimalSkeletonRepresentable,
        String firstMissingElement, String reason) {
    public V3ExactRepresentability {
        if (optimalOpportunitiesInGraph < 0 || optimalEdgesInGraph < 0 || optimalTransitionsInAnyChain < 0
                || firstMissingElement == null || reason == null) throw new IllegalArgumentException("Invalid V3 audit");
    }
}
