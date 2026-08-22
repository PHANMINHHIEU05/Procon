package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * Lexicographic M12.1 candidate guidance aligned with semi-commitment-adjusted plan value.
 *
 * <p>Structurally the M12 ordering with {@code semiClaimedFirst} inserted directly after
 * {@code hardClaimedFirst} in both phases: a candidate whose gain comes from a portion the bounded
 * direct reservation has already taken must lose to one whose gain survives it. Left as a separate
 * record so the M12 and M11 candidate comparators keep reading exactly the tuples they shipped
 * with.</p>
 */
record SemiCommitmentAwareCandidateMetrics(
        boolean teamNewSemiCommitmentRealizableBrand,
        boolean teamNewLocalBrand,
        int adjustedScore,
        int semiCommitmentRealizableGain,
        int semiCommitmentRealizableBrandGain,
        int rawGain,
        int hardClaimedFirst,
        int semiClaimedFirst,
        int directIntentBefore,
        int followOnIntentBefore,
        int tieCollections,
        int routeSteps,
        int routeFuel,
        int resultingFuel,
        Position targetPosition,
        AgentId patrolAgentId) {

    private static final Comparator<SemiCommitmentAwareCandidateMetrics> DENSITY =
            SemiCommitmentAwareCandidateMetrics::compareDensity;

    private static final Comparator<SemiCommitmentAwareCandidateMetrics> COVERAGE = Comparator
            .comparing(SemiCommitmentAwareCandidateMetrics::teamNewSemiCommitmentRealizableBrand)
            .reversed()
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareCandidateMetrics::adjustedScore).reversed())
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareCandidateMetrics::semiCommitmentRealizableGain).reversed())
            .thenComparing(Comparator.comparing(
                    SemiCommitmentAwareCandidateMetrics::teamNewLocalBrand).reversed())
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareCandidateMetrics::rawGain).reversed())
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::hardClaimedFirst)
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::semiClaimedFirst)
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::directIntentBefore)
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::routeSteps)
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::routeFuel)
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    private static final Comparator<SemiCommitmentAwareCandidateMetrics> HARVEST = Comparator
            .comparingInt(SemiCommitmentAwareCandidateMetrics::adjustedScore).reversed()
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareCandidateMetrics::semiCommitmentRealizableGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareCandidateMetrics::semiCommitmentRealizableBrandGain)
                    .reversed())
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareCandidateMetrics::rawGain).reversed())
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::hardClaimedFirst)
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::semiClaimedFirst)
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::directIntentBefore)
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::tieCollections)
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::followOnIntentBefore)
            .thenComparing(DENSITY)
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::routeSteps)
            .thenComparingInt(SemiCommitmentAwareCandidateMetrics::routeFuel)
            .thenComparing(Comparator.comparingInt(
                    SemiCommitmentAwareCandidateMetrics::resultingFuel).reversed())
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    SemiCommitmentAwareCandidateMetrics {
        Objects.requireNonNull(targetPosition, "Target position must not be null");
        Objects.requireNonNull(patrolAgentId, "PATROL agent ID must not be null");
        if (semiCommitmentRealizableBrandGain < 0 || semiCommitmentRealizableGain < 0) {
            throw new IllegalArgumentException(
                    "Semi-commitment-realizable candidate gains must be non-negative");
        }
        if (teamNewSemiCommitmentRealizableBrand != semiCommitmentRealizableBrandGain > 0) {
            throw new IllegalArgumentException(
                    "New realizable brand flag must agree with the realizable brand gain");
        }
        if (semiCommitmentRealizableBrandGain > semiCommitmentRealizableGain) {
            throw new IllegalArgumentException(
                    "Realizable brand gain cannot exceed realizable collection gain");
        }
    }

    static Comparator<SemiCommitmentAwareCandidateMetrics> coveragePreference() {
        return COVERAGE;
    }

    static Comparator<SemiCommitmentAwareCandidateMetrics> harvestPreference() {
        return HARVEST;
    }

    /** Semi-realizable gain per travelled step, compared without floating point. */
    private static int compareDensity(
            SemiCommitmentAwareCandidateMetrics first,
            SemiCommitmentAwareCandidateMetrics second) {
        if (first.routeSteps == 0 || second.routeSteps == 0) {
            if (first.routeSteps == second.routeSteps) {
                return 0;
            }
            return first.routeSteps == 0 ? -1 : 1;
        }
        long firstScaled = (long) first.semiCommitmentRealizableGain * second.routeSteps;
        long secondScaled = (long) second.semiCommitmentRealizableGain * first.routeSteps;
        return Long.compare(secondScaled, firstScaled);
    }
}
