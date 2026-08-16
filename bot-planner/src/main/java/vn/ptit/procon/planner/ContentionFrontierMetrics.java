package vn.ptit.procon.planner;

import java.util.Comparator;

/** Cheap immutable partial-state tuple used by the M9 contention frontier. */
record ContentionFrontierMetrics(
        int teamBrands,
        int safeProjected,
        int projectedTotal,
        int optimisticHarvestPotential,
        int remainingUsefulSteps,
        int remainingFuel,
        int travelSteps,
        int depth,
        long sequence) {

    private static final Comparator<ContentionFrontierMetrics> PREFERENCE = Comparator
            .comparingInt(ContentionFrontierMetrics::teamBrands).reversed()
            .thenComparing(Comparator.comparingInt(
                    ContentionFrontierMetrics::safeProjected).reversed())
            .thenComparing(Comparator.comparingInt(
                    ContentionFrontierMetrics::projectedTotal).reversed())
            .thenComparing(Comparator.comparingInt(
                    ContentionFrontierMetrics::optimisticHarvestPotential).reversed())
            .thenComparing(Comparator.comparingInt(
                    ContentionFrontierMetrics::remainingUsefulSteps).reversed())
            .thenComparing(Comparator.comparingInt(
                    ContentionFrontierMetrics::remainingFuel).reversed())
            .thenComparingInt(ContentionFrontierMetrics::travelSteps)
            .thenComparing(Comparator.comparingInt(
                    ContentionFrontierMetrics::depth).reversed())
            .thenComparingLong(ContentionFrontierMetrics::sequence);

    static Comparator<ContentionFrontierMetrics> preference() {
        return PREFERENCE;
    }
}