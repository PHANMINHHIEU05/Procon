package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Direction;
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
 * Cached static-geometry opportunity lookup for M13.
 *
 * <p>Future traffic is deliberately not predicted. ROAD timing uses the optimistic CLEAR lower
 * bound, while PATROL fuel uses the official terrain movement fuel cost. The result means that at
 * least one known Udon position is reachable on the next authoritative day budget under that
 * structural assumption; it does not promise future stock or collection.</p>
 */
public final class FutureReadinessCalculator {

    private final DayState state;
    private final List<UdonSpot> orderedSpots;
    private final Map<Position, Map<Position, OpportunityCost>> costsByStart;
    private final int pathfindingExecutions;
    private final int cacheEntries;

    private FutureReadinessCalculator(
            DayState state,
            List<UdonSpot> orderedSpots,
            Map<Position, Map<Position, OpportunityCost>> costsByStart,
            int pathfindingExecutions) {
        this.state = state;
        this.orderedSpots = orderedSpots;
        this.costsByStart = costsByStart;
        this.pathfindingExecutions = pathfindingExecutions;
        this.cacheEntries = costsByStart.values().stream().mapToInt(Map::size).sum();
    }

    public static FutureReadinessCalculator forState(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        List<UdonSpot> spots = state.matchData().udonSpots().stream()
                .sorted(Comparator.comparingInt(spot -> spot.position().value()))
                .toList();
        Map<Position, Map<Position, OpportunityCost>> costs = new HashMap<>();
        for (UdonSpot spot : spots) {
            costs.put(spot.position(), reverseParetoCosts(state, spot.position()));
        }
        return new FutureReadinessCalculator(state, spots, costs, spots.size());
    }

    public TeamFutureReadiness evaluate(ValidDaySimulationResult simulation) {
        Objects.requireNonNull(simulation, "Simulation result must not be null");
        int remainingDays = Math.max(0,
                state.matchData().dayStepBudgets().dayCount() - state.day().value() - 1);
        if (remainingDays == 0) {
            return zeroFuture(simulation, remainingDays);
        }
        int nextDayBudget = state.matchData().dayStepBudgets().stepsFor(
                new DayIndex(state.day().value() + 1));
        List<PatrolFutureReadiness> patrolReadiness = new ArrayList<>();
        int ready = 0;
        int spots = 0;
        int brands = 0;
        int minimum = Integer.MAX_VALUE;
        int totalFuel = 0;
        for (AgentState agent : simulation.finalAgents().stream()
                .sorted(Comparator.comparingInt(value -> value.id().value())).toList()) {
            if (agent.kind() != AgentKind.PATROL) {
                continue;
            }
            int fuel = ((FiniteFuel) agent.fuel()).amount();
            Set<Position> reachablePositions = new TreeSet<>(Comparator.comparingInt(Position::value));
            Set<BrandId> reachableBrands = new TreeSet<>(Comparator.comparing(BrandId::value));
            int nearestFuel = Integer.MAX_VALUE;
            for (UdonSpot spot : orderedSpots) {
                OpportunityCost cost = costsByStart.getOrDefault(spot.position(), Map.of())
                        .get(agent.position());
                if (cost != null && cost.steps() <= nextDayBudget && cost.fuel() <= fuel) {
                    reachablePositions.add(spot.position());
                    reachableBrands.add(spot.brand());
                    nearestFuel = Math.min(nearestFuel, cost.fuel());
                }
            }
            int minimumFuel = nearestFuel == Integer.MAX_VALUE ? 0 : nearestFuel;
            int slack = nearestFuel == Integer.MAX_VALUE ? 0 : fuel - nearestFuel;
            PatrolFutureReadiness readiness = new PatrolFutureReadiness(
                    agent.id(), agent.position(), fuel, remainingDays, reachablePositions,
                    reachableBrands, minimumFuel, slack);
            patrolReadiness.add(readiness);
            if (readiness.futureReady()) {
                ready++;
            }
            spots += readiness.reachableOpportunitySpotCount();
            brands += readiness.reachableOpportunityBrandCount();
            minimum = Math.min(minimum, readiness.futureReady() ? readiness.reachableOpportunitySpotCount() : 0);
            totalFuel += fuel;
        }
        return new TeamFutureReadiness(
                remainingDays, patrolReadiness, ready, spots, brands,
                minimum == Integer.MAX_VALUE ? 0 : minimum, totalFuel, cacheEntries, pathfindingExecutions);
    }

