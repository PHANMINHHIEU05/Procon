package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Set;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/** Deterministic, soft spatial grouping of opportunities. */
public record OpportunityRegion(
        int regionId,
        List<StrategicOpportunity> members,
        int totalStock,
        Set<BrandId> brands,
        Position representativePosition,
        int internalTravelCostEstimate,
        int bestOwnAgentAccessibility,
        int opponentAccessibility,
        List<StrategicOpportunity> entryCandidates,
        List<StrategicOpportunity> exitCandidates) {
    public OpportunityRegion {
        if (regionId < 0 || members == null || brands == null || representativePosition == null
                || entryCandidates == null || exitCandidates == null) throw new IllegalArgumentException("Region fields invalid");
        members = List.copyOf(members);
        brands = Set.copyOf(brands);
        entryCandidates = List.copyOf(entryCandidates);
        exitCandidates = List.copyOf(exitCandidates);
    }
}
