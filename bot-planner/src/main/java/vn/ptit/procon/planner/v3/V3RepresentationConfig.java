package vn.ptit.procon.planner.v3;

/** Finite benchmark caps for the Phase 1 representation oracle. */
public record V3RepresentationConfig(int maxPathLength, int maxPathsPerPatrol,
        int maxTeamCandidates, int maxMaterializedPlans) {
    public V3RepresentationConfig {
        if (maxPathLength <= 0 || maxPathsPerPatrol <= 0 || maxTeamCandidates <= 0 || maxMaterializedPlans <= 0) {
            throw new IllegalArgumentException("Representation caps must be positive");
        }
    }

    public static V3RepresentationConfig defaults() { return new V3RepresentationConfig(6, 192, 4096, 1024); }
}
