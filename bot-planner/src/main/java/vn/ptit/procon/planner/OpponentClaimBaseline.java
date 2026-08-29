package vn.ptit.procon.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** Stock-capped counterfactual opponent collections when today's own plan consumes no stock. */
public record OpponentClaimBaseline(
        Map<Position, List<BaselineOpponentClaim>> realizableClaimsBySpot,
        int forecastClaims,
        int observedNowRealizable,
        int directIntentRealizable,
        int followOnIntentRealizable,
        int stockedSpots) {

    public OpponentClaimBaseline {
        Objects.requireNonNull(realizableClaimsBySpot, "Baseline claims must not be null");
        Map<Position, List<BaselineOpponentClaim>> copy = new LinkedHashMap<>();
        realizableClaimsBySpot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparingInt(
                        vn.ptit.procon.domain.map.Position::value)))
                .forEach(entry -> copy.put(entry.getKey(), List.copyOf(entry.getValue())));
        realizableClaimsBySpot = Collections.unmodifiableMap(copy);
        if (forecastClaims < 0 || observedNowRealizable < 0 || directIntentRealizable < 0
                || followOnIntentRealizable < 0 || stockedSpots < 0) {
            throw new IllegalArgumentException("Opponent baseline metrics must be non-negative");
        }
        if (observedNowRealizable + directIntentRealizable + followOnIntentRealizable
                != realizableClaimsBySpot.values().stream().mapToInt(List::size).sum()) {
            throw new IllegalArgumentException("Baseline commitment counts must cover realizable claims");
        }
    }

    public int strongRealizable() {
        return observedNowRealizable + directIntentRealizable;
    }

    public int totalRealizable() {
        return strongRealizable() + followOnIntentRealizable;
    }
}