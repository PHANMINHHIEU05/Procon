package vn.ptit.procon.planner.v3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;

/**
 * PART 49/53: what the support axis cost, what it unlocked, and which root actually won.
 *
 * <p>Everything here is measured, never assumed. {@link #universeAudit()} carries the PART 48 hard
 * diagnostic ({@code v3GeneratedNewRefuelTours}), the per-root maps show how the unchanged allocation
 * budget was spent, the scheduler counters show that the PART 12 candidate set stayed bounded, and the two
 * entry-point maps show the PART 9/23/30 separation: how many opportunities each PATROL could reach with
 * its real starting tank versus how many it can reach at all as pure geometry.
 */
public record V3SupportSearchDiagnostics(V3SupportRootUniverseAudit universeAudit, int supportRootsConsidered,
        int mobileSupportRootsConsidered, Map<String, Integer> allocationsPerSupportRoot,
        Map<String, Integer> statesExpandedPerSupportRoot, int supportTrajectoriesCached,
        int supportTimelineSteps, int supportRefuelCapableCells, int schedulesRequested, int schedulesFeasible,
        int scheduleCandidatesEvaluated, int waitsInserted, int supportEventsUsed, int supportEventsProcessed,
        String selectedSupportRootId, String selectedSupportRootSignature, String rawSelectedSupportRootId,
        String rawSelectedSupportRootSignature, Map<AgentId, Integer> fuelFilteredEntryPointsByPatrol,
        Map<AgentId, Integer> potentialEntryPointsByPatrol) {

    public V3SupportSearchDiagnostics {
        Objects.requireNonNull(universeAudit, "Universe audit must not be null");
        allocationsPerSupportRoot = Map.copyOf(Objects.requireNonNull(allocationsPerSupportRoot));
        statesExpandedPerSupportRoot = Map.copyOf(Objects.requireNonNull(statesExpandedPerSupportRoot));
        Objects.requireNonNull(selectedSupportRootId, "Selected support root id must not be null");
        Objects.requireNonNull(selectedSupportRootSignature, "Selected signature must not be null");
        Objects.requireNonNull(rawSelectedSupportRootId, "Raw selected support root id must not be null");
        Objects.requireNonNull(rawSelectedSupportRootSignature, "Raw selected signature must not be null");
        fuelFilteredEntryPointsByPatrol = Map.copyOf(Objects.requireNonNull(fuelFilteredEntryPointsByPatrol));
        potentialEntryPointsByPatrol = Map.copyOf(Objects.requireNonNull(potentialEntryPointsByPatrol));
    }

    /** PART 8: the historical, tanker-free diagnostics shape. */
    public static V3SupportSearchDiagnostics none() {
        V3SupportRootUniverseAudit audit = new V3SupportRootUniverseAudit(true, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0,
                0, 0, List.of(V3SupportRootContext.NO_REFUEL), List.of(V3SupportRootContext.NO_REFUEL));
        return new V3SupportSearchDiagnostics(audit, 1, 0, Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0,
                V3SupportRootContext.NO_REFUEL, V3SupportRootContext.NO_REFUEL,
                V3SupportRootContext.NO_REFUEL, V3SupportRootContext.NO_REFUEL, Map.of(), Map.of());
    }

    static V3SupportSearchDiagnostics of(V3SupportRootUniverse universe,
            Map<String, SupportAwareTrajectoryScheduler> schedulers, Map<String, Integer> allocationsPerSupportRoot,
            Map<String, Integer> statesExpandedPerSupportRoot, int supportEventsProcessed,
            V3SupportRootContext selected, V3SupportRootContext rawSelected,
            Map<AgentId, Integer> fuelFilteredEntryPoints, Map<AgentId, Integer> potentialEntryPoints) {
        int timelineSteps = 0, refuelCells = 0, requested = 0, feasible = 0, candidates = 0, waits = 0, events = 0;
        for (SupportAwareTrajectoryScheduler scheduler : schedulers.values()) {
            timelineSteps += scheduler.support().timeline().size();
            refuelCells += scheduler.support().refuelCapablePositions().size();
            requested += scheduler.schedulesRequested();
            feasible += scheduler.schedulesFeasible();
            candidates += scheduler.candidatesEvaluated();
            waits += scheduler.waitsInserted();
            events += scheduler.supportEventsUsed();
        }
        return new V3SupportSearchDiagnostics(universe.audit(), universe.roots().size(),
                universe.mobileRoots().size(), ordered(allocationsPerSupportRoot),
                ordered(statesExpandedPerSupportRoot), schedulers.size(), timelineSteps, refuelCells, requested,
                feasible, candidates, waits, events, supportEventsProcessed, selected.supportRootId(),
                selected.signature(), rawSelected.supportRootId(), rawSelected.signature(),
                fuelFilteredEntryPoints, potentialEntryPoints);
    }

    private static Map<String, Integer> ordered(Map<String, Integer> values) {
        return Map.copyOf(new LinkedHashMap<>(values));
    }

    /** PART 48: hard. V3 consumes existing R3 roots and never authors a tanker tour. */
    public int v3GeneratedNewRefuelTours() { return universeAudit.v3GeneratedNewRefuelTours(); }

    /** PART 37: true only when the raw bounded search actually preferred a mobile tanker. */
    public boolean mobileSupportSelected() {
        return !V3SupportRootContext.NO_REFUEL.equals(rawSelectedSupportRootSignature);
    }

    public int totalEntryPoints(boolean potential) {
        Map<AgentId, Integer> source = potential ? potentialEntryPointsByPatrol : fuelFilteredEntryPointsByPatrol;
        return source.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public String toString() {
        return "supportRootsConsidered=" + supportRootsConsidered + " mobile=" + mobileSupportRootsConsidered
                + " allocationsPerSupportRoot=" + allocationsPerSupportRoot
                + " statesExpandedPerSupportRoot=" + statesExpandedPerSupportRoot
                + " schedules=" + schedulesFeasible + "/" + schedulesRequested
                + " candidates=" + scheduleCandidatesEvaluated + " waitsInserted=" + waitsInserted
                + " supportEventsUsed=" + supportEventsUsed + " supportEventsProcessed=" + supportEventsProcessed
                + " selected=" + selectedSupportRootSignature + " raw=" + rawSelectedSupportRootSignature
                + " entryPoints=" + totalEntryPoints(false) + "->" + totalEntryPoints(true)
                + " v3GeneratedNewRefuelTours=" + v3GeneratedNewRefuelTours();
    }
}
