package vn.ptit.procon.planner;

import java.util.Comparator;

/** Cheap immutable partial-state tuple used by the M9.2 arrival-aware contention frontier. */
record ArrivalContentionFrontierMetrics(
        int teamBrands,
        int arrivalSafeProjected,
        int arrivalTiedProjected,
        int arrivalAtRiskProjected,
        int staticSafeProjected,
        int projectedTotal,
        int optimisticHarvestPotential,
        int remainingUsefulSteps,
        int remainingFuel,
        int travelSteps,
        int depth,
        long sequence) {

    private static final Comparator<ArrivalContentionFrontierMetrics> PREFERENCE = Comparator
            .comparingInt(ArrivalContentionFrontierMetrics::teamBrands).reversed()
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionFrontierMetrics::projectedTotal).reversed())
            .thenComparingInt(ArrivalContentionFrontierMetrics::arrivalAtRiskProjected)
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionFrontierMetrics::arrivalSafeProjected).reversed())
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionFrontierMetrics::staticSafeProjected).reversed())
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionFrontierMetrics::optimisticHarvestPotential).reversed())
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionFrontierMetrics::remainingUsefulSteps).reversed())
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionFrontierMetrics::remainingFuel).reversed())
            .thenComparingInt(ArrivalContentionFrontierMetrics::travelSteps)
            .thenComparing(Comparator.comparingInt(
                    ArrivalContentionFrontierMetrics::depth).reversed())
            .thenComparingLong(ArrivalContentionFrontierMetrics::sequence);

    static Comparator<ArrivalContentionFrontierMetrics> preference() {
        return PREFERENCE;
    }
}