    private TeamFutureReadiness zeroFuture(ValidDaySimulationResult simulation, int remainingDays) {
        List<PatrolFutureReadiness> patrols = simulation.finalAgents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(agent -> agent.id().value()))
                .map(agent -> new PatrolFutureReadiness(
                        agent.id(), agent.position(), ((FiniteFuel) agent.fuel()).amount(), remainingDays,
                        Set.of(), Set.of(), 0, 0))
                .toList();
        return new TeamFutureReadiness(
                remainingDays, patrols, 0, 0, 0, 0, 0, cacheEntries, pathfindingExecutions);
    }

    public int routeCostCacheEntries() {
        return cacheEntries;
    }

    public int pathfindingExecutions() {
        return pathfindingExecutions;
    }

    private static Map<Position, OpportunityCost> reverseParetoCosts(DayState state, Position goal) {
        HexMap map = state.matchData().map();
        int maxSteps = state.matchData().dayStepBudgets().stepsFor(
                new DayIndex(Math.min(state.day().value() + 1,
                        state.matchData().dayStepBudgets().dayCount() - 1)));
        Map<Position, Map<Integer, Integer>> bestFuelBySteps = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>(Comparator
                .comparingInt(Label::steps)
                .thenComparingInt(Label::fuel)
                .thenComparingInt(label -> label.position().value()));
        bestFuelBySteps.computeIfAbsent(goal, ignored -> new HashMap<>()).put(0, 0);
        queue.add(new Label(goal, 0, 0));
        while (!queue.isEmpty()) {
            Label current = queue.poll();
            if (bestFuelBySteps.getOrDefault(current.position(), Map.of())
                    .getOrDefault(current.steps(), Integer.MAX_VALUE) != current.fuel()) {
                continue;
            }
            for (Position predecessor : map.neighbors(current.position())) {
                if (!map.isTraversable(predecessor)) {
                    continue;
                }
                TrafficStatus futureRoad = map.terrainAt(predecessor) == Terrain.ROAD
                        ? TrafficStatus.CLEAR : null;
                MoveCost cost = MovementRules.costFromSource(map, predecessor, futureRoad).orElse(null);
                if (cost == null || current.steps() + cost.stepCost() > maxSteps) {
                    continue;
                }
                int steps = current.steps() + cost.stepCost();
                int fuel = current.fuel() + cost.patrolFuelCost();
                Map<Integer, Integer> labels = bestFuelBySteps.computeIfAbsent(
                        predecessor, ignored -> new HashMap<>());
                if (fuel < labels.getOrDefault(steps, Integer.MAX_VALUE)) {
                    labels.put(steps, fuel);
                    queue.add(new Label(predecessor, steps, fuel));
                }
            }
        }
        Map<Position, OpportunityCost> result = new HashMap<>();
        for (Map.Entry<Position, Map<Integer, Integer>> entry : bestFuelBySteps.entrySet()) {
            int bestSteps = Integer.MAX_VALUE;
            int bestFuel = Integer.MAX_VALUE;
            for (Map.Entry<Integer, Integer> label : entry.getValue().entrySet()) {
                if (label.getValue() < bestFuel
                        || label.getValue() == bestFuel && label.getKey() < bestSteps) {
                    bestSteps = label.getKey();
                    bestFuel = label.getValue();
                }
            }
            result.put(entry.getKey(), new OpportunityCost(bestSteps, bestFuel));
        }
        return Map.copyOf(result);
    }

    private record OpportunityCost(int steps, int fuel) { }

    private record Label(Position position, int steps, int fuel) { }
}