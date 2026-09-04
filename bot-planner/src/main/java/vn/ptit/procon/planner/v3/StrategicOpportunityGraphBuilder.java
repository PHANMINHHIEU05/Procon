package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.planner.WeightedRouteFinder;

/** Builds the V3 graph once; all oracle expansion thereafter is cache-only. */
public final class StrategicOpportunityGraphBuilder {
    private final WeightedRouteFinder routeFinder;
    public StrategicOpportunityGraphBuilder() { this(new WeightedRouteFinder()); }
    StrategicOpportunityGraphBuilder(WeightedRouteFinder routeFinder) { this.routeFinder = routeFinder; }

    public StrategicOpportunityGraph build(DayState state) {
        return build(state, V3EdgeRetentionPolicy.CURRENT_PHASE0);
    }

    /** Builds the route cache once, then retains only the requested bounded edge portfolio. */
    public StrategicOpportunityGraph build(DayState state, V3EdgeRetentionPolicy policy) {
        List<UdonSpot> spots = state.matchData().udonSpots().stream()
                .sorted(Comparator.comparingInt(value -> value.position().value())).toList();
        List<StrategicOpportunity> opportunities = new ArrayList<>();
        for (UdonSpot spot : spots) {
            int nearby = (int) spots.stream().filter(other -> distance(state.matchData().map(), other.position(), spot.position()) <= 3).count() - 1;
            int density = spots.stream().filter(other -> distance(state.matchData().map(), other.position(), spot.position()) <= 3)
                    .mapToInt(other -> state.spotStock().getOrDefault(other.position(), 0)).sum();
            int brands = (int) spots.stream().filter(other -> distance(state.matchData().map(), other.position(), spot.position()) <= 3)
                    .map(UdonSpot::brand).distinct().count();
            Map<Integer, Integer> travel = new LinkedHashMap<>();
            for (AgentState agent : state.agents()) if (agent.kind() == AgentKind.PATROL) {
                routeFinder.find(state, agent, spot.position()).ifPresent(route -> travel.put(agent.id().value(), route.stepsUsed()));
            }
            opportunities.add(new StrategicOpportunity(spot.position(), spot.brand(), spot.stockCapacity(),
                    state.spotStock().getOrDefault(spot.position(), 0), nearby, density, brands,
                    opponentPressure(state, spot.position()), travel.values().stream().min(Integer::compareTo).orElse(state.stepBudget() + 1),
                    0, travel));
        }
        Map<Position, Map<Position, Route>> routes = new LinkedHashMap<>();
        int pathfinding = 0;
        AgentState routeAgent = AgentState.patrol(new AgentId(0), new Position(0), state.matchData().patrolFuelCapacity().value());
        for (StrategicOpportunity from : opportunities) {
            Map<Position, Route> byGoal = new LinkedHashMap<>();
            for (StrategicOpportunity to : opportunities) if (!from.position().equals(to.position())) {
                var found = routeFinder.find(state, routeAgentAt(routeAgent, from.position()), to.position());
                pathfinding++;
                found.ifPresent(route -> byGoal.put(to.position(), route));
            }
            routes.put(from.position(), byGoal);
        }
        Map<AgentId, Map<Position, Route>> agentRoutes = new LinkedHashMap<>();
        Map<AgentId, Map<Position, Route>> supportAwareAgentRoutes = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) if (agent.kind() == AgentKind.PATROL) {
            Map<Position, Route> byGoal = new LinkedHashMap<>();
            Map<Position, Route> liftedByGoal = new LinkedHashMap<>();
            // PART 10: the lifted probe keeps the real kind, the real cell, the real map and the real
            // traffic. Only the tank is raised, and only to the capacity the rules already allow.
            AgentState lifted = routeAgentAt(agent, agent.position(),
                    state.matchData().patrolFuelCapacity().value());
            for (StrategicOpportunity opportunity : opportunities) {
                var found = routeFinder.find(state, agent, opportunity.position());
                pathfinding++;
                found.ifPresent(route -> byGoal.put(opportunity.position(), route));
                // PART 9: geometry only. A route appearing here is NOT declared legal by appearing here.
                var liftedFound = routeFinder.find(state, lifted, opportunity.position());
                pathfinding++;
                liftedFound.ifPresent(route -> liftedByGoal.put(opportunity.position(), route));
            }
            agentRoutes.put(agent.id(), byGoal);
            supportAwareAgentRoutes.put(agent.id(), liftedByGoal);
        }
        Map<Position, List<StrategicOpportunityGraph.OpportunityEdge>> completeEdges = new LinkedHashMap<>();
        for (StrategicOpportunity from : opportunities) {
            List<StrategicOpportunityGraph.OpportunityEdge> list = new ArrayList<>();
            for (StrategicOpportunity to : opportunities) {
                Route route = routes.getOrDefault(from.position(), Map.of()).get(to.position());
                if (route != null) list.add(new StrategicOpportunityGraph.OpportunityEdge(from, to, route,
                        from.brand().equals(to.brand()), !from.brand().equals(to.brand()),
                        state.stepBudget() - route.stepsUsed()));
            }
            completeEdges.put(from.position(), list);
        }
        List<StrategicOpportunity> enriched = opportunities.stream().map(value -> new StrategicOpportunity(
                value.position(), value.brand(), value.initialStock(), value.currentStock(), value.nearbyOpportunityCount(),
                value.localStockDensity(), value.brandDiversityNearby(), value.opponentPressure(), value.ownAccessibility(),
                completeEdges.getOrDefault(value.position(), List.of()).size(), value.estimatedTravelCostByAgent())).toList();
        // Regions are descriptive and are intentionally computed from the complete cache,
        // never from the retained portfolio.
        List<OpportunityRegion> regions = regions(enriched, completeEdges, state);
        Map<Position, List<StrategicOpportunityGraph.OpportunityEdge>> retained = retainEdges(
                enriched, completeEdges, regions, state, policy);
        Map<Position, List<StrategicOpportunityGraph.OpportunityEdge>> categorized = new LinkedHashMap<>();
        for (StrategicOpportunity from : enriched) {
            List<StrategicOpportunityGraph.OpportunityEdge> values = retained.getOrDefault(from.position(), List.of()).stream()
                    .map(edge -> new StrategicOpportunityGraph.OpportunityEdge(edge.from(), edge.to(), edge.route(),
                            edge.sameBrand(), edge.differentBrand(), edge.estimatedRemainingBudgetImpact(),
                            category(edge, regions, state)))
                    .sorted(Comparator.comparingInt((StrategicOpportunityGraph.OpportunityEdge edge) -> edge.to().position().value()))
                    .toList();
            categorized.put(from.position(), values);
        }
        return new StrategicOpportunityGraph(enriched, categorized, regions, agentRoutes, routes, pathfinding,
                completeEdges.values().stream().mapToInt(List::size).sum(), policy, supportAwareAgentRoutes);
    }

    private static Map<Position, List<StrategicOpportunityGraph.OpportunityEdge>> retainEdges(
            List<StrategicOpportunity> opportunities,
            Map<Position, List<StrategicOpportunityGraph.OpportunityEdge>> complete,
            List<OpportunityRegion> regions, DayState state, V3EdgeRetentionPolicy policy) {
        Map<Position, List<StrategicOpportunityGraph.OpportunityEdge>> result = new LinkedHashMap<>();
        for (StrategicOpportunity from : opportunities) {
            List<StrategicOpportunityGraph.OpportunityEdge> candidates = complete.getOrDefault(from.position(), List.of());
            if (policy == V3EdgeRetentionPolicy.CURRENT_PHASE0) {
                result.put(from.position(), List.copyOf(candidates));
            } else if (policy == V3EdgeRetentionPolicy.NEAREST_ONLY) {
                result.put(from.position(), candidates.stream().sorted(edgeOrder()).limit(2).toList());
            } else {
                if (candidates.size() <= 12) {
                    result.put(from.position(), List.copyOf(candidates));
                    continue;
                }
                List<StrategicOpportunityGraph.OpportunityEdge> selected = new ArrayList<>();
                for (StrategicEdgeCategory kind : StrategicEdgeCategory.values()) {
                    candidates.stream().filter(edge -> category(edge, regions, state) == kind)
                            .sorted(edgeOrder()).limit(4).forEach(edge -> addIfAbsent(selected, edge));
                }
                selected.sort(edgeOrder());
                result.put(from.position(), List.copyOf(selected.stream().limit(12).toList()));
            }
        }
        return result;
    }

    private static void addIfAbsent(List<StrategicOpportunityGraph.OpportunityEdge> edges,
            StrategicOpportunityGraph.OpportunityEdge candidate) {
        if (edges.stream().noneMatch(edge -> edge.to().position().equals(candidate.to().position()))) edges.add(candidate);
    }

    private static Comparator<StrategicOpportunityGraph.OpportunityEdge> edgeOrder() {
        return Comparator.comparingInt((StrategicOpportunityGraph.OpportunityEdge edge) -> edge.route().stepsUsed())
                .thenComparingInt(edge -> edge.route().fuelUsed())
                .thenComparingInt(edge -> edge.to().currentStock()).reversed()
                .thenComparingInt(edge -> edge.to().position().value());
    }

    private static StrategicEdgeCategory category(StrategicOpportunityGraph.OpportunityEdge edge,
            List<OpportunityRegion> regions, DayState state) {
        boolean cross = regionOf(regions, edge.from().position()) != regionOf(regions, edge.to().position());
        int threshold = Math.max(3, state.stepBudget() / 5);
        if (cross && edge.route().stepsUsed() > threshold) return StrategicEdgeCategory.LONG_HOP_FRESH_REGION;
        if (cross) return StrategicEdgeCategory.DIFFERENT_REGION;
        int maxStock = state.spotStock().values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (edge.to().currentStock() >= Math.max(2, maxStock)) return StrategicEdgeCategory.HIGH_STOCK;
        if (edge.differentBrand()) return StrategicEdgeCategory.MISSING_BRAND;
        return StrategicEdgeCategory.LOCAL;
    }

    private static int regionOf(List<OpportunityRegion> regions, Position position) {
        return regions.stream().filter(region -> region.members().stream()
                .anyMatch(value -> value.position().equals(position)))
                .mapToInt(OpportunityRegion::regionId).findFirst().orElse(-1);
    }

    private static AgentState routeAgentAt(AgentState base, Position position) {
        return AgentState.patrol(base.id(), position, ((FiniteFuel) base.fuel()).amount());
    }

    /** PART 10: same kind, same cell, lifted tank. Nothing else about the probe changes. */
    private static AgentState routeAgentAt(AgentState base, Position position, int fuel) {
        return AgentState.patrol(base.id(), position, fuel);
    }

    private static int opponentPressure(DayState state, Position position) {
        return (int) state.observedOthers().stream().flatMap(group -> group.agents().stream())
                .filter(agent -> distance(state.matchData().map(), agent.position(), position) <= state.stepBudget()).count();
    }

    private static int distance(HexMap map, Position left, Position right) {
        return Math.abs(map.rowOf(left) - map.rowOf(right)) + Math.abs(map.columnOf(left) - map.columnOf(right));
    }

    private static List<OpportunityRegion> regions(List<StrategicOpportunity> opportunities,
            Map<Position, List<StrategicOpportunityGraph.OpportunityEdge>> edges, DayState state) {
        int threshold = Math.max(3, state.stepBudget() / 5);
        Map<Position, Set<Position>> adjacency = new LinkedHashMap<>();
        opportunities.forEach(value -> adjacency.put(value.position(), new LinkedHashSet<>()));
        for (var entry : edges.entrySet()) for (var edge : entry.getValue()) {
            if (edge.route().stepsUsed() <= threshold) adjacency.get(entry.getKey()).add(edge.to().position());
        }
        Set<Position> seen = new LinkedHashSet<>();
        List<OpportunityRegion> result = new ArrayList<>();
        int id = 0;
        for (StrategicOpportunity seed : opportunities) {
            if (!seen.add(seed.position())) continue;
            List<StrategicOpportunity> members = new ArrayList<>();
            List<Position> queue = new ArrayList<>(List.of(seed.position()));
            for (int index = 0; index < queue.size(); index++) {
                Position position = queue.get(index);
                opportunities.stream().filter(value -> value.position().equals(position)).findFirst().ifPresent(members::add);
                for (Position next : adjacency.getOrDefault(position, Set.of())) if (seen.add(next)) queue.add(next);
            }
            members.sort(Comparator.comparingInt(value -> value.position().value()));
            Set<vn.ptit.procon.domain.udon.BrandId> brands = members.stream().map(StrategicOpportunity::brand).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Position representative = members.getFirst().position();
            int internal = members.stream().flatMap(a -> members.stream().filter(b -> a.position().value() < b.position().value()
                    && edges.getOrDefault(a.position(), List.of()).stream().anyMatch(e -> e.to().position().equals(b.position()))))
                    .mapToInt(a -> 1).sum();
            int best = members.stream().mapToInt(StrategicOpportunity::ownAccessibility).min().orElse(0);
            result.add(new OpportunityRegion(id++, members, members.stream().mapToInt(StrategicOpportunity::currentStock).sum(),
                    brands, representative, internal, best, members.stream().mapToInt(StrategicOpportunity::opponentPressure).max().orElse(0),
                    members.stream().limit(Math.min(4, members.size())).toList(), members.stream()
                            .sorted(Comparator.comparingInt(StrategicOpportunity::continuationDegree).reversed()
                                    .thenComparingInt(StrategicOpportunity::currentStock).reversed()
                                    .thenComparingInt(value -> value.position().value()))
                            .limit(Math.min(4, members.size())).toList()));
        }
        return List.copyOf(result);
    }
}
