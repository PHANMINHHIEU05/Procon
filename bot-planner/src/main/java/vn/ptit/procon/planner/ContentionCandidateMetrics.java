package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** Lexicographic M9 search guidance; complete plan evaluation remains unchanged. */
public record ContentionCandidateMetrics(
        boolean teamNewBrand,
        int routeCollectionGain,
        int safeProjectedCollections,
        int tiedProjectedCollections,
        int contestedProjectedCollections,
        int stronglyContestedCollections,
        int routeSteps,
        int routeFuel,
        int resultingFuel,
        Position targetPosition,
        AgentId patrolAgentId) {

    private static final Comparator<ContentionCandidateMetrics> DENSITY =
            ContentionCandidateMetrics::compareDensity;

    private static final Comparator<ContentionCandidateMetrics> COVERAGE = Comparator
            .comparing(ContentionCandidateMetrics::teamNewBrand).reversed()
            .thenComparing(Comparator.comparingInt(
                    ContentionCandidateMetrics::safeProjectedCollections).reversed())
            .thenComparing(Comparator.comparingInt(
                    ContentionCandidateMetrics::routeCollectionGain).reversed())
            .thenComparing(Comparator.comparingInt(
                    ContentionCandidateMetrics::uncontestedCollections).reversed())
            .thenComparing(DENSITY)
            .thenComparingInt(ContentionCandidateMetrics::routeSteps)
            .thenComparingInt(ContentionCandidateMetrics::routeFuel)
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    private static final Comparator<ContentionCandidateMetrics> HARVEST = Comparator
            .comparingInt(ContentionCandidateMetrics::uncontestedCollections).reversed()
            .thenComparing(Comparator.comparingInt(
                    ContentionCandidateMetrics::routeCollectionGain).reversed())
            .thenComparingInt(ContentionCandidateMetrics::stronglyContestedCollections)
            .thenComparing(DENSITY)
            .thenComparingInt(ContentionCandidateMetrics::routeSteps)
            .thenComparingInt(ContentionCandidateMetrics::routeFuel)
            .thenComparing(Comparator.comparingInt(
                    ContentionCandidateMetrics::resultingFuel).reversed())
            .thenComparingInt(candidate -> candidate.targetPosition.value())
            .thenComparingInt(candidate -> candidate.patrolAgentId.value());

    public ContentionCandidateMetrics {
        Objects.requireNonNull(targetPosition, "Target position must not be null");
        Objects.requireNonNull(patrolAgentId, "PATROL agent ID must not be null");
    }

    public int uncontestedCollections() {
        return safeProjectedCollections + tiedProjectedCollections;
    }

    public static Comparator<ContentionCandidateMetrics> coveragePreference() {
        return COVERAGE;
    }

    public static Comparator<ContentionCandidateMetrics> harvestPreference() {
        return HARVEST;
    }

    public static Comparator<ContentionCandidateMetrics> densityPreference() {
        return DENSITY;
    }

    private static int compareDensity(
            ContentionCandidateMetrics first, ContentionCandidateMetrics second) {
        if (first.routeSteps == 0 || second.routeSteps == 0) {
            if (first.routeSteps == second.routeSteps) {
                return 0;
            }
            return first.routeSteps == 0 ? -1 : 1;
        }
        long firstScaled = (long) first.routeCollectionGain * second.routeSteps;
        long secondScaled = (long) second.routeCollectionGain * first.routeSteps;
        return Long.compare(secondScaled, firstScaled);
    }
}