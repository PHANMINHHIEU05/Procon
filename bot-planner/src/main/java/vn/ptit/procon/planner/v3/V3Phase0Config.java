package vn.ptit.procon.planner.v3;

/** Explicit finite limits for the benchmark-only strategic oracle. */
public record V3Phase0Config(int maxRegions, int maxChainLength, int maxChainsPerPatrol,
        int maxSecondaryChainsPerPatrol, int maxTeamAllocations, int maxMaterializedPlans) {
    public V3Phase0Config {
        if (maxRegions <= 0 || maxChainLength <= 0 || maxChainsPerPatrol <= 0
                || maxSecondaryChainsPerPatrol < 0 || maxTeamAllocations <= 0 || maxMaterializedPlans <= 0) {
            throw new IllegalArgumentException("V3 oracle caps must be finite and positive");
        }
    }
    public static V3Phase0Config defaults() { return new V3Phase0Config(12, 4, 12, 1, 512, 128); }
}
