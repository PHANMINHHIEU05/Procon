package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * M16-only top-K guidance; the complete-plan coupled competitive rollout stays authoritative.
 *
 * <p>{@code coupledContestGain} is read from the immutable no-own-plan coupled baseline rather than from
 * a three-target prefix, so a spot deep inside an opponent's whole-day adversarial route still scores
 * and can survive candidate pruning. Old modes keep their own candidate ordering untouched.</p>
 *
 * <p>This is guidance only. No candidate ordering here decides a plan: a candidate that looks strong
 * can still lose its collection entirely once the coupled rollout resolves the shared timeline.</p>
 */
record CoupledCompetitiveCandidateMetrics(
        boolean newOwnSemiBrand,
        int ownSemiCollectionGain,
        int coupledContestGain,
        int strongContestGain,
        int semiScoreGain,
        int rawGain,
        int routeSteps,
        int routeFuel,
        int resultingFuel,
        Position target,
        AgentId patrol) {

    private static final Comparator<CoupledCompetitiveCandidateMetrics> COVERAGE = Comparator
            .comparing(CoupledCompetitiveCandidateMetrics::newOwnSemiBrand).reversed()
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::ownSemiCollectionGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::coupledContestGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::resultingFuel).reversed())
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::strongContestGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::semiScoreGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::rawGain).reversed())
            .thenComparingInt(CoupledCompetitiveCandidateMetrics::routeSteps)
            .thenComparingInt(CoupledCompetitiveCandidateMetrics::routeFuel)
            .thenComparingInt(value -> value.target.value())
            .thenComparingInt(value -> value.patrol.value());

    private static final Comparator<CoupledCompetitiveCandidateMetrics> HARVEST = Comparator
            .comparingInt(CoupledCompetitiveCandidateMetrics::projectedContestGain).reversed()
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::ownSemiCollectionGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::coupledContestGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::resultingFuel).reversed())
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::strongContestGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::semiScoreGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    CoupledCompetitiveCandidateMetrics::rawGain).reversed())
            .thenComparingInt(CoupledCompetitiveCandidateMetrics::routeSteps)
            .thenComparingInt(CoupledCompetitiveCandidateMetrics::routeFuel)
            .thenComparingInt(value -> value.target.value())
            .thenComparingInt(value -> value.patrol.value());

    CoupledCompetitiveCandidateMetrics {
        Objects.requireNonNull(target, "Candidate target must not be null");
        Objects.requireNonNull(patrol, "Candidate PATROL must not be null");
        if (ownSemiCollectionGain < 0 || coupledContestGain < 0 || strongContestGain < 0
                || semiScoreGain < 0 || rawGain < 0 || routeSteps < 0 || routeFuel < 0
                || resultingFuel < 0) {
            throw new IllegalArgumentException(
                    "Coupled competitive candidate metrics must be non-negative");
        }
        if (strongContestGain > coupledContestGain) {
            throw new IllegalArgumentException(
                    "Strong contested collections cannot exceed the contested collections holding them");
        }
    }

    int projectedContestGain() {
        return ownSemiCollectionGain + coupledContestGain;
    }

    static Comparator<CoupledCompetitiveCandidateMetrics> coveragePreference() {
        return COVERAGE;
    }

    static Comparator<CoupledCompetitiveCandidateMetrics> harvestPreference() {
        return HARVEST;
    }
}
