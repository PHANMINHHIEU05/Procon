package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;

/**
 * One bounded strategic transition, not a movement-command node.
 *
 * <p>PART 19: the node also names the FIXED support root the whole subtree is searched under. Two nodes
 * whose PATROL states agree but whose tanker trajectories differ are not the same node, and the support
 * identity in {@link StrategicSearchState#supportState()} keeps that distinction inside the dedup key.
 */
public record StrategicSearchNode(StrategicSearchState state, List<StrategicTransition> transitions,
        int depth, String firstTargetVector, V3SupportRootContext supportRoot) {
    public StrategicSearchNode(StrategicSearchState state, List<StrategicTransition> transitions, int depth,
            String firstTargetVector) {
        this(state, transitions, depth, firstTargetVector, V3SupportRootContext.noRefuel());
    }
    public StrategicSearchNode {
        transitions = List.copyOf(transitions);
        Objects.requireNonNull(supportRoot, "Support root must not be null");
        if (depth < 0) throw new IllegalArgumentException("Depth must be non-negative");
    }

    /** PART 8: true only when the subtree is searched under a mobile REFUEL trajectory. */
    public boolean mobileSupport() { return supportRoot.present(); }
}
