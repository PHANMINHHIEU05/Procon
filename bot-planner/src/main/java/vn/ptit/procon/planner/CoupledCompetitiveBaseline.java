package vn.ptit.procon.planner;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/**
 * M16 immutable no-own-plan full-day opponent baseline for the CURRENT day.
 *
 * <p>Same role as the M15 {@link OpponentFullDayBaseline}: computed exactly once per planning run,
 * before any candidate plan, it answers how many collections the observed collectors can make over
 * the whole day when our plan consumes nothing. It is a separate record because M16 selects opponent
 * targets with the bounded adversarial best response rather than the M15 arrival-first ranking, and
 * the baseline has to be produced by the SAME selection rule the coupled rollout uses. Otherwise
 * {@link CoupledCompetitiveRolloutResult#opponentCollectionsRemovedVsBaseline()} would compare two
 * different opponent models rather than the effect of our plan.</p>
 *
 * <p>M15 keeps its own baseline untouched, so both remain available side by side for A/B.</p>
 */
public record CoupledCompetitiveBaseline(
        List<OpponentFullDayClaim> claims,
        Map<Position, List<OpponentFullDayClaim>> claimsBySpot,
        int observedNowCollections,
        int directIntentCollections,
        int followOnIntentCollections,
        int collectorCount,
        int stockedSpots,
        int stepBudget,
        int rolloutEvents,
        int maxCollectorCollections,
        int routeCostCacheEntries,
        int pathfindingExecutions) {

    public CoupledCompetitiveBaseline {
        Objects.requireNonNull(claims, "Coupled baseline claims must not be null");
        Objects.requireNonNull(claimsBySpot, "Coupled baseline claims by spot must not be null");
        claims = List.copyOf(claims);
        Map<Position, List<OpponentFullDayClaim>> copy = new LinkedHashMap<>();
        claimsBySpot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Position::value)))
                .forEach(entry -> copy.put(entry.getKey(), List.copyOf(entry.getValue())));
        claimsBySpot = Collections.unmodifiableMap(copy);
        if (observedNowCollections < 0 || directIntentCollections < 0 || followOnIntentCollections < 0
                || collectorCount < 0 || stockedSpots < 0 || stepBudget < 0 || rolloutEvents < 0
                || maxCollectorCollections < 0 || routeCostCacheEntries < 0
                || pathfindingExecutions < 0) {
            throw new IllegalArgumentException("Coupled baseline metrics must be non-negative");
        }
        if (observedNowCollections + directIntentCollections + followOnIntentCollections
                != claims.size()) {
            throw new IllegalArgumentException(
                    "Coupled baseline commitment counts must cover every baseline collection");
        }
        if (claimsBySpot.values().stream().mapToInt(List::size).sum() != claims.size()) {
            throw new IllegalArgumentException(
                    "Coupled baseline per-spot claims must cover every baseline collection");
        }
        if (maxCollectorCollections > claims.size()) {
            throw new IllegalArgumentException(
                    "One collector cannot hold more collections than the whole opponent team");
        }
    }

    /** Empty baseline used by every mode that does not run the M16 coupled rollout. */
    public static CoupledCompetitiveBaseline empty() {
        return new CoupledCompetitiveBaseline(List.of(), Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public int totalCollections() {
        return claims.size();
    }

    /** Retained for M12 continuity and diagnostics only; never part of the M16 ordering. */
    public int strongCollections() {
        return observedNowCollections + directIntentCollections;
    }

    public List<OpponentFullDayClaim> claimsAt(Position spot) {
        return claimsBySpot.getOrDefault(spot, List.of());
    }
}
