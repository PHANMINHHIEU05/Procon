package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.Route;

/**
 * The bounded per-PATROL strategic route portfolio the Phase 2.6 composition search assigns from.
 *
 * <p>Two organisational decisions, and nothing else, separate this from the enumeration it replaces.
 *
 * <p>First, the enumeration is a BEST-FIRST priority queue with an expansion budget, not a depth-limited
 * exhaustive DFS. The old oracle DFS generated 2.59 million prefixes on CURRENT LARGE just to retain 578 of
 * them; the same DFS at the bounded search's own route depth would have generated 385 million. A priority
 * queue ordered by settled collections dives instead of fanning out, so a six-hop route is reached in six
 * pops rather than after every shorter route has been enumerated. The budget here is therefore strictly
 * SMALLER than what it replaces — no cap is raised anywhere.
 *
 * <p>Second, retention is a conservative Pareto front plus bounded structural diversity, never one weighted
 * scalar (PART 5). A route survives unless another retained route is at least as good on EVERY axis that
 * matters to a future team decision — collections, brand coverage, elapsed time, remaining fuel, final cell
 * and claimed target set — so a route is never discarded merely for tying on collections.
 */
public final class StrategicRoutePortfolio {

    /** Why a generated route did not reach the retained portfolio. Reported per PATROL, never guessed. */
    public enum LossReason { ROUTE_NOT_GENERATED, ROUTE_PATH_LENGTH, ROUTE_PORTFOLIO_PRUNE, ROUTE_DOMINANCE,
        ROUTE_ORDERING, FULL_ROUTE_RETAINED }

    private StrategicRoutePortfolio() { }

    /** One PATROL's bounded portfolio plus the counters PART 2/35 require. */
    public record Portfolio(AgentId patrolId, List<StrategicRouteCandidate> routes, int generated,
            int expansions, int prunedDominated, int prunedDiversity, int prunedOrdering,
            List<StrategicRouteCandidate> generatedRoutes) {
        public Portfolio {
            Objects.requireNonNull(patrolId, "Patrol id must not be null");
            routes = List.copyOf(Objects.requireNonNull(routes, "Routes must not be null"));
            generatedRoutes = List.copyOf(Objects.requireNonNull(generatedRoutes, "Generated must not be null"));
        }

        public int retained() { return routes.size(); }

        /** The rank of {@code signature} in the retained portfolio, or {@code -1} when it is absent. */
        public int rankOf(String signature) {
            for (int index = 0; index < routes.size(); index++) {
                if (routes.get(index).signature().equals(signature)) return index;
            }
            return -1;
        }

        public boolean contains(String signature) { return rankOf(signature) >= 0; }

        /**
         * PART 2: the generated route with this exact signature, retained or not.
         *
         * <p>A recall audit has to be able to tell "never generated" from "generated and then dropped", and
         * those are different claims about different layers, so the generated list is kept verbatim.
         */
        public StrategicRouteCandidate generatedRoute(String signature) {
            return generatedRoutes.stream().filter(value -> value.signature().equals(signature)).findFirst()
                    .orElse(null);
        }

        public boolean generatedContains(String signature) { return generatedRoute(signature) != null; }

        /** PART 3: how many structurally distinct futures the portfolio actually offers. */
        public int distinctDiversityKeys() {
            return (int) routes.stream().map(StrategicRouteCandidate::diversityKey).distinct().count();
        }
    }

