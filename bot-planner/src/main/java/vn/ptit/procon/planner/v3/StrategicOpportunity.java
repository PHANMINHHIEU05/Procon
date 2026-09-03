package vn.ptit.procon.planner.v3;

import java.util.Map;
import java.util.Set;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/** Raw strategic features of one collectible opportunity. */
public record StrategicOpportunity(
        Position position,
        BrandId brand,
        int initialStock,
        int currentStock,
        int nearbyOpportunityCount,
        int localStockDensity,
        int brandDiversityNearby,
        int opponentPressure,
        int ownAccessibility,
        int continuationDegree,
        Map<Integer, Integer> estimatedTravelCostByAgent) {
    public StrategicOpportunity {
        if (position == null || brand == null || estimatedTravelCostByAgent == null) {
            throw new IllegalArgumentException("Opportunity fields must not be null");
        }
        if (initialStock < 0 || currentStock < 0 || nearbyOpportunityCount < 0 || localStockDensity < 0
                || brandDiversityNearby < 0 || opponentPressure < 0 || ownAccessibility < 0
                || continuationDegree < 0) throw new IllegalArgumentException("Opportunity features must be non-negative");
        estimatedTravelCostByAgent = Map.copyOf(estimatedTravelCostByAgent);
    }
}
