package vn.ptit.procon.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.rules.MovementRules;

/**
 * M13.1 cached next-day orienteering model.
 *
 * <p>One reverse Pareto route search is built per static Udon opportunity. ROAD timing uses the
 * explicit optimistic {@link TrafficStatus#CLEAR} future assumption; fuel always uses official
 * source-terrain PATROL cost. Complete-plan evaluation performs no pathfinding: it reads cached
 * start-to-opportunity and opportunity-to-opportunity labels, then runs a sparse subset DP.</p>
 */
public final class NextDayHarvestCapacityCalculator {

    /** Technical bound for the exact {@code 2^n * n} DP; live configurations currently contain 8. */
    public static final int MAX_EXACT_OPPORTUNITIES = 16;

    private final DayState state;
    private final int remainingFutureDays;
    private final int nextDayStepBudget;
    private final List<UdonSpot> opportunities;
    private final Map<Position, Map<Position, List<RouteCost>>> costsByGoal;
    private final int routeCostCacheEntries;
    private final int pathfindingExecutions;

    private NextDayHarvestCapacityCalculator(
            DayState state,
            int remainingFutureDays,
            int nextDayStepBudget,
            List<UdonSpot> opportunities,
            Map<Position, Map<Position, List<RouteCost>>> costsByGoal,
            int pathfindingExecutions) {
        this.state = state;
        this.remainingFutureDays = remainingFutureDays;
        this.nextDayStepBudget = nextDayStepBudget;
        this.opportunities = opportunities;
        this.costsByGoal = costsByGoal;
        this.pathfindingExecutions = pathfindingExecutions;
        this.routeCostCacheEntries = costsByGoal.values().stream()
                .mapToInt(Map::size).sum();
    }

    public static NextDayHarvestCapacityCalculator forState(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        int remaining = Math.max(0,
                state.matchData().dayStepBudgets().dayCount() - state.day().value() - 1);
        int budget = remaining == 0 ? 0 : state.matchData().dayStepBudgets().stepsFor(
                new DayIndex(state.day().value() + 1));
        List<UdonSpot> ordered = state.matchData().udonSpots().stream()
                .sorted(Comparator.comparingInt(value -> value.position().value())).toList();
        if (ordered.size() > MAX_EXACT_OPPORTUNITIES) {
            throw new IllegalArgumentException("Exact next-day harvest DP supports at most "
                    + MAX_EXACT_OPPORTUNITIES + " static opportunities, got " + ordered.size());
        }
        if (remaining == 0) {
            return new NextDayHarvestCapacityCalculator(
                    state, 0, 0, ordered, Map.of(), 0);
        }
        Map<Position, Map<Position, List<RouteCost>>> cache = new HashMap<>();
        for (UdonSpot spot : ordered) {
            cache.put(spot.position(), reverseParetoCosts(state, spot.position(), budget));
        }
        return new NextDayHarvestCapacityCalculator(
                state, remaining, budget, ordered, Map.copyOf(cache), ordered.size());
    }

    public TeamNextDayHarvestCapacity evaluate(ValidDaySimulationResult simulation) {
        Objects.requireNonNull(simulation, "Simulation result must not be null");
        List<PatrolNextDayHarvestCapacity> patrols = new ArrayList<>();
        for (AgentState agent : simulation.finalAgents().stream()
                .sorted(Comparator.comparingInt(value -> value.id().value())).toList()) {
            if (agent.kind() != AgentKind.PATROL) {
                continue;
            }
            int fuel = ((FiniteFuel) agent.fuel()).amount();
            patrols.add(remainingFutureDays == 0
                    ? new PatrolNextDayHarvestCapacity(
                            agent.id(), agent.position(), fuel, 0, false, 0, 0, 0, 0)
                    : capacity(agent, fuel));
        }
        return TeamNextDayHarvestCapacity.aggregate(
                remainingFutureDays, nextDayStepBudget, patrols,
                routeCostCacheEntries, pathfindingExecutions);
    }

    public int routeCostCacheEntries() {
        return routeCostCacheEntries;
    }

    public int pathfindingExecutions() {
        return pathfindingExecutions;
    }

    public int opportunityCount() {
        return opportunities.size();
    }

