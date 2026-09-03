package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * The strategic reading of a {@link V2BaselineWitness}: which opportunities each PATROL commits to,
 * in which order, what it merely walks over, and which support service each commitment depends on.
 *
 * <p>This is diagnostic extraction only.  It is never fed into normal V3 search.
 */
public record V2StrategicWitness(List<PatrolSkeleton> patrols, String supportRootSignature,
        int totalStrategicTransitions, int totalStrategicCollections, int totalIntermediateCollections) {

    public V2StrategicWitness {
        patrols = List.copyOf(patrols);
        Objects.requireNonNull(supportRootSignature);
    }

    public PatrolSkeleton patrol(AgentId patrolId) {
        return patrols.stream().filter(value -> value.patrolId().equals(patrolId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown patrol: " + patrolId.value()));
    }

    /** Compact human-readable skeleton, used verbatim in the Phase 2.4 report. */
    public String skeleton() {
        return patrols.stream().map(PatrolSkeleton::skeleton).reduce((left, right) -> left + " | " + right)
                .orElse("EMPTY");
    }

    /**
     * One PATROL's strategic commitment sequence.
     *
     * @param orderedStrategicCollections positions this PATROL actually claimed, in claim order
     * @param intermediateTrajectoryCollections spot cells it walked over without claiming
     * @param crossRegionTransitions indices of transitions whose endpoints sit in different graph regions
     * @param supportProvenance {@code REFUEL@position/step} of the first service, or {@code NONE}
     */
    public record PatrolSkeleton(AgentId patrolId, Position start, int startFuel,
            List<Position> orderedStrategicCollections, List<Position> intermediateTrajectoryCollections,
            List<Integer> crossRegionTransitions, int stopStep, String supportProvenance,
            List<StrategicStep> transitions) {
        public PatrolSkeleton {
            Objects.requireNonNull(patrolId);
            Objects.requireNonNull(start);
            orderedStrategicCollections = List.copyOf(orderedStrategicCollections);
            intermediateTrajectoryCollections = List.copyOf(intermediateTrajectoryCollections);
            crossRegionTransitions = List.copyOf(crossRegionTransitions);
            transitions = List.copyOf(transitions);
            Objects.requireNonNull(supportProvenance);
        }

        public boolean requiresSupport() { return !supportProvenance.equals("NONE"); }

        public String skeleton() {
            return patrolId.value() + "@" + start.value() + "(f" + startFuel + ")"
                    + (requiresSupport() ? "[" + supportProvenance + "]" : "")
                    + "->" + orderedStrategicCollections.stream().map(position -> Integer.toString(position.value()))
                            .reduce((left, right) -> left + "->" + right).orElse("STOP");
        }
    }

    /**
     * One strategic transition, carrying the authoritative movement path V2 actually used.
     *
     * @param requiredRefuels refuel steps that happen at or before this transition's arrival and after
     *        the previous arrival; a non-empty list means the transition is support-dependent
     */
    public record StrategicStep(int index, Position from, Position to, int departureStep, int arrivalStep,
            List<Position> traversedPositions, List<Position> traversedSpots, int moves,
            List<Position> requiredRefuelPositions, List<Integer> requiredRefuelSteps) {
        public StrategicStep {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            traversedPositions = List.copyOf(traversedPositions);
            traversedSpots = List.copyOf(traversedSpots);
            requiredRefuelPositions = List.copyOf(requiredRefuelPositions);
            requiredRefuelSteps = List.copyOf(requiredRefuelSteps);
        }

        public boolean supportDependent() { return !requiredRefuelPositions.isEmpty(); }

        public String label() { return from.value() + "->" + to.value(); }
    }
}
