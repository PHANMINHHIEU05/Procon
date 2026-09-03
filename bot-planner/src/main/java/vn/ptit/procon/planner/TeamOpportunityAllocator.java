package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;

/** Bounded, deterministic team-level opportunity ownership portfolio. */
public final class TeamOpportunityAllocator {

    public record Opportunity(Position position, BrandId brand, int stock) {
        public Opportunity {
            Objects.requireNonNull(position, "Opportunity position must not be null");
            Objects.requireNonNull(brand, "Opportunity brand must not be null");
            if (stock < 0) {
                throw new IllegalArgumentException("Opportunity stock must be non-negative");
            }
        }

        public static Opportunity from(UdonSpot spot, int stock) {
            return new Opportunity(spot.position(), spot.brand(), stock);
        }
    }

    public record RouteCost(int steps, int fuel) {
        public RouteCost {
            if (steps < 0 || fuel < 0) {
                throw new IllegalArgumentException("Route costs must be non-negative");
            }
        }
    }

    public record Candidate(TeamOpportunityAllocation allocation, Map<AgentId, Integer> estimatedLoads) {
        public Candidate {
            Objects.requireNonNull(allocation, "Allocation must not be null");
            estimatedLoads = Map.copyOf(estimatedLoads);
        }
    }

    private static final Comparator<AgentId> AGENT_ORDER = Comparator.comparingInt(AgentId::value);
    private static final Comparator<Opportunity> OPPORTUNITY_ORDER = Comparator
            .comparingInt((Opportunity opportunity) -> opportunity.position().value())
            .thenComparing(value -> value.brand().value());

    private TeamOpportunityAllocator() {
    }

    /** Produces four seeds and at most four deterministic refinements for each seed. */
    public static List<Candidate> generate(
            List<AgentState> patrols,
            List<Opportunity> opportunities,
            BiFunction<AgentState, Position, RouteCost> routeCost,
            Function<AgentId, Integer> currentAssignedLoad) {
        Objects.requireNonNull(patrols, "Patrols must not be null");
        Objects.requireNonNull(opportunities, "Opportunities must not be null");
        Objects.requireNonNull(routeCost, "Route cost function must not be null");
        Objects.requireNonNull(currentAssignedLoad, "Current load function must not be null");
        List<AgentState> orderedPatrols = patrols.stream()
                .sorted(Comparator.comparing(agent -> agent.id().value())).toList();
        List<Opportunity> orderedOpportunities = opportunities.stream()
                .filter(opportunity -> opportunity.stock() > 0)
                .sorted(OPPORTUNITY_ORDER)
                .toList();
        Map<AgentId, Map<Position, RouteCost>> costs = costMatrix(
                orderedPatrols, orderedOpportunities, routeCost);
        List<Candidate> result = new ArrayList<>();
        for (TeamOpportunityAllocation.Seed seed : TeamOpportunityAllocation.Seed.values()) {
            Map<AgentId, List<Position>> base = switch (seed) {
                case MIN_COST_OWNERSHIP -> minCost(orderedPatrols, orderedOpportunities, costs,
                        currentAssignedLoad);
                case BALANCED_LOAD -> balanced(orderedPatrols, orderedOpportunities, costs,
                        currentAssignedLoad);
                case DISTINCT_EARLY -> distinctEarly(orderedPatrols, orderedOpportunities, costs,
                        currentAssignedLoad);
                case BRAND_COVERAGE -> brandCoverage(orderedPatrols, orderedOpportunities, costs,
                        currentAssignedLoad);
            };
            result.add(candidate(seed, 0, base, costs));
            Map<AgentId, List<Position>> working = copy(base);
            for (int refinement = 1; refinement <= 3; refinement++) {
                working = refine(refinement, orderedPatrols, orderedOpportunities, working, costs,
                        currentAssignedLoad);
                result.add(candidate(seed, refinement, working, costs));
            }
        }
        return List.copyOf(result);
    }

    private static Map<AgentId, Map<Position, RouteCost>> costMatrix(
            List<AgentState> patrols,
            List<Opportunity> opportunities,
            BiFunction<AgentState, Position, RouteCost> routeCost) {
        Map<AgentId, Map<Position, RouteCost>> costs = new LinkedHashMap<>();
        for (AgentState patrol : patrols) {
            Map<Position, RouteCost> byPosition = new LinkedHashMap<>();
            for (Opportunity opportunity : opportunities) {
                RouteCost cost = routeCost.apply(patrol, opportunity.position());
                if (cost != null) {
                    byPosition.put(opportunity.position(), cost);
                }
            }
            costs.put(patrol.id(), byPosition);
        }
        return costs;
    }

