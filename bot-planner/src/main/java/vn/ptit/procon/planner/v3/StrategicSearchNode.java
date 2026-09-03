package vn.ptit.procon.planner.v3;

import java.util.List;

/** One bounded strategic transition, not a movement-command node. */
public record StrategicSearchNode(StrategicSearchState state, List<StrategicTransition> transitions,
        int depth, String firstTargetVector) {
    public StrategicSearchNode {
        transitions = List.copyOf(transitions);
        if (depth < 0) throw new IllegalArgumentException("Depth must be non-negative");
    }
}
