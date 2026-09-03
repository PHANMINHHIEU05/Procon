package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;

/** M19 bounded capacity allocator. It consumes a supplied route matrix and never searches routes. */
public final class CapacityAwareTeamAllocator {

    public record RouteCost(int steps, int fuel) {
        public RouteCost {
            if (steps < 0 || fuel < 0) {
                throw new IllegalArgumentException("Route costs must be non-negative");
            }
        }
    }

    public record Timeline(
            int expectedOwnSuccessfulClaims,
            int expectedOpponentClaimsBeforeOwn,
            int expectedResidualCapacitySum) {
        public Timeline {
            if (expectedOwnSuccessfulClaims < 0 || expectedOpponentClaimsBeforeOwn < 0
                    || expectedResidualCapacitySum < 0) {
                throw new IllegalArgumentException("Capacity timeline metrics must be non-negative");
            }
        }
    }

    public record Candidate(
            CapacityAwareTeamAllocation allocation,
            Map<AgentId, Integer> estimatedLoads,
            Timeline timeline) {
        public Candidate {
            Objects.requireNonNull(allocation, "Capacity allocation must not be null");
            estimatedLoads = Map.copyOf(estimatedLoads);
            Objects.requireNonNull(timeline, "Capacity timeline must not be null");
        }
    }

    private record Slot(CollectionOpportunityCapacity capacity, int ordinal) { }

    private static final Comparator<AgentId> AGENT_ORDER = Comparator.comparingInt(AgentId::value);
    private static final Comparator<CollectionOpportunityCapacity> POSITION_ORDER = Comparator
            .comparingInt((CollectionOpportunityCapacity capacity) -> capacity.spot().value())
            .thenComparing(capacity -> capacity.brand().value());

    private CapacityAwareTeamAllocator() { }

    /** Exactly four seed families with four deterministic variants each. */
    public static List<Candidate> generate(
            List<AgentState> patrols,
            List<CollectionOpportunityCapacity> capacities,
            BiFunction<AgentState, Position, RouteCost> routeCost,
            Map<Position, ? extends List<Integer>> opponentArrivalSteps,
            int stepBudget) {
        Objects.requireNonNull(patrols, "Patrols must not be null");
        Objects.requireNonNull(capacities, "Capacities must not be null");
        Objects.requireNonNull(routeCost, "Route cost function must not be null");
        Objects.requireNonNull(opponentArrivalSteps, "Opponent arrival steps must not be null");
        if (stepBudget < 0) throw new IllegalArgumentException("Step budget must be non-negative");

        List<AgentState> orderedPatrols = patrols.stream()
                .sorted(Comparator.comparingInt(agent -> agent.id().value())).toList();
        Map<AgentId, Map<Position, RouteCost>> costs = new LinkedHashMap<>();
        for (AgentState patrol : orderedPatrols) {
            Map<Position, RouteCost> bySpot = new LinkedHashMap<>();
            for (CollectionOpportunityCapacity capacity : capacities) {
                if (!capacity.reachablePatrols().contains(patrol.id())) continue;
                RouteCost cost = routeCost.apply(patrol, capacity.spot());
                if (cost != null && cost.steps() <= stepBudget) bySpot.put(capacity.spot(), cost);
            }
            costs.put(patrol.id(), bySpot);
        }
        List<CollectionOpportunityCapacity> usable = capacities.stream()
                .filter(capacity -> capacity.availableStock() > 0)
                .filter(capacity -> capacity.reachablePatrols().stream()
                        .anyMatch(id -> costs.getOrDefault(id, Map.of()).containsKey(capacity.spot())))
                .sorted(POSITION_ORDER).toList();

        List<Candidate> result = new ArrayList<>();
        for (CapacityAwareTeamAllocation.Seed seed : CapacityAwareTeamAllocation.Seed.values()) {
            for (int variant = 0; variant < 4; variant++) {
                Map<AgentId, List<CollectionClaim>> claims = allocate(
                        orderedPatrols, usable, costs, opponentArrivalSteps, seed, variant, stepBudget);
                CapacityAwareTeamAllocation allocation = new CapacityAwareTeamAllocation(seed, variant, claims);
                result.add(new Candidate(allocation, estimatedLoads(allocation, costs),
                        timeline(allocation, usable, opponentArrivalSteps)));
            }
        }
        return List.copyOf(result);
    }

