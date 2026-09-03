package vn.ptit.procon.planner.v3;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Finite, benchmark-only controls for the Phase 2 strategic search. */
public record StrategicSearchConfig(int strategicBeamWidth, int maxStrategicExpandedStates,
        int maxStrategicChildrenPerState, int maxAllocationCandidates, int maxRouteDepth,
        int maxTerminalEvaluations, long maxPlanningMillis, LongSupplier nanoClock,
        boolean trajectoryState, boolean perStateChildQuota) {
    public StrategicSearchConfig {
        if (strategicBeamWidth <= 0 || maxStrategicExpandedStates < 0 || maxStrategicChildrenPerState <= 0
                || maxAllocationCandidates <= 0 || maxRouteDepth <= 0 || maxTerminalEvaluations < 0
                || maxPlanningMillis < 0) throw new IllegalArgumentException("Strategic search bounds must be finite");
        nanoClock = Objects.requireNonNull(nanoClock, "Clock must not be null");
    }

    public StrategicSearchConfig(int beam, int expanded, int children, int allocations, int depth,
            int terminals, long maxPlanningMillis, LongSupplier clock) {
        this(beam, expanded, children, allocations, depth, terminals, maxPlanningMillis, clock, true, true);
    }

    public StrategicSearchConfig(int beam, int expanded, int children, int allocations, int terminals) {
        this(beam, expanded, children, allocations, 10, terminals, 0, System::nanoTime);
    }

    public StrategicSearchConfig(int beam, int expanded, int children, int allocations, int depth, int terminals) {
        this(beam, expanded, children, allocations, depth, terminals, 0, System::nanoTime);
    }

    public StrategicSearchConfig(int beam, int expanded, int children, int allocations, int depth,
            int terminals, long maxPlanningMillis) {
        this(beam, expanded, children, allocations, depth, terminals, maxPlanningMillis, System::nanoTime);
    }

    public static StrategicSearchConfig defaults() {
        return new StrategicSearchConfig(32, 128, 32, 16, 10, 16, 0, System::nanoTime);
    }

    public StrategicSearchConfig withClock(LongSupplier clock) {
        return new StrategicSearchConfig(strategicBeamWidth, maxStrategicExpandedStates,
                maxStrategicChildrenPerState, maxAllocationCandidates, maxRouteDepth,
                maxTerminalEvaluations, maxPlanningMillis, clock, trajectoryState, perStateChildQuota);
    }

    /** Diagnostic-only causal ablation; production has no access to these modes. */
    public StrategicSearchConfig withAblation(boolean trajectory, boolean perStateQuota) {
        return new StrategicSearchConfig(strategicBeamWidth, maxStrategicExpandedStates,
                maxStrategicChildrenPerState, maxAllocationCandidates, maxRouteDepth,
                maxTerminalEvaluations, maxPlanningMillis, nanoClock, trajectory, perStateQuota);
    }

    public StrategicSearchConfig withTrajectoryState(boolean enabled) {
        return withAblation(enabled, perStateChildQuota);
    }

    public StrategicSearchConfig withPerStateChildQuota(boolean enabled) {
        return withAblation(trajectoryState, enabled);
    }
}
