package vn.ptit.procon.planner;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/**
 * Immutable full-day opponent harvest baseline for the CURRENT day.
 *
 * <p>Computed exactly once per planning run, before any candidate plan is evaluated. It answers how
 * many collections the observed opponent collectors can make throughout the whole day if our own
 * plan consumes nothing, given their observed position and fuel, the authoritative current traffic,
 * the current stock, the day's authoritative step budget and the static Udon spots.</p>
 *
 * <p>There is deliberately no per-collector claim cap: {@link #maxCollectorCollections()} is the
 * boundedness probe that shows a single collector reaching four, five or more collections whenever
 * the map allows it.</p>
 */
public record OpponentFullDayBaseline(
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

    public OpponentFullDayBaseline {
        Objects.requireNonNull(claims, "Full-day claims must not be null");
        Objects.requireNonNull(claimsBySpot, "Full-day claims by spot must not be null");
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
            throw new IllegalArgumentException("Full-day baseline metrics must be non-negative");
        }
        if (observedNowCollections + directIntentCollections + followOnIntentCollections
                != claims.size()) {
            throw new IllegalArgumentException(
                    "Full-day commitment counts must cover every baseline collection");
        }
        if (claimsBySpot.values().stream().mapToInt(List::size).sum() != claims.size()) {
            throw new IllegalArgumentException(
                    "Full-day per-spot claims must cover every baseline collection");
        }
        if (maxCollectorCollections > claims.size()) {
            throw new IllegalArgumentException(
                    "One collector cannot hold more collections than the whole opponent team");
        }
    }

    /** Empty baseline used by every mode that does not run the M15 full-day rollout. */
    public static OpponentFullDayBaseline empty() {
        return new OpponentFullDayBaseline(List.of(), Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public int totalCollections() {
        return claims.size();
    }

    /** Retained for M12 continuity and diagnostics only; never part of the M15 ordering. */
    public int strongCollections() {
        return observedNowCollections + directIntentCollections;
    }

    public List<OpponentFullDayClaim> claimsAt(Position spot) {
        return claimsBySpot.getOrDefault(spot, List.of());
    }
}