    private static Map<AgentId, List<CollectionClaim>> allocate(
            List<AgentState> patrols,
            List<CollectionOpportunityCapacity> capacities,
            Map<AgentId, Map<Position, RouteCost>> costs,
            Map<Position, ? extends List<Integer>> opponentArrivals,
            CapacityAwareTeamAllocation.Seed seed,
            int variant,
            int stepBudget) {
        Map<AgentId, List<CollectionClaim>> assigned = new LinkedHashMap<>();
        patrols.forEach(patrol -> assigned.put(patrol.id(), new ArrayList<>()));
        Map<AgentId, Integer> loads = new LinkedHashMap<>();
        patrols.forEach(patrol -> loads.put(patrol.id(), 0));
        Set<String> usedAgentSpot = new HashSet<>();
        List<Slot> slots = slots(capacities, seed, opponentArrivals);
        for (Slot slot : slots) {
            List<AgentState> eligible = patrols.stream()
                    .filter(agent -> slot.capacity().reachablePatrols().contains(agent.id()))
                    .filter(agent -> costs.getOrDefault(agent.id(), Map.of())
                            .containsKey(slot.capacity().spot()))
                    .filter(agent -> !usedAgentSpot.contains(agent.id().value() + ":"
                            + slot.capacity().spot().value()))
                    .sorted(ownerComparator(slot, costs, loads, seed, variant))
                    .toList();
            AgentState owner = eligible.stream().findFirst().orElse(null);
            if (owner == null) continue;
            RouteCost route = costs.get(owner.id()).get(slot.capacity().spot());
            if (loads.get(owner.id()) + route.steps() > stepBudget && !assigned.get(owner.id()).isEmpty()) {
                continue;
            }
            assigned.get(owner.id()).add(new CollectionClaim(
                    slot.capacity().spot(), slot.ordinal(), owner.id(), route.steps()));
            usedAgentSpot.add(owner.id().value() + ":" + slot.capacity().spot().value());
            loads.merge(owner.id(), route.steps(), Integer::sum);
        }
        return assigned;
    }

