package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * Lexicographic M12 candidate guidance aligned with commitment-adjusted plan value.
 *
 * <p>Coverage ordering leads with a new commitment-realizable team brand, so a locally new brand
 * whose only projected source is already held by an observed collector cannot dominate — but a brand
 * merely contested by future intent still counts, unlike under M10. Harvest ordering keeps
 * commitment-adjusted value first and protects the realizable brand count above raw quantity.</p>
 *
 * <p>The M11 comparator in {@link IntentAwareCandidateMetrics} is untouched.</p>
 */
record CommitmentAwareCandidateMetrics(
        boolean teamNewCommitmentRealizableBrand,
        boolean teamNewLocalBrand,
        int adjustedScore,
        int commitmentRealizableGain,
        int commitmentRealizableBrandGain,
        int rawGain,
        int hardClaimedFirst,
        int directIntentBefore,
        int followOnIntentBefore,
        int tieCollections,
        int routeSteps,
        int routeFuel,
        int resultingFuel,
        Position targetPosition,
        AgentId patrolAgentId) {

    private static final Comparator<CommitmentAwareCandidateMetrics> DENSITY =
            CommitmentAwareCandidateMetrics::compareDensity;

    private static final Comparator<CommitmentAwareCandidateMetrics> COVERAGE = Comparator
            .comparing(CommitmentAwareCandidateMetrics::teamNewCommitmentRealizableBrand).reversed()
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareCandidateMetrics::adjustedScore).reversed())
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareCandidateMetrics::commitmentRealizableGain).reversed())
            .thenComparing(Comparator.comparing(
                    CommitmentAwareCandidateMetrics::teamNewLocalBrand).reversed())
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareCandidateMetrics::rawGain).reversed())
            .thenComparingInt(CommitmentAwareCandidateMetrics::hardClaimedFirst)
            .thenComparingInt(CommitmentAwareCandidateMetrics::directIntentBefore)
            .thenComparingInt(CommitmentAwareCandidateMetrics::routeSteps)
            .thenComparingInt(CommitmentAwareCandidateMetrics::routeFuel)
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    private static final Comparator<CommitmentAwareCandidateMetrics> HARVEST = Comparator
            .comparingInt(CommitmentAwareCandidateMetrics::adjustedScore).reversed()
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareCandidateMetrics::commitmentRealizableGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareCandidateMetrics::commitmentRealizableBrandGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareCandidateMetrics::rawGain).reversed())
            .thenComparingInt(CommitmentAwareCandidateMetrics::hardClaimedFirst)
            .thenComparingInt(CommitmentAwareCandidateMetrics::directIntentBefore)
            .thenComparingInt(CommitmentAwareCandidateMetrics::tieCollections)
            .thenComparingInt(CommitmentAwareCandidateMetrics::followOnIntentBefore)
            .thenComparing(DENSITY)
            .thenComparingInt(CommitmentAwareCandidateMetrics::routeSteps)
            .thenComparingInt(CommitmentAwareCandidateMetrics::routeFuel)
            .thenComparing(Comparator.comparingInt(
                    CommitmentAwareCandidateMetrics::resultingFuel).reversed())
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    CommitmentAwareCandidateMetrics {
        Objects.requireNonNull(targetPosition, "Target position must not be null");
        Objects.requireNonNull(patrolAgentId, "PATROL agent ID must not be null");
        if (commitmentRealizableBrandGain < 0 || commitmentRealizableGain < 0) {
            throw new IllegalArgumentException(
                    "Commitment-realizable candidate gains must be non-negative");
        }
        if (teamNewCommitmentRealizableBrand != commitmentRealizableBrandGain > 0) {
            throw new IllegalArgumentException(
                    "New realizable brand flag must agree with the realizable brand gain");
        }
        if (commitmentRealizableBrandGain > commitmentRealizableGain) {
            throw new IllegalArgumentException(
                    "Realizable brand gain cannot exceed realizable collection gain");
        }
    }

    static Comparator<CommitmentAwareCandidateMetrics> coveragePreference() {
        return COVERAGE;
    }

    static Comparator<CommitmentAwareCandidateMetrics> harvestPreference() {
        return HARVEST;
    }

    private static int compareDensity(
            CommitmentAwareCandidateMetrics first, CommitmentAwareCandidateMetrics second) {
        if (first.routeSteps == 0 || second.routeSteps == 0) {
            if (first.routeSteps == second.routeSteps) {
                return 0;
            }
            return first.routeSteps == 0 ? -1 : 1;
        }
        long firstScaled = (long) first.commitmentRealizableGain * second.routeSteps;
        long secondScaled = (long) second.commitmentRealizableGain * first.routeSteps;
        return Long.compare(secondScaled, firstScaled);
    }
}
