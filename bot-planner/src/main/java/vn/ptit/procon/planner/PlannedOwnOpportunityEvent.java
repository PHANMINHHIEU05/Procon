package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * M16: one immutable physical PATROL presence on a Udon spot inside a complete own plan.
 *
 * <p>This is deliberately NOT a collection. It says only "our PATROL is physically scheduled to stand
 * on this spot at this step, and this is the first time that PATROL stands there today", which is
 * exactly the precondition the day simulator checks before it looks at the stock at all. Whether the
 * event turns into a collection is decided later, on the shared coupled stock timeline, because an
 * adaptive opponent may have emptied the spot before we arrive.</p>
 *
 * <p>{@code stableOrdinal} is the index of this event in the deterministic global order
 * {@code (arrivalStep, own agent ordinal)}, which is exactly the order the simulator resolves
 * collections in. It therefore fixes own-versus-own precedence without any tie-breaking heuristic.</p>
 */
public record PlannedOwnOpportunityEvent(
        AgentId agentId,
        Position spot,
        int arrivalStep,
        int stableOrdinal) {

    public PlannedOwnOpportunityEvent {
        Objects.requireNonNull(agentId, "Planned own opportunity agent must not be null");
        Objects.requireNonNull(spot, "Planned own opportunity spot must not be null");
        if (arrivalStep < 0 || stableOrdinal < 0) {
            throw new IllegalArgumentException(
                    "Planned own opportunity ordering values must be non-negative");
        }
    }

    /** Agent plus spot; the once-per-spot-per-PATROL identity the simulator enforces. */
    public String agentSpotKey() {
        return agentId.value() + ":" + spot.value();
    }
}
