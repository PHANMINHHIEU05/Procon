package vn.ptit.procon.planner;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** Capacity-constrained forecast for one observed agent. */
public record OpponentAgentIntentForecast(
        int agentIndex,
        Position observedPosition,
        int rawKind,
        int observedFuel,
        int physicallyReachableSpots,
        List<OpponentTargetIntent> targets) {

    public OpponentAgentIntentForecast {
        if (agentIndex < 0 || physicallyReachableSpots < 0) {
            throw new IllegalArgumentException("Opponent agent forecast metrics must be non-negative");
        }
        Objects.requireNonNull(observedPosition, "Observed position must not be null");
        targets = List.copyOf(Objects.requireNonNull(targets, "Intent targets must not be null"));
    }
}