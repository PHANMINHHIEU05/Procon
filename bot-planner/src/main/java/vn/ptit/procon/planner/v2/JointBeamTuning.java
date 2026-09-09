package vn.ptit.procon.planner.v2;

import vn.ptit.procon.planner.HybridScoringConfig;

/** Per-planner tuning knobs that used to be frozen in JVM-wide static fields. */
public record JointBeamTuning(
        int stockWeight,
        boolean useExpectedStock,
        boolean fairFamilyExpansion,
        boolean stageBFairAllocation,
        boolean gateRefuelByGain,
        HybridScoringConfig scoring) {

    public JointBeamTuning {
        if (stockWeight <= 0) throw new IllegalArgumentException("Stock weight must be positive");
        java.util.Objects.requireNonNull(scoring, "Hybrid scoring configuration must not be null");
    }

    public static JointBeamTuning defaults() {
        return new JointBeamTuning(
                intSetting("procon.v2.stock_weight", "PROCON_V2_STOCK_WEIGHT", 100),
                booleanSetting("procon.v2.use_expected_stock", "PROCON_V2_USE_EXPECTED_STOCK", false),
                booleanSetting("procon.v2.fair_family_expansion", "PROCON_V2_FAIR_FAMILY_EXPANSION", false),
                booleanSetting("procon.v2.stage_b_fair_allocation", "PROCON_V2_STAGE_B_FAIR_ALLOCATION", false),
                booleanSetting("procon.v2.gate_refuel_by_gain", "PROCON_V2_GATE_REFUEL_BY_GAIN", false),
                HybridScoringConfig.defaults());
    }

    /** Production baseline before tier-specific campaign tuning. */
    public static JointBeamTuning productionDefaults() {
        return new JointBeamTuning(
                intSetting("procon.v2.stock_weight", "PROCON_V2_STOCK_WEIGHT", 2_000),
                booleanSetting("procon.v2.use_expected_stock", "PROCON_V2_USE_EXPECTED_STOCK", false),
                booleanSetting("procon.v2.fair_family_expansion", "PROCON_V2_FAIR_FAMILY_EXPANSION", true),
                booleanSetting("procon.v2.stage_b_fair_allocation", "PROCON_V2_STAGE_B_FAIR_ALLOCATION", false),
                booleanSetting("procon.v2.gate_refuel_by_gain", "PROCON_V2_GATE_REFUEL_BY_GAIN", true),
                new HybridScoringConfig(
                        intSetting("procon.hybrid.base_weight", "PROCON_HYBRID_BASE_WEIGHT", 3),
                        intSetting("procon.hybrid.coupled_weight", "PROCON_HYBRID_COUPLED_WEIGHT", 1),
                        booleanSetting("procon.prioritize_own", "PROCON_PRIORITIZE_OWN", true)));
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
