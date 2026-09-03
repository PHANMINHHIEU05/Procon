package vn.ptit.procon.planner.v3;

import java.util.List;
import vn.ptit.procon.domain.agent.AgentId;

/** Strategic assignment of one PATROL to a chain. */
public record PatrolAllocation(AgentId patrolId, OpportunityRegion primaryRegion,
        OpportunityChain primaryChain, OpportunityChain secondaryChain, List<Integer> allocatedPositions,
        int estimatedCollections, int estimatedBrands, int estimatedDuration, int estimatedFuel) {
    public PatrolAllocation {
        if (patrolId == null || allocatedPositions == null) throw new IllegalArgumentException("Allocation fields must not be null");
        allocatedPositions = List.copyOf(allocatedPositions);
    }
}
