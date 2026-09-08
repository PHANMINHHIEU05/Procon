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
 *
 * <p>The subset DP is label-driven: its work queue holds {@code (key, label)} pairs, so every accepted Pareto
 * label is expanded exactly once. Queuing keys instead would re-expand a key's whole Pareto set on every
 * accepted label, i.e. about {@code A(A+1)/2} expansions for a key that ends with {@code A} labels. The
 * resulting Pareto front is identical either way — a displaced label can only produce dominated successors —
 * so {@code dpStatesEvaluated} is the only reported figure the schedule changes.</p>
 *
 * <p>The DP result is memoized per calculator instance under the key {@code (startPosition, projectedFuel)}.
 * That key is COMPLETE, not merely convenient: one invocation reads exactly the patrol's projected end
 * position, its projected end fuel, and instance state that is final and immutable for the whole lifetime —
 * {@link #opportunities} (positions and brands), {@link #costsByGoal}, {@link #nextDayStepBudget} and
 * {@link #remainingFutureDays}, all fixed by the single {@link DayState} the instance was built from. The
 * agent identity is deliberately NOT part of the key because the DP never reads it; it is re-attached to the
 * returned record from the requesting agent, so two patrols that share a key still report their own ids.
 * Nothing mutable of the day state (stock, other teams, the plan being scored) reaches the DP, and the memo
 * is never shared between instances, so it cannot carry a value across day states.</p>
 *
 * <p>The memo is not synchronized. Every instance is created and consumed inside the evaluator that owns it
 * ({@code JointTerminalEvaluator}, {@code FrozenObjectiveEvaluator}), and the only planner thread pool
 * ({@code V3ShadowRunner}) builds its own evaluators, so an instance is confined to one thread — the same
 * confinement the DP's own {@link HashMap} state already relies on.</p>
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
    /** Lifetime-scoped memo: one entry per distinct {@code (startPosition, projectedFuel)} DP question. */
    private final Map<CapacityKey, CapacityValue> capacityCache = new HashMap<>();
    private int capacityCacheHits;
    private int capacityCacheMisses;

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

    /** Diagnostics only: how many DP invocations this instance answered from its memo. */
    public int capacityCacheHits() {
        return capacityCacheHits;
    }

    /** Diagnostics only: how many DP invocations this instance actually ran the subset DP for. */
    public int capacityCacheMisses() {
        return capacityCacheMisses;
    }

    private PatrolNextDayHarvestCapacity capacity(AgentState agent, int fuel) {
        CapacityKey key = new CapacityKey(agent.position().value(), fuel);
        CapacityValue cached = capacityCache.get(key);
        if (cached == null) {
            capacityCacheMisses++;
            cached = computeCapacity(agent.position(), fuel);
            capacityCache.put(key, cached);
        } else {
            capacityCacheHits++;
        }
        // Only the agent identity and its position/fuel come from the caller; everything the DP derives is
        // shared, because the DP derived it from nothing else than this key and immutable instance state.
        return new PatrolNextDayHarvestCapacity(
                agent.id(), agent.position(), fuel, remainingFutureDays, cached.stationary(),
                cached.maxSpots(), cached.maxBrands(), cached.bestRemainingFuel(),
                cached.dpStatesEvaluated());
    }

    private CapacityValue computeCapacity(Position start, int fuel) {
        int count = opportunities.size();
        if (count == 0) {
            return new CapacityValue(false, 0, 0, fuel, 0);
        }
        Map<DpKey, List<RouteCost>> states = new HashMap<>();
        ArrayDeque<DpLabel> work = new ArrayDeque<>();
        boolean stationary = false;
        for (int spotIndex = 0; spotIndex < count; spotIndex++) {
            UdonSpot spot = opportunities.get(spotIndex);
            if (spot.position().equals(start)) {
                stationary = true;
            }
            for (RouteCost cost : costs(start, spot.position())) {
                if (cost.steps <= nextDayStepBudget && cost.fuel <= fuel) {
                    DpKey key = new DpKey(1 << spotIndex, spotIndex);
                    if (addPareto(states, key, cost)) {
                        work.addLast(new DpLabel(key, cost));
                    }
                }
            }
        }
        int stateVisits = 0;
        while (!work.isEmpty()) {
            DpLabel current = work.removeFirst();
            // A label that a later, dominating label has already displaced can only produce dominated
            // successors, so skipping it removes work without removing reachable Pareto labels. This is the
            // same staleness guard the reverse route search below already applies.
            if (!states.getOrDefault(current.key, List.of()).contains(current.label)) {
                continue;
            }
            stateVisits++;
            Position from = opportunities.get(current.key.lastSpot).position();
            for (int next = 0; next < count; next++) {
                if ((current.key.visitedMask & 1 << next) != 0) {
                    continue;
                }
                for (RouteCost leg : costs(from, opportunities.get(next).position())) {
                    RouteCost combined = new RouteCost(
                            current.label.steps + leg.steps, current.label.fuel + leg.fuel);
                    if (combined.steps > nextDayStepBudget || combined.fuel > fuel) {
                        continue;
                    }
                    DpKey key = new DpKey(current.key.visitedMask | 1 << next, next);
                    if (addPareto(states, key, combined)) {
                        work.addLast(new DpLabel(key, combined));
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
        return new CapacityValue(stationary, maxSpots, maxBrands, bestRemainingFuel, stateVisits);
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

    /**
     * The complete question the subset DP answers: where the patrol ends the day and with how much fuel.
     * Position is stored as its flat index so the key is two ints and needs no domain equality contract.
     */
    private record CapacityKey(int position, int fuel) { }

    /** Every field {@link #computeCapacity} derives, i.e. everything that is shared by an identical key. */
    private record CapacityValue(
            boolean stationary, int maxSpots, int maxBrands, int bestRemainingFuel,
            int dpStatesEvaluated) { }

    private record DpKey(int visitedMask, int lastSpot) { }

    /** One Pareto label queued for exactly one expansion, so no label is ever expanded twice. */
    private record DpLabel(DpKey key, RouteCost label) { }

    private record RouteCost(int steps, int fuel) {
        private boolean dominates(RouteCost other) {
            return steps <= other.steps && fuel <= other.fuel;
        }
    }

    private record RouteLabel(Position position, RouteCost cost) { }
}