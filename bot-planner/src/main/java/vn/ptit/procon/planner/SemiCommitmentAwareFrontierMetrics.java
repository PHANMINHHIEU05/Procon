package vn.ptit.procon.planner;

import java.util.Comparator;

/**
 * Partial-state M12.1 frontier tuple.
 *
 * <p>Mirrors {@link SemiCommitmentAwarePlanEvaluation} key for key so the frontier ordering and the
 * complete-plan objective cannot disagree about what "better" means. The M10 and M12 tuples are left
 * untouched for the shipped modes.</p>
 */
record SemiCommitmentAwareFrontierMetrics(
        int semiCommitmentRealizableBrands,
        int adjustedScore,
        int semiCommitmentRealizableCollections,
        int rawCollections,
        int localBrands,
        int hardClaimedFirst,
        int semiClaimedFirst,
        int directIntentBefore,
        int tieCollections,
        int followOnIntentBefore,
        int optimisticHarvestPotential,
        int remainingUsefulSteps,
        int remainingFuel,
        int travelSteps,
        int depth,
        long sequence) {

    private static final Comparator<SemiCommitmentAwareFrontierMetrics> PREFERENCE = Comparator
            .comparingInt(SemiCommitmentAwareFrontierMetrics::semiCommitmentRealizableBrands)
            .reversed()
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareFrontierMetrics::adjustedScore).reversed())
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareFrontierMetrics::semiCommitmentRealizableCollections)
                    .reversed())
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareFrontierMetrics::rawCollections).reversed())
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareFrontierMetrics::localBrands).reversed())
            .thenComparingInt(SemiCommitmentAwareFrontierMetrics::hardClaimedFirst)
            .thenComparingInt(SemiCommitmentAwareFrontierMetrics::semiClaimedFirst)
            .thenComparingInt(SemiCommitmentAwareFrontierMetrics::directIntentBefore)
            .thenComparingInt(SemiCommitmentAwareFrontierMetrics::tieCollections)
            .thenComparingInt(SemiCommitmentAwareFrontierMetrics::followOnIntentBefore)
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareFrontierMetrics::optimisticHarvestPotential).reversed())
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareFrontierMetrics::remainingUsefulSteps).reversed())
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareFrontierMetrics::remainingFuel).reversed())
            .thenComparingInt(SemiCommitmentAwareFrontierMetrics::travelSteps)
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareFrontierMetrics::depth).reversed())
            .thenComparingLong(SemiCommitmentAwareFrontierMetrics::sequence);

    static Comparator<SemiCommitmentAwareFrontierMetrics> preference() {
        return PREFERENCE;
    }
}