    private static Map<AgentId, List<Position>> minCost(
            List<AgentState> patrols, List<Opportunity> opportunities,
            Map<AgentId, Map<Position, RouteCost>> costs,
            Function<AgentId, Integer> currentLoad) {
        Map<AgentId, List<Position>> assigned = empty(patrols);
        Map<AgentId, Integer> loads = loads(patrols, currentLoad);
        for (Opportunity opportunity : opportunities) {
            bestOwner(opportunity, patrols, costs, loads, false).ifPresent(owner -> {
                assigned.get(owner).add(opportunity.position());
                loads.merge(owner, cost(costs, owner, opportunity.position()).steps(), Integer::sum);
            });
        }
        return assigned;
    }

    private static Map<AgentId, List<Position>> balanced(
            List<AgentState> patrols, List<Opportunity> opportunities,
            Map<AgentId, Map<Position, RouteCost>> costs,
            Function<AgentId, Integer> currentLoad) {
        Map<AgentId, List<Position>> assigned = empty(patrols);
        Map<AgentId, Integer> loads = loads(patrols, currentLoad);
        for (Opportunity opportunity : opportunities) {
            bestOwner(opportunity, patrols, costs, loads, true).ifPresent(owner -> {
                assigned.get(owner).add(opportunity.position());
                loads.merge(owner, cost(costs, owner, opportunity.position()).steps(), Integer::sum);
            });
        }
        return assigned;
    }

    private static Map<AgentId, List<Position>> distinctEarly(
            List<AgentState> patrols, List<Opportunity> opportunities,
            Map<AgentId, Map<Position, RouteCost>> costs,
            Function<AgentId, Integer> currentLoad) {
        Map<AgentId, List<Position>> assigned = empty(patrols);
        Set<Position> used = new LinkedHashSet<>();
        Map<AgentId, Integer> loads = loads(patrols, currentLoad);
        for (AgentState patrol : patrols) {
            opportunities.stream()
                    .filter(opportunity -> !used.contains(opportunity.position()))
                    .filter(opportunity -> costs.getOrDefault(patrol.id(), Map.of())
                            .containsKey(opportunity.position()))
                    .min(costComparator(patrol.id(), costs, loads))
                    .ifPresent(opportunity -> {
                        assigned.get(patrol.id()).add(opportunity.position());
                        used.add(opportunity.position());
                        loads.merge(patrol.id(), cost(costs, patrol.id(), opportunity.position()).steps(),
                                Integer::sum);
                    });
        }
        for (Opportunity opportunity : opportunities) {
            if (used.contains(opportunity.position())) {
                continue;
            }
            bestOwner(opportunity, patrols, costs, loads, false).ifPresent(owner -> {
                assigned.get(owner).add(opportunity.position());
                loads.merge(owner, cost(costs, owner, opportunity.position()).steps(), Integer::sum);
            });
        }
        return assigned;
    }

    private static Map<AgentId, List<Position>> brandCoverage(
            List<AgentState> patrols, List<Opportunity> opportunities,
            Map<AgentId, Map<Position, RouteCost>> costs,
            Function<AgentId, Integer> currentLoad) {
        Map<AgentId, List<Position>> assigned = empty(patrols);
        Map<AgentId, Integer> loads = loads(patrols, currentLoad);
        Set<BrandId> covered = new LinkedHashSet<>();
        List<Opportunity> prioritized = opportunities.stream()
                .sorted(Comparator.comparing((Opportunity opportunity) -> covered.contains(opportunity.brand()))
                        .thenComparing(OPPORTUNITY_ORDER))
                .toList();
        for (Opportunity opportunity : prioritized) {
            bestOwner(opportunity, patrols, costs, loads, !covered.contains(opportunity.brand()))
                    .ifPresent(owner -> {
                        assigned.get(owner).add(opportunity.position());
                        loads.merge(owner, cost(costs, owner, opportunity.position()).steps(), Integer::sum);
                        covered.add(opportunity.brand());
                    });
        }
        return assigned;
    }

