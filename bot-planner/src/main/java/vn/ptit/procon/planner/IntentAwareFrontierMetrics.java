package vn.ptit.procon.planner;

import java.util.Comparator;

/**
 * Partial-state M10 frontier tuple.
 *
 * <p>Mirrors {@link IntentAwarePlanEvaluation}: forecast-realizable brand coverage
 * leads, local simulator brand coverage stays visible below raw collections.</p>
 */
record IntentAwareFrontierMetrics(
        int forecastRealizableBrands,
        int adjustedScore,
        int forecastRealizableCollections,
        int rawCollections,
        int localBrands,
        int likelyClaimedFirst,
        int tieCollections,
        int optimisticHarvestPotential,
        int remainingUsefulSteps,
        int remainingFuel,
        int travelSteps,
        int depth,
        long sequence) {

    private static final Comparator<IntentAwareFrontierMetrics> PREFERENCE = Comparator
            .comparingInt(IntentAwareFrontierMetrics::forecastRealizableBrands).reversed()
            .thenComparing(Comparator.comparingInt(IntentAwareFrontierMetrics::adjustedScore).reversed())
            .thenComparing(Comparator.comparingInt(
                    IntentAwareFrontierMetrics::forecastRealizableCollections).reversed())
            .thenComparing(Comparator.comparingInt(IntentAwareFrontierMetrics::rawCollections).reversed())
            .thenComparing(Comparator.comparingInt(IntentAwareFrontierMetrics::localBrands).reversed())
            .thenComparingInt(IntentAwareFrontierMetrics::likelyClaimedFirst)
            .thenComparingInt(IntentAwareFrontierMetrics::tieCollections)
            .thenComparing(Comparator.comparingInt(
                    IntentAwareFrontierMetrics::optimisticHarvestPotential).reversed())
            .thenComparing(Comparator.comparingInt(
                    IntentAwareFrontierMetrics::remainingUsefulSteps).reversed())
            .thenComparing(Comparator.comparingInt(IntentAwareFrontierMetrics::remainingFuel).reversed())
            .thenComparingInt(IntentAwareFrontierMetrics::travelSteps)
            .thenComparing(Comparator.comparingInt(IntentAwareFrontierMetrics::depth).reversed())
            .thenComparingLong(IntentAwareFrontierMetrics::sequence);

    static Comparator<IntentAwareFrontierMetrics> preference() {
        return PREFERENCE;
    }
}