    private static List<Slot> slots(
            List<CollectionOpportunityCapacity> capacities,
            CapacityAwareTeamAllocation.Seed seed,
            Map<Position, ? extends List<Integer>> opponentArrivals) {
        List<CollectionOpportunityCapacity> ordered = new ArrayList<>(capacities);
        Comparator<CollectionOpportunityCapacity> comparator = switch (seed) {
            case CAPACITY_THROUGHPUT -> Comparator
                    .comparingInt(CollectionOpportunityCapacity::boundedClaimCapacity).reversed()
                    .thenComparing(POSITION_ORDER);
            case CAPACITY_BALANCED -> Comparator
                    .comparingInt(CollectionOpportunityCapacity::availableStock).reversed()
                    .thenComparing(POSITION_ORDER);
            case CAPACITY_BRAND -> Comparator
                    .comparing((CollectionOpportunityCapacity value) -> value.brand().value())
                    .thenComparing(POSITION_ORDER);
            case COMPETITIVE_RESIDUAL -> Comparator
                    .comparingInt((CollectionOpportunityCapacity value) ->
                            opponentArrivals.get(value.spot()) == null ? Integer.MAX_VALUE
                                    : opponentArrivals.get(value.spot()).stream()
                                    .mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE))
                    .thenComparing(POSITION_ORDER);
        };
        ordered.sort(comparator);
        List<Slot> result = new ArrayList<>();
        if (seed == CapacityAwareTeamAllocation.Seed.CAPACITY_BRAND) {
            Set<String> brands = new LinkedHashSet<>();
            for (CollectionOpportunityCapacity capacity : ordered) {
                if (brands.add(capacity.brand().value())) result.add(new Slot(capacity, 0));
            }
            for (CollectionOpportunityCapacity capacity : ordered) {
                for (int ordinal = 0; ordinal < capacity.boundedClaimCapacity(); ordinal++) {
                    if (ordinal > 0 || !result.contains(new Slot(capacity, 0))) {
                        result.add(new Slot(capacity, ordinal));
                    }
                }
            }
            return result;
        }
        for (CollectionOpportunityCapacity capacity : ordered) {
            for (int ordinal = 0; ordinal < capacity.boundedClaimCapacity(); ordinal++) {
                result.add(new Slot(capacity, ordinal));
            }
        }
        return result;
    }

    private static Comparator<AgentState> ownerComparator(
            Slot slot,
            Map<AgentId, Map<Position, RouteCost>> costs,
            Map<AgentId, Integer> loads,
            CapacityAwareTeamAllocation.Seed seed,
            int variant) {
        return (left, right) -> {
            RouteCost leftCost = costs.get(left.id()).get(slot.capacity().spot());
            RouteCost rightCost = costs.get(right.id()).get(slot.capacity().spot());
            int leftScore = loads.get(left.id()) + leftCost.steps();
            int rightScore = loads.get(right.id()) + rightCost.steps();
            if (seed == CapacityAwareTeamAllocation.Seed.CAPACITY_THROUGHPUT) {
                int compared = Integer.compare(leftCost.steps(), rightCost.steps());
                if (compared != 0) return compared;
            } else if (seed == CapacityAwareTeamAllocation.Seed.COMPETITIVE_RESIDUAL) {
                int compared = Integer.compare(leftCost.steps(), rightCost.steps());
                if (compared != 0) return compared;
            } else {
                int compared = Integer.compare(leftScore, rightScore);
                if (compared != 0) return compared;
            }
            int rotation = Math.floorMod(variant, 2);
            int compared = rotation == 0 ? AGENT_ORDER.compare(left.id(), right.id())
                    : AGENT_ORDER.compare(right.id(), left.id());
            return compared;
        };
    }

    private static Map<AgentId, Integer> estimatedLoads(
            CapacityAwareTeamAllocation allocation,
            Map<AgentId, Map<Position, RouteCost>> costs) {
        Map<AgentId, Integer> result = new LinkedHashMap<>();
        allocation.claimsByPatrol().forEach((id, claims) -> result.put(id, claims.stream()
                .mapToInt(claim -> costs.get(id).get(claim.spot()).steps()).sum()));
        return result;
    }

    private static Timeline timeline(
            CapacityAwareTeamAllocation allocation,
            List<CollectionOpportunityCapacity> capacities,
            Map<Position, ? extends List<Integer>> opponentArrivals) {
        Map<Position, Integer> initialStock = new LinkedHashMap<>();
        capacities.forEach(capacity -> initialStock.put(capacity.spot(), capacity.availableStock()));
        List<CollectionClaim> claims = allocation.claims();
        int totalSuccessful = 0;
        int totalOpponentBeforeOwn = 0;
        int totalResidual = 0;
        Set<Position> claimedSpots = claims.stream().map(CollectionClaim::spot)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (Position spot : claimedSpots) {
            List<Integer> own = claims.stream().filter(claim -> claim.spot().equals(spot))
                    .sorted(Comparator.comparingInt(CollectionClaim::plannedArrivalStep)
                            .thenComparingInt(claim -> claim.assignedPatrol().value())
                            .thenComparingInt(CollectionClaim::claimOrdinal))
                    .map(CollectionClaim::plannedArrivalStep).toList();
            List<Integer> opponents = new ArrayList<>(opponentArrivals.get(spot) == null
                    ? List.of() : opponentArrivals.get(spot));
            opponents.sort(Integer::compareTo);
            int ownIndex = 0;
            int opponentIndex = 0;
            int residual = initialStock.getOrDefault(spot, 0);
            int successful = 0;
            int opponentBeforeOwn = 0;
            while (ownIndex < own.size() || opponentIndex < opponents.size()) {
                int ownStep = ownIndex < own.size() ? own.get(ownIndex) : Integer.MAX_VALUE;
                int opponentStep = opponentIndex < opponents.size() ? opponents.get(opponentIndex)
                        : Integer.MAX_VALUE;
                if (opponentStep <= ownStep) {
                    if (residual > 0) residual--;
                    opponentIndex++;
                    if (ownStep != Integer.MAX_VALUE) opponentBeforeOwn++;
                } else {
                    if (residual > 0) {
                        residual--;
                        successful++;
                    }
                    ownIndex++;
                }
            }
            totalSuccessful += successful;
            totalOpponentBeforeOwn += opponentBeforeOwn;
            totalResidual += residual;
        }
        return new Timeline(totalSuccessful, totalOpponentBeforeOwn, totalResidual);
    }
}
