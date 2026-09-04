package vn.ptit.procon.planner.v3;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.planner.Route;

/**
 * PART 8: one PARTIAL TEAM ASSIGNMENT — a whole strategic route given to zero or more PATROLs.
 *
 * <p>This is the unit the Phase 2.6 composition search expands, and the single reason the frozen expansion
 * budget is now enough. The joint beam it replaces committed ONE hop per expansion, so the fourteen-transition
 * team the V2 witness proves legal needed fourteen expansion levels and never got past four. Here a complete
 * team is reached in as many levels as there are PATROLs.
 *
 * <p>Every field below is either an assignment decision or a fact READ BACK from the trajectory-faithful
 * chronology replay of that assignment — never an estimate invented here. In particular
 * {@code securedCollections}, {@code remainingStock}, {@code brands} and {@code chronologyFingerprint} come
 * from {@link StrategicChronologyReplay}, so a partial team already knows about same-stock competition,
 * arrival order and incidental refills (PART 9).
 *
 * <p>{@code identity} deliberately contains only the support root and the assigned route signatures: those
 * determine every other field, and PART 8 forbids debug history in the identity.
 */
public record TeamCompositionState(V3SupportRootContext supportRoot,
        List<StrategicRouteCandidate> assigned, List<AgentId> unassigned,
        Map<Position, Integer> remainingStock, int securedCollections, Set<BrandId> brands,
        int optimisticRemaining, int duplicatedStockDemand, int routeOverlap, int totalElapsed,
        int minRemainingFuel, String chronologyFingerprint, Map<AgentId, Position> endPositions,
        Map<AgentId, Integer> endElapsed, Map<AgentId, Integer> endFuel, String identity) {

    public TeamCompositionState {
        Objects.requireNonNull(supportRoot, "Support root must not be null");
        assigned = List.copyOf(Objects.requireNonNull(assigned, "Assigned routes must not be null"));
        unassigned = List.copyOf(Objects.requireNonNull(unassigned, "Unassigned patrols must not be null"));
        remainingStock = Map.copyOf(Objects.requireNonNull(remainingStock, "Stock must not be null"));
        brands = Set.copyOf(Objects.requireNonNull(brands, "Brands must not be null"));
        endPositions = Map.copyOf(Objects.requireNonNull(endPositions, "Positions must not be null"));
        endElapsed = Map.copyOf(Objects.requireNonNull(endElapsed, "Elapsed must not be null"));
        endFuel = Map.copyOf(Objects.requireNonNull(endFuel, "Fuel must not be null"));
        Objects.requireNonNull(chronologyFingerprint, "Fingerprint must not be null");
        Objects.requireNonNull(identity, "Identity must not be null");
        if (securedCollections < 0) throw new IllegalArgumentException("Secured collections must be >= 0");
    }

    public int assignedCount() { return assigned.size(); }

    public boolean complete() { return unassigned.isEmpty(); }

    /**
     * PART 12: secured plus what the still-unassigned PATROLs could add if nothing conflicted.
     *
     * <p>Used for ORDERING only in this task. It is never subtracted from a bound to prune a state, because
     * admissibility across PATROLs that compete for the same spot has not been proven here.
     */
    public int optimisticTotal() { return securedCollections + optimisticRemaining; }

    /** The exact cached legs the assignment names — no pathfinding, no re-derivation. */
    public Map<AgentId, List<Route>> routesByPatrol() {
        Map<AgentId, List<Route>> result = new LinkedHashMap<>();
        assigned.forEach(route -> result.put(route.patrolId(), route.legs()));
        return Map.copyOf(result);
    }

    /** The ordered strategic targets per assigned PATROL, in the shape the materialiser expects. */
    public Map<AgentId, List<Position>> targetsByPatrol() {
        Map<AgentId, List<Position>> result = new LinkedHashMap<>();
        assigned.forEach(route -> result.put(route.patrolId(), route.targets()));
        return Map.copyOf(result);
    }

    /** The per-PATROL route signatures, ordered by PATROL id — the PART 36 witness-recall key. */
    public String routeSignatures() {
        return assigned.stream().sorted((a, b) -> Integer.compare(a.patrolId().value(), b.patrolId().value()))
                .map(route -> route.patrolId().value() + "=" + route.signature())
                .reduce((a, b) -> a + " " + b).orElse("EMPTY");
    }

    /**
     * PART 14: the team-level structural fingerprint, over the axes a team decision actually differs on.
     *
     * <p>Two teams that merely permute which PATROL of an equivalent pair takes which of two symmetric routes
     * still differ here, because the vectors are PATROL-ordered; what collapses is only a team that claims the
     * same cells, in the same regions, ending in the same places, under the same tanker.
     */
    public String diversityKey() {
        List<StrategicRouteCandidate> ordered = assigned.stream()
                .sorted((a, b) -> Integer.compare(a.patrolId().value(), b.patrolId().value())).toList();
        Set<Integer> claimed = new LinkedHashSet<>();
        ordered.forEach(route -> route.targetSet().forEach(position -> claimed.add(position.value())));
        return supportRoot.signature()
                + "|first=" + ordered.stream().map(route -> route.firstTarget().value()).toList()
                + "|end=" + ordered.stream().map(route -> route.lastTarget().value()).toList()
                + "|claim=" + claimed.stream().sorted().toList()
                + "|regions=" + ordered.stream().map(StrategicRouteCandidate::regionSequence).toList()
                + "|load=" + ordered.stream().map(StrategicRouteCandidate::length).toList();
    }

    @Override
    public String toString() {
        return "team[" + routeSignatures() + "] own=" + securedCollections + " opt=" + optimisticTotal()
                + " root=" + supportRoot.signature();
    }
}
