package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** Immutable M19 logical claim allocation. Claims may share a spot across PATROLs. */
public final class CapacityAwareTeamAllocation {

    public enum Seed {
        CAPACITY_THROUGHPUT,
        CAPACITY_BALANCED,
        CAPACITY_BRAND,
        COMPETITIVE_RESIDUAL
    }

    private final Seed seed;
    private final int variant;
    private final Map<AgentId, List<CollectionClaim>> claimsByPatrol;
    private final String signature;

    public CapacityAwareTeamAllocation(
            Seed seed, int variant, Map<AgentId, ? extends List<CollectionClaim>> claimsByPatrol) {
        this.seed = Objects.requireNonNull(seed, "Allocation seed must not be null");
        if (variant < 0 || variant > 3) {
            throw new IllegalArgumentException("M19 variant must be between 0 and 3");
        }
        this.variant = variant;
        Map<AgentId, List<CollectionClaim>> copied = new LinkedHashMap<>();
        claimsByPatrol.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(AgentId::value)))
                .forEach(entry -> {
                    AgentId id = Objects.requireNonNull(entry.getKey(), "Claim patrol must not be null");
                    List<CollectionClaim> claims = new ArrayList<>(Objects.requireNonNull(
                            entry.getValue(), "Patrol claims must not be null"));
                    claims.sort(Comparator.comparingInt(CollectionClaim::plannedArrivalStep)
                            .thenComparingInt(CollectionClaim::claimOrdinal)
                            .thenComparingInt(claim -> claim.spot().value()));
                    copied.put(id, List.copyOf(claims));
                });
        this.claimsByPatrol = Map.copyOf(copied);
        this.signature = signature(copied);
    }

    public Seed seed() { return seed; }

    public int variant() { return variant; }

    public Map<AgentId, List<CollectionClaim>> claimsByPatrol() { return claimsByPatrol; }

    public List<CollectionClaim> claimsFor(AgentId patrol) {
        return claimsByPatrol.getOrDefault(patrol, List.of());
    }

    public List<CollectionClaim> claims() {
        return claimsByPatrol.values().stream().flatMap(List::stream).toList();
    }

    public String signature() { return signature; }

    public int totalLogicalClaims() { return claims().size(); }

    public int distinctClaimedSpots() {
        return (int) claims().stream().map(CollectionClaim::spot).distinct().count();
    }

    public int multiClaimSpotCount() {
        Map<Position, Integer> counts = new LinkedHashMap<>();
        claims().forEach(claim -> counts.merge(claim.spot(), 1, Integer::sum));
        return (int) counts.values().stream().filter(count -> count > 1).count();
    }

    public Map<AgentId, Integer> claimCounts() {
        Map<AgentId, Integer> result = new LinkedHashMap<>();
        claimsByPatrol.forEach((id, claims) -> result.put(id, claims.size()));
        return Map.copyOf(result);
    }

    private static String signature(Map<AgentId, ? extends List<CollectionClaim>> claims) {
        StringBuilder result = new StringBuilder();
        claims.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(AgentId::value)))
                .forEach(entry -> {
                    result.append(entry.getKey().value()).append(':');
                    entry.getValue().stream().sorted(Comparator.comparing(CollectionClaim::signature))
                            .forEach(claim -> result.append(claim.signature()).append(','));
                    result.append(';');
                });
        return result.toString();
    }
}
