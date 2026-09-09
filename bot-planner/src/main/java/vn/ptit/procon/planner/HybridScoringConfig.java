package vn.ptit.procon.planner;

/** Immutable weights for the hybrid terminal comparator. */
public record HybridScoringConfig(int baseWeight, int coupledWeight, boolean prioritizeOwn) {

    public HybridScoringConfig {
        if (baseWeight < 0 || coupledWeight < 0 || baseWeight + coupledWeight <= 0) {
            throw new IllegalArgumentException("Hybrid weights must be non-negative and not both zero");
        }
    }

    public static HybridScoringConfig defaults() {
        int base = intSetting("procon.hybrid.base_weight", "PROCON_HYBRID_BASE_WEIGHT", 3);
        int coupled = intSetting("procon.hybrid.coupled_weight", "PROCON_HYBRID_COUPLED_WEIGHT", 1);
        boolean own = booleanSetting("procon.prioritize_own", "PROCON_PRIORITIZE_OWN", false);
        return new HybridScoringConfig(base, coupled, own);
    }

    private static int intSetting(String property, String environment, int fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static boolean booleanSetting(String property, String environment, boolean fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }
}
