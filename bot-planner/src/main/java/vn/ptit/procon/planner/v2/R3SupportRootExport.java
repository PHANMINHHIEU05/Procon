package vn.ptit.procon.planner.v2;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * Read-only view of ONE support root that {@link JointTeamBeamR3Planner} already retains for its own
 * root universe.
 *
 * <p>This record adds no generation, validation, retention or scoring rule: it only re-publishes the
 * authoritative REFUEL action sequence and service metadata of an existing R3 root using public types,
 * because the internal {@code JointTeamSearchState.RefuelRootSchedule} is package private and cannot
 * cross the package boundary into {@code planner.v3}.
 *
 * <p>{@link #refuelActions()} is the authoritative tanker behaviour and is the primary content of this
 * export. {@link #plannedServices()} is metadata only: it records the services R3 intended, and it is
 * NOT an exhaustive list of the refuelling that the tanker route actually causes.
 */
public record R3SupportRootExport(int rootIndex, String signature, AgentId refuelAgentId,
        Position refuelStartPosition, List<AgentAction> refuelActions, int refuelElapsedSteps,
        int serviceCount, List<Integer> supportedPatrolIds, List<PlannedService> plannedServices,
        String provenance, String familyProvenance) {

    /** Every root reachable through this export originates in the existing R3 root universe. */
    public static final String EXISTING_R3_ROOT = "EXISTING_R3_ROOT";

    public R3SupportRootExport {
        Objects.requireNonNull(signature, "Support root signature must not be null");
        Objects.requireNonNull(refuelAgentId, "REFUEL agent must not be null");
        Objects.requireNonNull(refuelStartPosition, "REFUEL start position must not be null");
        refuelActions = List.copyOf(Objects.requireNonNull(refuelActions, "REFUEL actions must not be null"));
        supportedPatrolIds = List.copyOf(Objects.requireNonNull(supportedPatrolIds, "Supported patrols must not be null"));
        plannedServices = List.copyOf(Objects.requireNonNull(plannedServices, "Planned services must not be null"));
        Objects.requireNonNull(provenance, "Provenance must not be null");
        Objects.requireNonNull(familyProvenance, "Family provenance must not be null");
        if (rootIndex < 0 || refuelElapsedSteps <= 0 || serviceCount <= 0) {
            throw new IllegalArgumentException("Exported R3 root must carry a positive service prefix");
        }
    }

    /** One service R3 intended when it validated this root; the tanker route stays authoritative. */
    public record PlannedService(int patrolId, Position position, int serviceStep, int patrolFuelAfter,
            List<AgentAction> patrolPrefixActions) {
        public PlannedService {
            Objects.requireNonNull(position, "Service position must not be null");
            patrolPrefixActions = List.copyOf(Objects.requireNonNull(patrolPrefixActions,
                    "Service PATROL prefix must not be null"));
            if (serviceStep <= 0 || patrolFuelAfter < 0) {
                throw new IllegalArgumentException("Exported service must carry a positive step");
            }
        }
    }
}