    /**
     * Builds one portfolio per PATROL under ONE fixed support root.
     *
     * <p>The entry geometry is whatever Phase 2.5 decided it is for this root — {@code entryRoutes(present)}
     * — and legality is decided by the existing {@link SupportAwareTrajectoryScheduler} against the root's
     * real tanker timeline. No pathfinding, no new simulator, no new support semantics.
     */
    public static Map<AgentId, Portfolio> build(DayState state, StrategicOpportunityGraph graph,
            List<AgentState> patrols, V3SupportRootContext root, SupportAwareTrajectoryScheduler scheduler,
            StrategicTrajectoryCache cache, int maxLength, int maxExpansions, int maxRetained) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(graph, "Graph must not be null");
        Objects.requireNonNull(root, "Support root must not be null");
        Objects.requireNonNull(scheduler, "Scheduler must not be null");
        Map<AgentId, Map<Position, Route>> entries = graph.entryRoutes(root.present());
        Map<Position, StrategicOpportunity> byPosition = new LinkedHashMap<>();
        graph.opportunities().forEach(value -> byPosition.put(value.position(), value));
        Map<Position, Integer> regions = new LinkedHashMap<>();
        for (OpportunityRegion region : graph.regions()) {
            region.members().forEach(member -> regions.putIfAbsent(member.position(), region.regionId()));
        }
        Map<AgentId, Portfolio> result = new LinkedHashMap<>();
        for (AgentState patrol : patrols) {
            result.put(patrol.id(), forPatrol(state, graph, entries, byPosition, regions, patrol, scheduler,
                    cache, maxLength, maxExpansions, maxRetained));
        }
        return Map.copyOf(result);
    }

    private static Portfolio forPatrol(DayState state, StrategicOpportunityGraph graph,
            Map<AgentId, Map<Position, Route>> entries, Map<Position, StrategicOpportunity> byPosition,
            Map<Position, Integer> regions, AgentState patrol, SupportAwareTrajectoryScheduler scheduler,
            StrategicTrajectoryCache cache, int maxLength, int maxExpansions, int maxRetained) {
        int startFuel = ((FiniteFuel) patrol.fuel()).amount();
        int startCollections = state.spotStock().getOrDefault(patrol.position(), 0) > 0 ? 1 : 0;
        Prefix origin = new Prefix(List.of(), List.of(), List.of(), List.of(), List.of(), 0, startFuel,
                Set.of(patrol.position()), Set.of(), startCollections, false, "");
        List<Prefix> frontier = new ArrayList<>(List.of(origin));
        List<StrategicRouteCandidate> generated = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(Set.of(""));
        int expansions = 0;
        while (!frontier.isEmpty() && expansions < maxExpansions) {
            frontier.sort(prefixOrder());
            Prefix node = frontier.removeFirst();
            expansions++;
            if (node.targets.size() >= maxLength) continue;
            for (Position target : successors(graph, entries, patrol, node)) {
                if (node.targets.contains(target)) continue;
                Route leg = node.targets.isEmpty()
                        ? entries.getOrDefault(patrol.id(), Map.of()).get(target)
                        : graph.opportunityRoutes().getOrDefault(node.targets.getLast(), Map.of()).get(target);
                if (leg == null) continue;
                CachedTrajectoryEffect effect = cache.effect(patrol.id(), leg);
                SupportAwareSchedule schedule = scheduler.schedule(node.position(patrol), node.elapsed,
                        node.fuel, effect);
                if (!schedule.feasible()) continue;
                Prefix child = node.extend(state, byPosition, regions, target, leg, effect, schedule);
                if (!seen.add(child.signature)) continue;
                frontier.add(child);
                generated.add(child.candidate(patrol.id(), patrol.position()));
            }
            if (frontier.size() > maxRetained) {
                frontier.sort(prefixOrder());
                frontier = new ArrayList<>(frontier.subList(0, maxRetained));
            }
        }
        return retain(patrol.id(), generated, expansions, maxRetained);
    }

    private static List<Position> successors(StrategicOpportunityGraph graph,
            Map<AgentId, Map<Position, Route>> entries, AgentState patrol, Prefix node) {
        if (node.targets.isEmpty()) {
            return graph.opportunities().stream().map(StrategicOpportunity::position)
                    .filter(position -> entries.getOrDefault(patrol.id(), Map.of()).containsKey(position))
                    .toList();
        }
        return graph.outgoing().getOrDefault(node.targets.getLast(), List.of()).stream()
                .map(edge -> edge.to().position()).distinct().toList();
    }

    /** Dives: more settled collections first, then longer, so a deep route is reached in depth-many pops. */
    private static Comparator<Prefix> prefixOrder() {
        return Comparator.comparingInt((Prefix value) -> value.collections).reversed()
                .thenComparing(Comparator.comparingInt((Prefix value) -> value.brands.size()).reversed())
                .thenComparing(Comparator.comparingInt((Prefix value) -> value.targets.size()).reversed())
                .thenComparingInt(value -> value.elapsed)
                .thenComparing(value -> value.signature);
    }

    /**
     * Conservative Pareto front, then bounded structural diversity, then the existing deterministic order.
     *
     * <p>PART 4/5: there is no weighted scalar anywhere. A route is dropped only when another retained route
     * is at least as good on collections, brand coverage, elapsed time and remaining fuel AND ends in the
     * same cell AND already claims everything it claims. Ties on collections alone never lose a route.
     */
    private static Portfolio retain(AgentId patrolId, List<StrategicRouteCandidate> generated, int expansions,
            int maxRetained) {
        List<StrategicRouteCandidate> ordered = new ArrayList<>(generated);
        ordered.sort(routeOrder());
        List<StrategicRouteCandidate> front = new ArrayList<>();
        int dominated = 0;
        for (StrategicRouteCandidate candidate : ordered) {
            if (front.stream().anyMatch(kept -> dominates(kept, candidate))) { dominated++; continue; }
            front.add(candidate);
        }
        List<StrategicRouteCandidate> retained = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (StrategicRouteCandidate candidate : front) {
            if (retained.size() >= maxRetained) break;
            if (keys.add(candidate.diversityKey())) retained.add(candidate);
        }
        for (StrategicRouteCandidate candidate : front) {
            if (retained.size() >= maxRetained) break;
            if (!retained.contains(candidate)) retained.add(candidate);
        }
        long keptKeys = retained.stream().map(StrategicRouteCandidate::diversityKey).distinct().count();
        long frontKeys = front.stream().map(StrategicRouteCandidate::diversityKey).distinct().count();
        return new Portfolio(patrolId, retained, generated.size(), expansions, dominated,
                (int) Math.max(0, frontKeys - keptKeys), Math.max(0, front.size() - retained.size()), ordered);
    }

    /** The existing deterministic quality order, unchanged in spirit: no new objective is introduced. */
    private static Comparator<StrategicRouteCandidate> routeOrder() {
        return Comparator.comparingInt(StrategicRouteCandidate::soloPotential).reversed()
                .thenComparing(Comparator.comparingInt((StrategicRouteCandidate value) -> value.brands().size())
                        .reversed())
                .thenComparingInt(StrategicRouteCandidate::stepsUsed)
                .thenComparing(Comparator.comparingInt(StrategicRouteCandidate::endFuel).reversed())
                .thenComparing(Comparator.comparingInt(StrategicRouteCandidate::length).reversed())
                .thenComparing(StrategicRouteCandidate::signature);
    }

    static boolean dominates(StrategicRouteCandidate left, StrategicRouteCandidate right) {
        return !left.signature().equals(right.signature())
                && left.soloPotential() >= right.soloPotential()
                && left.stepsUsed() <= right.stepsUsed()
                && left.endFuel() >= right.endFuel()
                && left.endPosition().equals(right.endPosition())
                && left.brands().containsAll(right.brands())
                && left.targetSet().containsAll(right.targetSet());
    }

    /** One partial per-PATROL route under construction. Deliberately carries no diagnostic history. */
    private static final class Prefix {
        private final List<Position> targets;
        private final List<Route> legs;
        private final List<BrandId> brandSequence;
        private final List<Integer> regionSequence;
        private final List<Integer> arrivals;
        private final int elapsed;
        private final int fuel;
        private final Set<Position> visited;
        private final Set<BrandId> brands;
        private final int collections;
        private final boolean supportDependent;
        private final String signature;

        private Prefix(List<Position> targets, List<Route> legs, List<BrandId> brandSequence,
                List<Integer> regionSequence, List<Integer> arrivals, int elapsed, int fuel,
                Set<Position> visited, Set<BrandId> brands, int collections, boolean supportDependent,
                String signature) {
            this.targets = targets; this.legs = legs; this.brandSequence = brandSequence;
            this.regionSequence = regionSequence; this.arrivals = arrivals; this.elapsed = elapsed;
            this.fuel = fuel; this.visited = visited; this.brands = brands; this.collections = collections;
            this.supportDependent = supportDependent; this.signature = signature;
        }

        private Position position(AgentState patrol) {
            return targets.isEmpty() ? patrol.position() : targets.getLast();
        }

        private Prefix extend(DayState state, Map<Position, StrategicOpportunity> byPosition,
                Map<Position, Integer> regions, Position target, Route leg, CachedTrajectoryEffect effect,
                SupportAwareSchedule schedule) {
            List<Position> nextTargets = append(targets, target);
            Set<Position> nextVisited = new LinkedHashSet<>(visited);
            effect.encounters().forEach(encounter -> nextVisited.add(encounter.position()));
            Set<BrandId> nextBrands = new LinkedHashSet<>(brands);
            BrandId brand = byPosition.containsKey(target) ? byPosition.get(target).brand() : null;
            if (brand != null) nextBrands.add(brand);
            int settled = (int) nextVisited.stream()
                    .filter(position -> state.spotStock().getOrDefault(position, 0) > 0).count();
            return new Prefix(nextTargets, append(legs, leg),
                    brand == null ? brandSequence : append(brandSequence, brand),
                    append(regionSequence, regions.getOrDefault(target, -1)),
                    append(arrivals, schedule.scheduledEndStep()), schedule.scheduledEndStep(),
                    schedule.fuelAfter(), Set.copyOf(nextVisited), Set.copyOf(nextBrands), settled,
                    supportDependent || schedule.usedSupport() || schedule.waited(),
                    nextTargets.stream().map(position -> Integer.toString(position.value()))
                            .reduce((a, b) -> a + ">" + b).orElse(""));
        }

        private StrategicRouteCandidate candidate(AgentId patrolId, Position start) {
            return new StrategicRouteCandidate(patrolId, targets, legs, brandSequence, regionSequence,
                    arrivals, elapsed, fuel, start, targets.getLast(), collections, supportDependent,
                    signature);
        }

        private static <T> List<T> append(List<T> values, T value) {
            List<T> result = new ArrayList<>(values);
            result.add(value);
            return List.copyOf(result);
        }
    }
}
