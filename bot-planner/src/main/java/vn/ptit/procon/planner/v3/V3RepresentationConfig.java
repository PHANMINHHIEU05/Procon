package vn.ptit.procon.planner.v3;

/**
 * Finite benchmark caps for the Phase 1 representation oracle.
 *
 * <p>{@code compositionSearch} selects the Phase 2.6 enumeration organisation and changes NO cap: the same
 * {@code maxPathLength}, {@code maxPathsPerPatrol}, {@code maxTeamCandidates} and {@code maxMaterializedPlans}
 * drive both organisations. It defaults to {@code false} so the historical oracle answer stays reproducible
 * side by side with the new one.
 */
public record V3RepresentationConfig(int maxPathLength, int maxPathsPerPatrol,
        int maxTeamCandidates, int maxMaterializedPlans, boolean compositionSearch) {
    public V3RepresentationConfig {
        if (maxPathLength <= 0 || maxPathsPerPatrol <= 0 || maxTeamCandidates <= 0 || maxMaterializedPlans <= 0) {
            throw new IllegalArgumentException("Representation caps must be positive");
        }
    }

    public V3RepresentationConfig(int maxPathLength, int maxPathsPerPatrol, int maxTeamCandidates,
            int maxMaterializedPlans) {
        this(maxPathLength, maxPathsPerPatrol, maxTeamCandidates, maxMaterializedPlans, false);
    }

    public static V3RepresentationConfig defaults() { return new V3RepresentationConfig(6, 192, 4096, 1024); }

    public V3RepresentationConfig withCompositionSearch(boolean enabled) {
        return new V3RepresentationConfig(maxPathLength, maxPathsPerPatrol, maxTeamCandidates,
                maxMaterializedPlans, enabled);
    }
}
