package vn.ptit.procon.planner;

/** Deterministic work limits for one bounded anytime planning call. */
public record AnytimePlannerConfig(
        int maxExpandedStates,
        int maxFrontierSize,
        int topCandidatesPerState) {

    public static final int DEFAULT_MAX_EXPANDED_STATES = 64;
    public static final int DEFAULT_MAX_FRONTIER_SIZE = 48;
    public static final int DEFAULT_TOP_CANDIDATES_PER_STATE = 4;

    public AnytimePlannerConfig {
        if (maxExpandedStates < 0) {
            throw new IllegalArgumentException("Maximum expanded states must be non-negative");
        }
        if (maxFrontierSize <= 0 || topCandidatesPerState <= 0) {
            throw new IllegalArgumentException("Frontier size and top candidate count must be positive");
        }
    }

    public static AnytimePlannerConfig defaults() {
        return new AnytimePlannerConfig(
                DEFAULT_MAX_EXPANDED_STATES,
                DEFAULT_MAX_FRONTIER_SIZE,
                DEFAULT_TOP_CANDIDATES_PER_STATE);
    }
}