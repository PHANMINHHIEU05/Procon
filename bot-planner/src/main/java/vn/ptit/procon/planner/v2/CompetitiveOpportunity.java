package vn.ptit.procon.planner.v2;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/** Observable, deliberately bounded race estimate for one own target transition. */
public record CompetitiveOpportunity(
        Position targetPosition,
        BrandId brand,
        int stock,
        AgentId ownAgent,
        int ownEta,
        int opponentEta,
        int raceMarginSteps,
        int expectedAvailableStockAtOwnArrival,
        int travelCost,
        boolean missingBrand,
        int continuationCandidateCount,
        int bestContinuationRaceMargin,
        int boundedContinuationValue,
        int ownArrivalGap,
        boolean naturalOwner) {
    public CompetitiveOpportunity {
        Objects.requireNonNull(targetPosition, "Target position must not be null");
        Objects.requireNonNull(brand, "Target brand must not be null");
        Objects.requireNonNull(ownAgent, "Own agent must not be null");
        if (stock < 0 || ownEta < 0 || opponentEta < -1 || expectedAvailableStockAtOwnArrival < 0
                || travelCost < 0 || continuationCandidateCount < 0 || ownArrivalGap < 0) {
            throw new IllegalArgumentException("Competitive opportunity values must be non-negative");
        }
    }

    public boolean contested() {
        return opponentEta >= 0 && raceMarginSteps <= 0;
    }
}