    private PatrolNextDayHarvestCapacity capacity(AgentState agent, int fuel) {
        int count = opportunities.size();
        if (count == 0) {
            return new PatrolNextDayHarvestCapacity(
                    agent.id(), agent.position(), fuel, remainingFutureDays,
                    false, 0, 0, fuel, 0);
        }
        Map<DpKey, List<RouteCost>> states = new HashMap<>();
        ArrayDeque<DpKey> work = new ArrayDeque<>();
        boolean stationary = false;
        for (int spotIndex = 0; spotIndex < count; spotIndex++) {
            UdonSpot spot = opportunities.get(spotIndex);
            if (spot.position().equals(agent.position())) {
                stationary = true;
            }
            for (RouteCost cost : costs(agent.position(), spot.position())) {
                if (cost.steps <= nextDayStepBudget && cost.fuel <= fuel) {
                    DpKey key = new DpKey(1 << spotIndex, spotIndex);
                    if (addPareto(states, key, cost)) {
                        work.addLast(key);
                    }
                }
            }
        }
        int stateVisits = 0;
        while (!work.isEmpty()) {
            DpKey current = work.removeFirst();
            List<RouteCost> labels = List.copyOf(states.getOrDefault(current, List.of()));
            Position start = opportunities.get(current.lastSpot).position();
            for (RouteCost prefix : labels) {
                stateVisits++;
                for (int next = 0; next < count; next++) {
                    if ((current.visitedMask & 1 << next) != 0) {
                        continue;
                    }
                    for (RouteCost leg : costs(start, opportunities.get(next).position())) {
                        RouteCost combined = new RouteCost(
                                prefix.steps + leg.steps, prefix.fuel + leg.fuel);
                        if (combined.steps > nextDayStepBudget || combined.fuel > fuel) {
                            continue;
                        }
                        DpKey key = new DpKey(current.visitedMask | 1 << next, next);
                        if (addPareto(states, key, combined)) {
                            work.addLast(key);
                        }
                    }
                }
            }
        }

        int maxSpots = 0;
        int maxBrands = 0;
        int bestRemainingFuel = fuel;
        for (Map.Entry<DpKey, List<RouteCost>> entry : states.entrySet()) {
            int spots = Integer.bitCount(entry.getKey().visitedMask);
            int brands = brandCount(entry.getKey().visitedMask);
            int leastFuel = entry.getValue().stream().mapToInt(RouteCost::fuel).min().orElse(fuel);
            if (spots > maxSpots) {
                maxSpots = spots;
                bestRemainingFuel = fuel - leastFuel;
            } else if (spots == maxSpots) {
                bestRemainingFuel = Math.max(bestRemainingFuel, fuel - leastFuel);
            }
            maxBrands = Math.max(maxBrands, brands);
        }
        return new PatrolNextDayHarvestCapacity(
                agent.id(), agent.position(), fuel, remainingFutureDays, stationary,
                maxSpots, maxBrands, bestRemainingFuel, stateVisits);
    }

    private int brandCount(int mask) {
        List<BrandId> brands = new ArrayList<>();
        for (int index = 0; index < opportunities.size(); index++) {
            if ((mask & 1 << index) != 0 && !brands.contains(opportunities.get(index).brand())) {
                brands.add(opportunities.get(index).brand());
            }
        }
        return brands.size();
    }

    private List<RouteCost> costs(Position start, Position goal) {
        return costsByGoal.getOrDefault(goal, Map.of()).getOrDefault(start, List.of());
    }

    private static boolean addPareto(
            Map<DpKey, List<RouteCost>> states, DpKey key, RouteCost candidate) {
        List<RouteCost> labels = states.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (labels.stream().anyMatch(value -> value.dominates(candidate))) {
            return false;
        }
        labels.removeIf(candidate::dominates);
        labels.add(candidate);
        labels.sort(Comparator.comparingInt(RouteCost::steps).thenComparingInt(RouteCost::fuel));
        return true;
    }

    private static Map<Position, List<RouteCost>> reverseParetoCosts(
            DayState state, Position goal, int maxSteps) {
        HexMap map = state.matchData().map();
        Map<Position, List<RouteCost>> labels = new HashMap<>();
        PriorityQueue<RouteLabel> queue = new PriorityQueue<>(Comparator
                .comparingInt((RouteLabel value) -> value.cost.steps)
                .thenComparingInt(value -> value.cost.fuel)
                .thenComparingInt(value -> value.position.value()));
        RouteCost zero = new RouteCost(0, 0);
        labels.put(goal, new ArrayList<>(List.of(zero)));
        queue.add(new RouteLabel(goal, zero));
        while (!queue.isEmpty()) {
            RouteLabel current = queue.poll();
            if (!labels.getOrDefault(current.position, List.of()).contains(current.cost)) {
                continue;
            }
            for (Position predecessor : map.neighbors(current.position)) {
                if (!map.isTraversable(predecessor)) {
                    continue;
                }
                TrafficStatus traffic = map.terrainAt(predecessor) == Terrain.ROAD
                        ? TrafficStatus.CLEAR : null;
                MoveCost move = MovementRules.costFromSource(map, predecessor, traffic).orElse(null);
                if (move == null) {
                    continue;
                }
                RouteCost candidate = new RouteCost(
                        current.cost.steps + move.stepCost(),
                        current.cost.fuel + move.patrolFuelCost());
                if (candidate.steps > maxSteps) {
                    continue;
                }
                List<RouteCost> existing = labels.computeIfAbsent(predecessor, ignored -> new ArrayList<>());
                if (existing.stream().anyMatch(value -> value.dominates(candidate))) {
                    continue;
                }
                existing.removeIf(candidate::dominates);
                existing.add(candidate);
                existing.sort(Comparator.comparingInt(RouteCost::steps).thenComparingInt(RouteCost::fuel));
                queue.add(new RouteLabel(predecessor, candidate));
            }
        }
        Map<Position, List<RouteCost>> immutable = new HashMap<>();
        labels.forEach((position, costs) -> immutable.put(position, List.copyOf(costs)));
        return Map.copyOf(immutable);
    }

    private record DpKey(int visitedMask, int lastSpot) { }

    private record RouteCost(int steps, int fuel) {
        private boolean dominates(RouteCost other) {
            return steps <= other.steps && fuel <= other.fuel;
        }
    }

    private record RouteLabel(Position position, RouteCost cost) { }
}