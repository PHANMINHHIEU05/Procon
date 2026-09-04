package vn.ptit.procon.planner.v3;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.planner.Route;

/**
 * ONE already-valid strategic route of ONE PATROL — the unit the Phase 2.6 team-composition search assigns.
 *
 * <p>Phase 2.5 proved that the per-PATROL geometry V3 needs is already retained: every hop of the V2
 * witness exists in the graph caches. What was missing was a NAME for a whole per-PATROL route, because
 * the old joint beam only ever held one-hop-at-a-time prefixes shared across the whole team. This record
 * is that name: an ordered target list, the exact cached {@link Route} chain the targets denote, and the
 * per-route facts the composition search orders and diversifies on.
 *
 * <p>Nothing here is searched or invented. {@code legs} are the very Route objects the graph already holds,
 * so materialising a candidate performs no pathfinding, and {@code arrivalSteps} come from the support
 * scheduler of the root this candidate was built under.
 */
public record StrategicRouteCandidate(AgentId patrolId, List<Position> targets, List<Route> legs,
        List<BrandId> brandSequence, List<Integer> regionSequence, List<Integer> arrivalSteps,
        int stepsUsed, int endFuel, Position startPosition, Position endPosition, int soloPotential,
        boolean supportDependent, String signature) {

    public StrategicRouteCandidate {
        Objects.requireNonNull(patrolId, "Patrol id must not be null");
        targets = List.copyOf(Objects.requireNonNull(targets, "Targets must not be null"));
        legs = List.copyOf(Objects.requireNonNull(legs, "Legs must not be null"));
        brandSequence = List.copyOf(Objects.requireNonNull(brandSequence, "Brands must not be null"));
        regionSequence = List.copyOf(Objects.requireNonNull(regionSequence, "Regions must not be null"));
        arrivalSteps = List.copyOf(Objects.requireNonNull(arrivalSteps, "Arrivals must not be null"));
        Objects.requireNonNull(startPosition, "Start must not be null");
        Objects.requireNonNull(endPosition, "End must not be null");
        Objects.requireNonNull(signature, "Signature must not be null");
        if (targets.size() != legs.size()) {
            throw new IllegalArgumentException("Every target must be reached by exactly one cached leg");
        }
    }

    /** The empty route: this PATROL is deliberately left with nothing to do. */
    public static StrategicRouteCandidate empty(AgentId patrolId, Position start, int startFuel) {
        return new StrategicRouteCandidate(patrolId, List.of(), List.of(), List.of(), List.of(), List.of(),
                0, startFuel, start, start, 0, false, "STOP");
    }

    public boolean isEmpty() { return targets.isEmpty(); }

    public int length() { return targets.size(); }

    public Position firstTarget() { return targets.isEmpty() ? startPosition : targets.getFirst(); }

    public Position lastTarget() { return targets.isEmpty() ? startPosition : targets.getLast(); }

    public Set<BrandId> brands() { return Set.copyOf(new LinkedHashSet<>(brandSequence)); }

    /** The de-duplicated target set — what this route CLAIMS, regardless of the order it claims it in. */
    public Set<Position> targetSet() { return Set.copyOf(new LinkedHashSet<>(targets)); }

    /**
     * PART 3: the structural fingerprint used for bounded diversity.
     *
     * <p>It deliberately does NOT collapse to the collection count: two routes with the same number of
     * collections that end in different cells, claim different targets or arrive on a different chronology
     * are different futures, and the portfolio must be allowed to keep both.
     */
    public String diversityKey() {
        return firstTarget().value() + "/" + lastTarget().value() + "/" + targets.size() + "/"
                + brands().size() + "/" + regionSequence + "/"
                + targetSet().stream().map(Position::value).sorted().toList();
    }

    @Override
    public String toString() {
        return "P" + patrolId.value() + "=" + signature + " own=" + soloPotential + " steps=" + stepsUsed;
    }
}