    private static Map<AgentId, List<Position>> refine(
            int refinement, List<AgentState> patrols, List<Opportunity> opportunities,
            Map<AgentId, List<Position>> source,
            Map<AgentId, Map<Position, RouteCost>> costs,
            Function<AgentId, Integer> currentLoad) {
        Map<AgentId, List<Position>> refined = copy(source);
        if (patrols.isEmpty() || opportunities.isEmpty()) {
            return refined;
        }
        if (refinement == 1 || refinement == 2) {
            List<Position> owned = refined.values().stream().flatMap(List::stream)
                    .sorted(Comparator.comparingInt(Position::value)).toList();
            if (!owned.isEmpty()) {
                Position target = owned.get((refinement - 1) % owned.size());
                AgentId from = ownerOf(refined, target);
                AgentId to = patrols.stream().map(AgentState::id)
                        .filter(id -> !id.equals(from) && costs.getOrDefault(id, Map.of()).containsKey(target))
                        .min(Comparator.comparingInt((AgentId id) -> cost(costs, id, target).steps())
                                .thenComparingInt(AgentId::value)).orElse(null);
                if (to != null) {
                    refined.get(from).remove(target);
                    refined.get(to).add(target);
                }
            }
        } else if (refinement == 3 && patrols.size() > 1) {
            AgentId first = patrols.get(0).id();
            AgentId second = patrols.get(1).id();
            if (!refined.get(first).isEmpty() && !refined.get(second).isEmpty()) {
                Position a = refined.get(first).get(0);
                Position b = refined.get(second).get(0);
                if (costs.getOrDefault(first, Map.of()).containsKey(b)
                        && costs.getOrDefault(second, Map.of()).containsKey(a)) {
                    refined.get(first).set(0, b);
                    refined.get(second).set(0, a);
                }
            }
        } else if (refinement == 4) {
            Map<AgentId, List<Position>> coverage = brandCoverage(
                    patrols, opportunities, costs, currentLoad);
            if (coverage.values().stream().flatMap(List::stream).distinct().count()
                    >= refined.values().stream().flatMap(List::stream).distinct().count()) {
                refined = coverage;
            }
        }
        refined.values().forEach(values -> values.sort(Comparator.comparingInt(Position::value)));
        return refined;
    }

    private static java.util.Optional<AgentId> bestOwner(
            Opportunity opportunity, List<AgentState> patrols,
            Map<AgentId, Map<Position, RouteCost>> costs,
            Map<AgentId, Integer> loads, boolean balanceFirst) {
        return patrols.stream()
                .filter(patrol -> costs.getOrDefault(patrol.id(), Map.of())
                        .containsKey(opportunity.position()))
                .min((left, right) -> {
                    RouteCost a = cost(costs, left.id(), opportunity.position());
                    RouteCost b = cost(costs, right.id(), opportunity.position());
                    int compared = balanceFirst
                            ? Integer.compare(loads.get(left.id()) + a.steps(),
                                    loads.get(right.id()) + b.steps())
                            : Integer.compare(a.steps(), b.steps());
                    if (compared != 0) return compared;
                    compared = Integer.compare(a.fuel(), b.fuel());
                    return compared != 0 ? compared : AGENT_ORDER.compare(left.id(), right.id());
                })
                .map(AgentState::id);
    }

    private static Comparator<Opportunity> costComparator(
            AgentId id, Map<AgentId, Map<Position, RouteCost>> costs, Map<AgentId, Integer> loads) {
        return Comparator.comparingInt((Opportunity opportunity) ->
                loads.get(id) + cost(costs, id, opportunity.position()).steps())
                .thenComparingInt(opportunity -> cost(costs, id, opportunity.position()).fuel())
                .thenComparing(OPPORTUNITY_ORDER);
    }

    private static Candidate candidate(
            TeamOpportunityAllocation.Seed seed, int refinement,
            Map<AgentId, List<Position>> assigned,
            Map<AgentId, Map<Position, RouteCost>> costs) {
        Map<AgentId, Integer> loads = new LinkedHashMap<>();
        assigned.forEach((id, positions) -> loads.put(id, positions.stream()
                .mapToInt(position -> cost(costs, id, position).steps()).sum()));
        return new Candidate(new TeamOpportunityAllocation(seed, refinement, assigned), loads);
    }

    private static Map<AgentId, List<Position>> empty(List<AgentState> patrols) {
        Map<AgentId, List<Position>> result = new LinkedHashMap<>();
        patrols.stream().map(AgentState::id).sorted(AGENT_ORDER)
                .forEach(id -> result.put(id, new ArrayList<>()));
        return result;
    }

    private static Map<AgentId, Integer> loads(
            List<AgentState> patrols, Function<AgentId, Integer> currentLoad) {
        return patrols.stream().collect(Collectors.toMap(
                AgentState::id, patrol -> currentLoad.apply(patrol.id()),
                Integer::sum, LinkedHashMap::new));
    }

    private static Map<AgentId, List<Position>> copy(Map<AgentId, List<Position>> source) {
        Map<AgentId, List<Position>> result = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey(AGENT_ORDER))
                .forEach(entry -> result.put(entry.getKey(), new ArrayList<>(entry.getValue())));
        return result;
    }

    private static AgentId ownerOf(Map<AgentId, List<Position>> assigned, Position target) {
        return assigned.entrySet().stream().filter(entry -> entry.getValue().contains(target))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static RouteCost cost(
            Map<AgentId, Map<Position, RouteCost>> costs, AgentId id, Position position) {
        return costs.getOrDefault(id, Map.of()).get(position);
    }
}
