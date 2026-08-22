package vn.ptit.procon.planner;

import java.util.Comparator;

/**
 * Partial-state M12 frontier tuple.
 *
 * <p>Mirrors {@link CommitmentAwarePlanEvaluation} key for key so the frontier ordering and the
 * complete-plan objective cannot disagree about what "better" means. The M10 tuple is left
 * untouched for the shipped modes.</p>
 */
record CommitmentAwareFrontierMetrics(
        int commitmentRealizableBrands,
        int adjustedScore,
        int commitmentRealizableCollections,
        int rawCollections,
        int localBrands,
        int hardClaimedFirst,
        int directIntentBefore,
        int tieCollections,
        int followOnIntentBefore,
        int optimisticHarvestPotential,
        int remainingUsefulSteps,
        int remainingFuel,
        int travelSteps,
        int depth,
        long sequence) {

    private static final Comparator<CommitmentAwareFrontierMetrics> PREFERENCE = Comparator
            .comparingInt(CommitmentAwareFrontierMetrics::commitmentRealizableBrands).reversed()
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareFrontierMetrics::adjustedScore).reversed())
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareFrontierMetrics::commitmentRealizableCollections).reversed())
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareFrontierMetrics::rawCollections).reversed())
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareFrontierMetrics::localBrands).reversed())
            .thenComparingInt(CommitmentAwareFrontierMetrics::hardClaimedFirst)
            .thenComparingInt(CommitmentAwareFrontierMetrics::directIntentBefore)
            .thenComparingInt(CommitmentAwareFrontierMetrics::tieCollections)
            .thenComparingInt(CommitmentAwareFrontierMetrics::followOnIntentBefore)
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareFrontierMetrics::optimisticHarvestPotential).reversed())
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareFrontierMetrics::remainingUsefulSteps).reversed())
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareFrontierMetrics::remainingFuel).reversed())
            .thenComparingInt(CommitmentAwareFrontierMetrics::travelSteps)
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareFrontierMetrics::depth).reversed())
            .thenComparingLong(CommitmentAwareFrontierMetrics::sequence);

    static Comparator<CommitmentAwareFrontierMetrics> preference() {
        return PREFERENCE;
    }
}
