package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;

/**
 * Derives a {@link V2StrategicWitness} from a captured {@link V2BaselineWitness}.
 *
 * <p>The V2 witness is never manually rewritten: every position, step and refuel dependency below is
 * read out of the authoritative simulator trace that {@link V2BaselineWitnessCapture} recorded.
 */
public final class V2PlanToStrategicWitness {

    /** Region labels come from the V3 graph so the representability audit can compare like with like. */
    public V2StrategicWitness extract(DayState state, V2BaselineWitness witness,
            StrategicOpportunityGraph graph) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(witness);
        Objects.requireNonNull(graph);
        Map<Position, Integer> regionByPosition = new LinkedHashMap<>();
        for (OpportunityRegion region : graph.regions()) {
            region.members().forEach(member -> regionByPosition.put(member.position(), region.regionId()));
        }
        Set<Position> spotPositions = state.matchData().udonSpots().stream()
                .map(vn.ptit.procon.domain.udon.UdonSpot::position)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<V2StrategicWitness.PatrolSkeleton> skeletons = new ArrayList<>();
        int transitionTotal = 0;
        int collectionTotal = 0;
        int intermediateTotal = 0;
        for (V2BaselineWitness.PatrolWitness patrol : witness.patrols()) {
            V2StrategicWitness.PatrolSkeleton skeleton = skeleton(witness, patrol, regionByPosition, spotPositions);
            skeletons.add(skeleton);
            transitionTotal += skeleton.transitions().size();
            collectionTotal += skeleton.orderedStrategicCollections().size();
            intermediateTotal += skeleton.intermediateTrajectoryCollections().size();
        }
        return new V2StrategicWitness(skeletons, witness.support().rootSignature(), transitionTotal,
                collectionTotal, intermediateTotal);
    }

    private static V2StrategicWitness.PatrolSkeleton skeleton(V2BaselineWitness witness,
            V2BaselineWitness.PatrolWitness patrol, Map<Position, Integer> regionByPosition,
            Set<Position> spotPositions) {
        List<Position> claimed = patrol.claimedPositions();
        List<Position> intermediate = patrol.strategicEncounters().stream()
                .filter(encounter -> !encounter.claimed()).map(V2BaselineWitness.Encounter::position).toList();
        List<Integer> arrivalSteps = patrol.claims().stream().map(V2BaselineWitness.Claim::step).toList();
        List<Integer> moveSteps = new ArrayList<>();
        moveSteps.add(0);
        patrol.fuelChronology().stream().filter(point -> point.cause().equals("MOVE"))
                .forEach(point -> moveSteps.add(point.step()));
        List<V2StrategicWitness.StrategicStep> transitions = new ArrayList<>();
        List<Integer> crossRegion = new ArrayList<>();
        Position cursor = patrol.start();
        int cursorIndex = 0;
        int previousArrival = 0;
        for (int i = 0; i < claimed.size(); i++) {
            Position target = claimed.get(i);
            int targetIndex = indexOf(patrol.movementRoute(), target, cursorIndex);
            if (targetIndex < 0) {
                // A claim at the start cell consumes no movement; keep the transition explicit anyway.
                targetIndex = cursorIndex;
            }
            List<Position> traversed = patrol.movementRoute().subList(cursorIndex, targetIndex + 1);
            List<Position> traversedSpots = traversed.stream().skip(1).filter(spotPositions::contains).toList();
            int arrival = arrivalSteps.get(i);
            int departure = moveSteps.get(Math.min(cursorIndex, moveSteps.size() - 1));
            List<Position> refuelPositions = new ArrayList<>();
            List<Integer> refuelSteps = new ArrayList<>();
            for (V2BaselineWitness.FuelPoint point : patrol.fuelChronology()) {
                if (!point.cause().equals("REFUEL")) continue;
                if (point.step() < previousArrival || point.step() > arrival) continue;
                refuelPositions.add(point.position());
                refuelSteps.add(point.step());
            }
            transitions.add(new V2StrategicWitness.StrategicStep(i, cursor, target, departure, arrival,
                    List.copyOf(traversed), traversedSpots, Math.max(0, targetIndex - cursorIndex),
                    List.copyOf(refuelPositions), List.copyOf(refuelSteps)));
            if (!Objects.equals(regionByPosition.get(cursor), regionByPosition.get(target))) crossRegion.add(i);
            cursor = target;
            cursorIndex = targetIndex;
            previousArrival = arrival;
        }
        var service = witness.support().serviceFor(patrol.patrolId());
        return new V2StrategicWitness.PatrolSkeleton(patrol.patrolId(), patrol.start(), patrol.startFuel(),
                claimed, intermediate, List.copyOf(crossRegion), patrol.lastMoveStep(),
                service.map(value -> "REFUEL@" + value.position().value() + "/" + value.step()).orElse("NONE"),
                List.copyOf(transitions));
    }

    private static int indexOf(List<Position> route, Position target, int from) {
        for (int i = from; i < route.size(); i++) if (route.get(i).equals(target)) return i;
        return -1;
    }
}
