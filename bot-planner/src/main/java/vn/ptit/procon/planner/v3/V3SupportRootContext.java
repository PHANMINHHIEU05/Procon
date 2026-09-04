package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.v2.R3SupportRootExport;

/**
 * Read-only V3 adapter around ONE existing R3 support root.
 *
 * <p>PART 1: this class references, and never reinvents, the R3 root identity, the REFUEL actor, its
 * authoritative action sequence, its service metadata and its provenance. V3 never generates a tanker
 * tour: every context with {@link #present()} true originates in {@link R3SupportRootExport} and
 * carries provenance {@link R3SupportRootExport#EXISTING_R3_ROOT}.
 *
 * <p>The single derived artefact is {@link #trajectory()}, which turns the existing REFUEL action
 * sequence into a step-indexed occupancy timeline. That derivation reads the map, never a route finder.
 */
public record V3SupportRootContext(String supportRootId, String signature, AgentId refuelAgentId,
        Position refuelStartPosition, int serviceCount, List<Integer> supportedPatrolIds,
        List<AgentAction> refuelActions, List<R3SupportRootExport.PlannedService> plannedServices,
        String provenance, CachedSupportTrajectory trajectory) {

    /** The support axis value used when V3 keeps its historical, tanker-free behaviour. */
    public static final String NO_REFUEL = "NO_REFUEL";

    public V3SupportRootContext {
        Objects.requireNonNull(supportRootId, "Support root id must not be null");
        Objects.requireNonNull(signature, "Support root signature must not be null");
        Objects.requireNonNull(refuelAgentId, "REFUEL id must not be null");
        Objects.requireNonNull(refuelStartPosition, "REFUEL start must not be null");
        supportedPatrolIds = List.copyOf(Objects.requireNonNull(supportedPatrolIds));
        refuelActions = List.copyOf(Objects.requireNonNull(refuelActions));
        plannedServices = List.copyOf(Objects.requireNonNull(plannedServices));
        Objects.requireNonNull(provenance, "Provenance must not be null");
        Objects.requireNonNull(trajectory, "Support trajectory must not be null");
    }

    /** PART 8: the unchanged tanker-free root. It stays a first-class member of the universe. */
    public static V3SupportRootContext noRefuel() {
        return new V3SupportRootContext(NO_REFUEL, NO_REFUEL, new AgentId(0), new Position(0), 0,
                List.of(), List.of(), List.of(), NO_REFUEL, CachedSupportTrajectory.none());
    }

    /** Adapts one exported R3 root; the REFUEL action sequence stays authoritative. */
    public static V3SupportRootContext of(DayState state, R3SupportRootExport export) {
        Objects.requireNonNull(export, "Exported root must not be null");
        CachedSupportTrajectory trajectory = CachedSupportTrajectory.from(state, export.refuelAgentId(),
                export.refuelStartPosition(), export.refuelActions());
        return new V3SupportRootContext("R3#" + export.rootIndex(), export.signature(), export.refuelAgentId(),
                export.refuelStartPosition(), export.serviceCount(), export.supportedPatrolIds(),
                export.refuelActions(), export.plannedServices(), export.provenance(), trajectory);
    }

    public boolean present() { return trajectory.present(); }

    /** PART 48: V3 must never claim authorship of a tanker tour. */
    public boolean existingR3Root() {
        return present() && R3SupportRootExport.EXISTING_R3_ROOT.equals(provenance);
    }

    /**
     * PART 19/20: the support identity stored in {@link StrategicSearchState#supportState()}.
     *
     * <p>It names the exact tanker trajectory, not a vague class, so two roots whose future refuelling
     * differs can never be merged. The pending-event fingerprint makes the remaining future explicit.
     */
    public String supportStateKey(int fromStep) {
        if (!present()) return NO_REFUEL;
        return "R3:" + signature + "|pending=" + trajectory.futureFingerprint(fromStep);
    }
}
