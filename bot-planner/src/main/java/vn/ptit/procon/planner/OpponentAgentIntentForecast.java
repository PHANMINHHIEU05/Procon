package vn.ptit.procon.planner;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/**
 * Capacity-constrained forecast for one observed agent.
 *
 * <p>{@code collectionEligible} separates the observation from the planner
 * collection policy. An ineligible agent stays visible for diagnostics but never
 * carries collector intent targets, so it can never claim forecast Udon stock.</p>
 */
public record OpponentAgentIntentForecast(
        int agentIndex,
        Position observedPosition,
        int rawKind,
        int observedFuel,
        int physicallyReachableSpots,
        boolean collectionEligible,
        List<OpponentTargetIntent> targets) {

    public OpponentAgentIntentForecast {
        if (agentIndex < 0 || physicallyReachableSpots < 0) {
            throw new IllegalArgumentException("Opponent agent forecast metrics must be non-negative");
        }
        Objects.requireNonNull(observedPosition, "Observed position must not be null");
        targets = List.copyOf(Objects.requireNonNull(targets, "Intent targets must not be null"));
        if (!collectionEligible && !targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "Collection-ineligible agents must not carry collector intent targets");
        }
    }
}
