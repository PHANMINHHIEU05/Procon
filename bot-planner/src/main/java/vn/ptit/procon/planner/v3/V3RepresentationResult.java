package vn.ptit.procon.planner.v3;

import java.util.List;

/** Result of the benchmark-only graph-path representation oracle. */
public record V3RepresentationResult(StrategicOpportunityGraph graph, List<RouteSkeleton> skeletons,
        StrategicOracleEvaluation winner, V3RepresentationDiagnostics diagnostics, String reason) {
    public V3RepresentationResult {
        if (graph == null || skeletons == null || winner == null || diagnostics == null || reason == null) {
            throw new IllegalArgumentException("Representation result fields must not be null");
        }
        skeletons = List.copyOf(skeletons);
    }
}
