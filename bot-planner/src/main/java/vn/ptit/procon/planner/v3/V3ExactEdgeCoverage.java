package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.planner.oracle.OptimalCollectionSkeleton;

/** Exact-skeleton transition coverage audit; benchmark authority, never a search shortcut. */
public record V3ExactEdgeCoverage(int exactTransitionCount, int coveredTransitionCount,
        int missingTransitionCount, double coverageRatio, String firstMissingTransition) {
    public V3ExactEdgeCoverage {
        if (exactTransitionCount < 0 || coveredTransitionCount < 0 || missingTransitionCount < 0
                || coveredTransitionCount > exactTransitionCount || missingTransitionCount != exactTransitionCount - coveredTransitionCount
                || coverageRatio < 0 || coverageRatio > 1 || firstMissingTransition == null) {
            throw new IllegalArgumentException("Invalid exact edge coverage");
        }
    }

    public static V3ExactEdgeCoverage audit(StrategicOpportunityGraph graph, List<List<Position>> transitions) {
        Objects.requireNonNull(graph, "Graph must not be null");
        Objects.requireNonNull(transitions, "Transitions must not be null");
        int total = 0;
        int covered = 0;
        String firstMissing = "NONE";
        for (List<Position> path : transitions) {
            for (int i = 1; i < path.size(); i++) {
                total++;
                Position from = path.get(i - 1);
                Position to = path.get(i);
                boolean present = graph.outgoing().getOrDefault(from, List.of()).stream()
                        .anyMatch(edge -> edge.to().position().equals(to));
                if (present) covered++;
                else if (firstMissing.equals("NONE")) firstMissing = from.value() + ">" + to.value();
            }
        }
        return new V3ExactEdgeCoverage(total, covered, total - covered,
                total == 0 ? 1.0 : (double) covered / total, firstMissing);
    }

    public static V3ExactEdgeCoverage audit(StrategicOpportunityGraph graph,
            OptimalCollectionSkeleton skeleton) {
        return audit(graph, skeleton.agents().stream().map(agent -> agent.visits().stream()
                .map(visit -> new Position(visit.position())).toList()).toList());
    }

    public boolean complete() { return missingTransitionCount == 0; }
}
