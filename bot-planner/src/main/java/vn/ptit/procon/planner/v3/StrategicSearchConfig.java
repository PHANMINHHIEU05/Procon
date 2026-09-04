package vn.ptit.procon.planner.v3;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Finite, benchmark-only controls for the Phase 2 strategic search. */
public record StrategicSearchConfig(int strategicBeamWidth, int maxStrategicExpandedStates,
        int maxStrategicChildrenPerState, int maxAllocationCandidates, int maxRouteDepth,
        int maxTerminalEvaluations, long maxPlanningMillis, LongSupplier nanoClock,
        boolean trajectoryState, boolean perStateChildQuota, boolean compositionSearch) {
    public StrategicSearchConfig {
        if (strategicBeamWidth <= 0 || maxStrategicExpandedStates < 0 || maxStrategicChildrenPerState <= 0
                || maxAllocationCandidates <= 0 || maxRouteDepth <= 0 || maxTerminalEvaluations < 0
                || maxPlanningMillis < 0) throw new IllegalArgumentException("Strategic search bounds must be finite");
        nanoClock = Objects.requireNonNull(nanoClock, "Clock must not be null");
    }

    /**
     * Every bound stays exactly where Phase 2.5 froze it; only the SEARCH ORGANISATION is selectable.
     *
     * <p>{@code compositionSearch} defaults to {@code false} so every existing caller keeps the historical
     * joint per-hop beam bit-for-bit — the Phase 2.2–2.5 tests assert exact counters and must not move. The
     * Phase 2.6 report needs the before and the after side by side, which is only possible while both
     * organisations are reachable from the SAME frozen numbers.
     */
    public StrategicSearchConfig(int beam, int expanded, int children, int allocations, int depth,
            int terminals, long maxPlanningMillis, LongSupplier clock, boolean trajectoryState,
            boolean perStateChildQuota) {
        this(beam, expanded, children, allocations, depth, terminals, maxPlanningMillis, clock,
                trajectoryState, perStateChildQuota, false);
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
                maxTerminalEvaluations, maxPlanningMillis, clock, trajectoryState, perStateChildQuota,
                compositionSearch);
    }

    /** Diagnostic-only causal ablation; production has no access to these modes. */
    public StrategicSearchConfig withAblation(boolean trajectory, boolean perStateQuota) {
        return new StrategicSearchConfig(strategicBeamWidth, maxStrategicExpandedStates,
                maxStrategicChildrenPerState, maxAllocationCandidates, maxRouteDepth,
                maxTerminalEvaluations, maxPlanningMillis, nanoClock, trajectory, perStateQuota,
                compositionSearch);
    }

    /**
     * PART 39: selects the Phase 2.6 team-composition organisation. It changes NO bound.
     *
     * <p>The composition search reads exactly the same six numbers this record already carries — route length
     * from {@code maxRouteDepth}, per-PATROL portfolio work from {@code maxStrategicChildrenPerState}, the
     * composition frontier from {@code strategicBeamWidth}, total composition expansions from
     * {@code maxStrategicExpandedStates}, root seeds from {@code maxAllocationCandidates}, materialisation and
     * coupled evaluation from {@code maxTerminalEvaluations}.
     */
    public StrategicSearchConfig withCompositionSearch(boolean enabled) {
        return new StrategicSearchConfig(strategicBeamWidth, maxStrategicExpandedStates,
                maxStrategicChildrenPerState, maxAllocationCandidates, maxRouteDepth,
                maxTerminalEvaluations, maxPlanningMillis, nanoClock, trajectoryState, perStateChildQuota,
                enabled);
    }

    public StrategicSearchConfig withTrajectoryState(boolean enabled) {
        return withAblation(enabled, perStateChildQuota);
    }

    public StrategicSearchConfig withPerStateChildQuota(boolean enabled) {
        return withAblation(trajectoryState, enabled);
    }
}
