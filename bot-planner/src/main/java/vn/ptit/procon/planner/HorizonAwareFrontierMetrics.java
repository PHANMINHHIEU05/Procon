package vn.ptit.procon.planner;

import java.util.Comparator;

/** Partial-state M13 tuple with M12.1 primary value above terminal readiness. */
record HorizonAwareFrontierMetrics(
        int semiBrands,
        int semiScore,
        int semiCollections,
        int futureReadyPatrols,
        int futureReachableBrands,
        int minimumPatrolReadiness,
        int futureReachableSpots,
        int rawCollections,
        int localBrands,
        int hardClaimedFirst,
        int semiClaimedFirst,
        int directIntentBefore,
        int tieCollections,
        int followOnIntentBefore,
        int finalPatrolFuel,
        int travelSteps,
        int depth,
        long sequence) {

    private static final Comparator<HorizonAwareFrontierMetrics> PREFERENCE = Comparator
            .comparingInt(HorizonAwareFrontierMetrics::semiBrands).reversed()
            .thenComparing(Comparator.comparingInt(HorizonAwareFrontierMetrics::semiScore).reversed())
            .thenComparing(Comparator.comparingInt(HorizonAwareFrontierMetrics::semiCollections).reversed())
            .thenComparing(Comparator.comparingInt(HorizonAwareFrontierMetrics::futureReadyPatrols).reversed())
            .thenComparing(Comparator.comparingInt(HorizonAwareFrontierMetrics::futureReachableBrands).reversed())
            .thenComparing(Comparator.comparingInt(HorizonAwareFrontierMetrics::minimumPatrolReadiness).reversed())
            .thenComparing(Comparator.comparingInt(HorizonAwareFrontierMetrics::futureReachableSpots).reversed())
            .thenComparing(Comparator.comparingInt(HorizonAwareFrontierMetrics::rawCollections).reversed())
            .thenComparing(Comparator.comparingInt(HorizonAwareFrontierMetrics::localBrands).reversed())
            .thenComparingInt(HorizonAwareFrontierMetrics::hardClaimedFirst)
            .thenComparingInt(HorizonAwareFrontierMetrics::semiClaimedFirst)
            .thenComparingInt(HorizonAwareFrontierMetrics::directIntentBefore)
            .thenComparingInt(HorizonAwareFrontierMetrics::tieCollections)
            .thenComparingInt(HorizonAwareFrontierMetrics::followOnIntentBefore)
            .thenComparing(Comparator.comparingInt(HorizonAwareFrontierMetrics::finalPatrolFuel).reversed())
            .thenComparingInt(HorizonAwareFrontierMetrics::travelSteps)
            .thenComparing(Comparator.comparingInt(HorizonAwareFrontierMetrics::depth).reversed())
            .thenComparingLong(HorizonAwareFrontierMetrics::sequence);

    static Comparator<HorizonAwareFrontierMetrics> preference() {
        return PREFERENCE;
    }
}