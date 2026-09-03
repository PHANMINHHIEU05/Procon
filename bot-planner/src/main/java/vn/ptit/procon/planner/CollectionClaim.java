package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** One logical unit of stocked collection capacity assigned to one physical arrival. */
public record CollectionClaim(
        Position spot,
        int claimOrdinal,
        AgentId assignedPatrol,
        int plannedArrivalStep) {

    public CollectionClaim {
        Objects.requireNonNull(spot, "Claim spot must not be null");
        Objects.requireNonNull(assignedPatrol, "Claim patrol must not be null");
        if (claimOrdinal < 0 || plannedArrivalStep < 0) {
            throw new IllegalArgumentException("Claim ordinal and arrival must be non-negative");
        }
    }

    public String signature() {
        return spot.value() + "#" + claimOrdinal + "@" + assignedPatrol.value()
                + ":" + plannedArrivalStep;
    }
}
