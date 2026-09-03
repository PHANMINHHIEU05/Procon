package vn.ptit.procon.planner.v3;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/** Immutable strategic state; debug counters and history are intentionally absent. */
public record StrategicSearchState(List<PatrolState> patrols, Map<Position, Integer> remainingStock,
        Set<Position> claimedOpportunities, int collectionEstimate, Set<BrandId> brands,
        StrategicAllocation allocation, String supportState, Map<AgentId, Set<Position>> visitedByAgent,
        String chronologyFingerprint) {
    public StrategicSearchState(List<PatrolState> patrols, Map<Position, Integer> remainingStock,
            Set<Position> claimedOpportunities, int collectionEstimate, Set<BrandId> brands,
            StrategicAllocation allocation, String supportState) {
        this(patrols, remainingStock, claimedOpportunities, collectionEstimate, brands, allocation,
                supportState, Map.of(), "");
    }
    public StrategicSearchState {
        patrols = patrols.stream().map(Objects::requireNonNull).toList();
        remainingStock = Map.copyOf(remainingStock);
        claimedOpportunities = Set.copyOf(claimedOpportunities);
        brands = Set.copyOf(brands);
        Objects.requireNonNull(allocation);
        supportState = Objects.requireNonNull(supportState);
        visitedByAgent = visitedByAgent.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
        chronologyFingerprint = Objects.requireNonNull(chronologyFingerprint);
        if (collectionEstimate < 0) throw new IllegalArgumentException("Collection estimate must be non-negative");
    }

    public String exactKey() {
        String patrolKey = patrols.stream().sorted((a, b) -> Integer.compare(a.patrolId().value(), b.patrolId().value()))
                .map(PatrolState::exactKey).collect(Collectors.joining("/"));
        String stock = remainingStock.entrySet().stream().sorted(Map.Entry.comparingByKey((a, b) -> Integer.compare(a.value(), b.value())))
                .map(e -> e.getKey().value() + "=" + e.getValue()).collect(Collectors.joining(","));
        return patrolKey + "|stock=" + stock + "|claimed=" + claimedOpportunities.stream()
                .map(Position::value).sorted().map(String::valueOf).collect(Collectors.joining(","))
                + "|brands=" + brands.stream().map(BrandId::value).sorted().collect(Collectors.joining(","))
                + "|support=" + supportState + "|visited=" + visitedByAgent.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((a, b) -> Integer.compare(a.value(), b.value())))
                .map(e -> e.getKey().value() + ":" + e.getValue().stream().map(Position::value).sorted()
                        .map(String::valueOf).collect(Collectors.joining(","))).collect(Collectors.joining("/"))
                // Allocation only influences the first commitment. Once a patrol
                // has a route, equivalent future states may merge normally.
                + (patrols.stream().allMatch(p -> p.route().isEmpty()) ? "|alloc=" + allocation.signature() : "");
    }

    public int optimisticPotential() {
        return collectionEstimate + remainingStock.values().stream().mapToInt(Integer::intValue).sum();
    }

    public record PatrolState(AgentId patrolId, Position position, int elapsed, int fuel,
            List<Position> route, List<Integer> primaryRegions, List<Integer> secondaryRegions, boolean stopped) {
        public PatrolState {
            Objects.requireNonNull(patrolId); Objects.requireNonNull(position); route = List.copyOf(route);
            primaryRegions = List.copyOf(primaryRegions); secondaryRegions = List.copyOf(secondaryRegions);
            if (elapsed < 0 || fuel < 0) throw new IllegalArgumentException("Patrol resources must be non-negative");
        }
        public String exactKey() {
            return patrolId.value() + "@" + position.value() + ":" + elapsed + ":" + fuel + ":"
                    + stopped;
        }
    }
}
