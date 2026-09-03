package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Set;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/** A bounded strategic sequence, before tactical action materialization. */
public record OpportunityChain(
        String chainId,
        List<StrategicOpportunity> opportunities,
        int totalRawCollectionPotential,
        Set<BrandId> distinctBrands,
        int travelDuration,
        int fuelCost,
        List<Integer> regionIds,
        Position startPosition,
        Position endPosition,
        List<Integer> opponentPressureProfile) {
    public OpportunityChain {
        if (chainId == null || opportunities == null || distinctBrands == null || regionIds == null
                || startPosition == null || endPosition == null || opponentPressureProfile == null) {
            throw new IllegalArgumentException("Chain fields must not be null");
        }
        opportunities = List.copyOf(opportunities);
        distinctBrands = Set.copyOf(distinctBrands);
        regionIds = List.copyOf(regionIds);
        opponentPressureProfile = List.copyOf(opponentPressureProfile);
    }
}
