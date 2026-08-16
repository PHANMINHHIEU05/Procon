package vn.ptit.procon.planner;

import java.util.Comparator;

/** Partial-state M9.3 frontier tuple. */
record RiskAdjustedFrontierMetrics(
        int teamBrands,
        int adjustedCollectionScore,
        int rawProjectedCollections,
        int arrivalAtRiskProjected,
        int arrivalSafeProjected,
        int stronglyStaticContestedProjected,
        int optimisticHarvestPotential,
        int remainingUsefulSteps,
        int remainingFuel,
        int travelSteps,
        int depth,
        long sequence) {

    private static final Comparator<RiskAdjustedFrontierMetrics> PREFERENCE = Comparator
            .comparingInt(RiskAdjustedFrontierMetrics::teamBrands).reversed()
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedFrontierMetrics::adjustedCollectionScore).reversed())
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedFrontierMetrics::rawProjectedCollections).reversed())
            .thenComparingInt(RiskAdjustedFrontierMetrics::arrivalAtRiskProjected)
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedFrontierMetrics::arrivalSafeProjected).reversed())
            .thenComparingInt(RiskAdjustedFrontierMetrics::stronglyStaticContestedProjected)
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedFrontierMetrics::optimisticHarvestPotential).reversed())
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedFrontierMetrics::remainingUsefulSteps).reversed())
            .thenComparing(Comparator.comparingInt(
                    RiskAdjustedFrontierMetrics::remainingFuel).reversed())
            .thenComparingInt(RiskAdjustedFrontierMetrics::travelSteps)
            .thenComparing(Comparator.comparingInt(RiskAdjustedFrontierMetrics::depth).reversed())
            .thenComparingLong(RiskAdjustedFrontierMetrics::sequence);

    static Comparator<RiskAdjustedFrontierMetrics> preference() {
        return PREFERENCE;
    }
}