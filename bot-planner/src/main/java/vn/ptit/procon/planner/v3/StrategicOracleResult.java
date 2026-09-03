package vn.ptit.procon.planner.v3;

import java.util.List;

/** Result of the bounded V3 Phase 0 oracle. */
public record StrategicOracleResult(StrategicOpportunityGraph graph, List<OpportunityChain> chains,
        List<TeamAllocation> allocations, StrategicOracleEvaluation winner, V3Phase0Diagnostics diagnostics,
        String headroomReason) {
    public StrategicOracleResult {
        chains = List.copyOf(chains); allocations = List.copyOf(allocations);
        if (graph == null || winner == null || diagnostics == null || headroomReason == null) throw new IllegalArgumentException("Oracle result fields must not be null");
    }
}
