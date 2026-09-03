package vn.ptit.procon.planner.oracle;

/** Finite benchmark-only limits for the independent strategic oracle. */
public record ExactOracleConfig(int maxSearchStates, long maxWallMillis, int maxReachableTargets,
        int maxSupportRoots, int maxAgents, int maxDaySteps) {
    public ExactOracleConfig {
        if (maxSearchStates <= 0 || maxWallMillis <= 0 || maxReachableTargets <= 0
                || maxSupportRoots < 0 || maxAgents <= 0 || maxDaySteps <= 0) {
            throw new IllegalArgumentException("Exact oracle caps must be positive");
        }
    }

    public static ExactOracleConfig defaults() {
        return new ExactOracleConfig(250_000, 5_000, 64, 12, 6, 60);
    }

    public static ExactOracleConfig tiny() {
        return new ExactOracleConfig(100_000, 2_000, 32, 4, 4, 24);
    }
}
