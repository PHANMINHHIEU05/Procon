package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.rules.MovementRules;

/**
 * M15 deterministic bounded full-day opponent harvest rollout for the CURRENT day.
 *
 * <p>Route costs are precomputed once per planning run: one reverse Pareto search per static Udon
 * spot, using the AUTHORITATIVE CURRENT road traffic of {@link DayState#roadTraffic()} and the
 * official source-terrain PATROL fuel cost, bounded by the day's authoritative step budget. That one
 * cache covers both opponent-start-to-spot and spot-to-spot legs, so
 * {@link #pathfindingExecutions()} equals the static spot count and there is ZERO per-terminal and
 * per-candidate Dijkstra. The opponent forecast itself is never recomputed here.</p>
 *
 * <p>The rollout is event driven. Every eligible collector holds exactly one pending arrival
 * (identity, position, step, fuel, selected target); the earliest arrival is processed first. Target
 * selection reuses the existing M10 opponent intent ranking semantics — travel steps ascending, then
 * residual stock descending, then an unseen brand ahead of a repeated one, then position — but with
 * no top-K target truncation, so a collector keeps collecting until the step budget is exhausted, its
 * fuel is insufficient, or no reachable stocked opportunity is left. When a selected target turns out
 * to be unavailable on arrival the collector reroutes from its unchanged current position and step.</p>
 *
 * <p>One shared global residual stock model is used for the whole opponent team. Our own plan enters
 * only through M12.1 semi-realizable collections, and only when {@code ownStep < opponentArrivalStep};
 * an equal step never removes an opponent collection and an own-late collection cannot affect one.</p>
 *
 * <p>Observed opponent fuel is a plain protocol integer that may be negative when the server does not
 * expose it. A negative value is treated as unbounded, so the rollout stays limited by the step budget
 * alone rather than silently assuming an empty tank. Opponent refuelling is not modelled.</p>
 */
public final class FullDayOpponentHarvestRollout {

    private static final Comparator<Pending> ARRIVAL_ORDER = Comparator
            .comparingInt(Pending::arrivalStep)
            .thenComparingInt(Pending::collectorIndex)
            .thenComparingInt(Pending::spotIndex);

    private final DayState state;
    private final int stepBudget;
    private final List<UdonSpot> orderedSpots;
    private final Map<Position, Map<Position, List<RouteCost>>> costsByGoal;
    private final List<Collector> collectors;
    private final int routeCostCacheEntries;
    private final int pathfindingExecutions;
    private final int maxRolloutEvents;

    private FullDayOpponentHarvestRollout(
            DayState state,
            int stepBudget,
            List<UdonSpot> orderedSpots,
            Map<Position, Map<Position, List<RouteCost>>> costsByGoal,
            List<Collector> collectors,
            int pathfindingExecutions) {
        this.state = state;
        this.stepBudget = stepBudget;
        this.orderedSpots = orderedSpots;
        this.costsByGoal = costsByGoal;
        this.collectors = collectors;
        this.pathfindingExecutions = pathfindingExecutions;
        this.routeCostCacheEntries = costsByGoal.values().stream().mapToInt(Map::size).sum();
        // Each collector either collects a spot or permanently drops one per event, plus one final
        // empty selection. The rollout therefore always terminates well inside this bound.
        this.maxRolloutEvents = collectors.size() * (2 * orderedSpots.size() + 2) + 1;
    }

    public static FullDayOpponentHarvestRollout forState(DayState state, OpponentIntentConfig config) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(config, "Opponent intent configuration must not be null");
        List<UdonSpot> ordered = state.matchData().udonSpots().stream()
                .sorted(Comparator.comparingInt(spot -> spot.position().value())).toList();
        List<Collector> collectors = new ArrayList<>();
        for (ObservedOtherGroup group : state.observedOthers()) {
            for (int agentIndex = 0; agentIndex < group.agents().size(); agentIndex++) {
                ObservedOtherAgent agent = group.agents().get(agentIndex);
                if (!config.collectionEligibility().collectsUdon(agent)) {
                    continue;
                }
                collectors.add(new Collector(
                        group.rawId(), agentIndex, agent.rawKind(), agent.position(), agent.fuel()));
            }
        }
        int budget = state.stepBudget();
        if (ordered.isEmpty() || collectors.isEmpty()) {
            return new FullDayOpponentHarvestRollout(
                    state, budget, ordered, Map.of(), List.copyOf(collectors), 0);
        }
        Map<Position, Map<Position, List<RouteCost>>> cache = new HashMap<>();
        for (UdonSpot spot : ordered) {
            cache.put(spot.position(), reverseParetoCosts(state, spot.position(), budget));
        }
        return new FullDayOpponentHarvestRollout(
                state, budget, ordered, Map.copyOf(cache), List.copyOf(collectors), ordered.size());
    }

    /** The immutable full-day baseline, computed once per planning run before any candidate plan. */
    public OpponentFullDayBaseline baseline() {
        RolloutOutcome outcome = rollout(Map.of());
        Map<Position, List<OpponentFullDayClaim>> bySpot = new LinkedHashMap<>();
        for (OpponentFullDayClaim claim : outcome.claims()) {
            bySpot.computeIfAbsent(claim.spot(), ignored -> new ArrayList<>()).add(claim);
        }
        int stocked = (int) state.spotStock().values().stream().filter(stock -> stock > 0).count();
        return new OpponentFullDayBaseline(
                outcome.claims(),
                bySpot,
                outcome.observedNow(),
                outcome.directIntent(),
                outcome.followOnIntent(),
                collectors.size(),
                stocked,
                stepBudget,
                outcome.events(),
                outcome.maxCollectorCollections(),
                routeCostCacheEntries,
                pathfindingExecutions);
    }

    /**
     * Replacement-aware residual for one complete candidate plan.
     *
     * <p>Only M12.1 semi-realizable collections of the plan reach the opponent, and only strictly
     * before an opponent arrival. Nothing here recomputes the opponent forecast or the route cache.</p>
     */
    public OpponentFullDayResidualEvaluation evaluate(
            OpponentFullDayBaseline baseline,
            ValidDaySimulationResult simulation,
            SemiCommitmentCollectionAttribution attribution) {
        Objects.requireNonNull(baseline, "Full-day baseline must not be null");
        Objects.requireNonNull(simulation, "Simulation must not be null");
        Objects.requireNonNull(attribution, "M12.1 attribution must not be null");
        List<UdonCollectedEvent> events = simulation.events().stream()
                .filter(UdonCollectedEvent.class::isInstance)
                .map(UdonCollectedEvent.class::cast)
                .sorted(Comparator.comparingInt(UdonCollectedEvent::step)
                        .thenComparingInt(event -> event.agentId().value()))
                .toList();
        if (events.size() != attribution.assessments().size()) {
            throw new IllegalArgumentException("M12.1 assessments must align with simulator collections");
        }
        List<OwnSemiCollection> own = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            if (attribution.assessments().get(index).semiCommitmentRealizable()) {
                UdonCollectedEvent event = events.get(index);
                own.add(new OwnSemiCollection(event.position(), event.step()));
            }
        }
        return evaluate(baseline, own);
    }

    OpponentFullDayResidualEvaluation evaluate(
            OpponentFullDayBaseline baseline, List<OwnSemiCollection> ownCollections) {
        Objects.requireNonNull(baseline, "Full-day baseline must not be null");
        Objects.requireNonNull(ownCollections, "Own semi-realizable collections must not be null");
        Map<Position, List<Integer>> ownStepsBySpot = new TreeMap<>(Comparator.comparingInt(Position::value));
        for (OwnSemiCollection collection : ownCollections) {
            ownStepsBySpot.computeIfAbsent(collection.spot(), ignored -> new ArrayList<>())
                    .add(collection.step());
        }
        ownStepsBySpot.values().forEach(java.util.Collections::sort);
        RolloutOutcome outcome = rollout(ownStepsBySpot);
        Set<String> baselineKeys = new HashSet<>();
        baseline.claims().forEach(claim -> baselineKeys.add(claim.collectorSpotKey()));
        int replacement = 0;
        for (OpponentFullDayClaim claim : outcome.claims()) {
            if (!baselineKeys.contains(claim.collectorSpotKey())) {
                replacement++;
            }
        }
        return new OpponentFullDayResidualEvaluation(
                baseline,
                outcome.claims(),
                outcome.observedNow(),
                outcome.directIntent(),
                outcome.followOnIntent(),
                replacement,
                outcome.events(),
                outcome.maxCollectorCollections());
    }

    /**
     * M15-only candidate guidance over the fixed full-day baseline.
     *
     * <p>A linear walk of one candidate route that counts how many baseline full-day collections this
     * route could reach first. Because the baseline is a whole-day route rather than a top-three
     * prefix, a target deep inside an opponent's route still scores here and can survive top-K. No
     * rollout and no pathfinding happens per candidate.</p>
     */
    FullDayRouteContest contestRoute(
            OpponentFullDayBaseline baseline,
            OpponentCommitmentForecast commitment,
            SemiCommitmentForecastEvaluator semiEvaluator,
            SemiCommitmentAdjustmentWeights weights,
            Map<Position, UdonSpot> spotsByPosition,
            Route route,
            int initialArrivalStep,
            Map<Position, Integer> branchStock,
            Set<Position> alreadyVisited) {
        Map<Position, Integer> available = new LinkedHashMap<>(branchStock);
        Set<Position> visited = new LinkedHashSet<>(alreadyVisited);
        Map<Position, Set<Integer>> matched = new LinkedHashMap<>();
        HexMap map = state.matchData().map();
        Position cursor = route.start();
        int step = initialArrivalStep;
        int contested = 0;
        int strongContested = 0;
        for (Direction direction : route.directions()) {
            TrafficStatus traffic = map.terrainAt(cursor) == Terrain.ROAD
                    ? state.roadTraffic().get(cursor) : null;
            MoveCost cost = MovementRules.costFromSource(map, cursor, traffic).orElseThrow();
            step += cost.stepCost();
            cursor = map.neighbor(cursor, direction).orElseThrow();
            if (!visited.add(cursor) || available.getOrDefault(cursor, 0) <= 0
                    || !spotsByPosition.containsKey(cursor)) {
                continue;
            }
            available.put(cursor, available.get(cursor) - 1);
            SemiCommitmentCollectionAssessment assessment = semiEvaluator.assessCollection(
                    branchStock, cursor, step, commitment, weights);
            if (!assessment.semiCommitmentRealizable()) {
                continue;
            }
            List<OpponentFullDayClaim> claims = baseline.claimsAt(cursor);
            Set<Integer> used = matched.computeIfAbsent(cursor, ignored -> new LinkedHashSet<>());
            for (int index = 0; index < claims.size(); index++) {
                OpponentFullDayClaim claim = claims.get(index);
                if (used.contains(index) || claim.arrivalStep() <= step) {
                    continue;
                }
                used.add(index);
                contested++;
                if (claim.strong()) {
                    strongContested++;
                }
                break;
            }
        }
        return new FullDayRouteContest(contested, strongContested);
    }

    public int routeCostCacheEntries() {
        return routeCostCacheEntries;
    }

    public int pathfindingExecutions() {
        return pathfindingExecutions;
    }

    public int collectorCount() {
        return collectors.size();
    }

    public int spotCount() {
        return orderedSpots.size();
    }

    private RolloutOutcome rollout(Map<Position, List<Integer>> ownStepsBySpot) {
        int[] opponentConsumed = new int[orderedSpots.size()];
        List<Cursor> cursors = collectors.stream().map(Cursor::new).toList();
        PriorityQueue<Pending> queue = new PriorityQueue<>(ARRIVAL_ORDER);
        for (int index = 0; index < cursors.size(); index++) {
            select(cursors.get(index), index, opponentConsumed, ownStepsBySpot).ifPresent(queue::add);
        }
        List<OpponentFullDayClaim> claims = new ArrayList<>();
        int observedNow = 0;
        int direct = 0;
        int followOn = 0;
        int events = 0;
        while (!queue.isEmpty()) {
            if (++events > maxRolloutEvents) {
                throw new IllegalStateException(
                        "Full-day opponent rollout exceeded its bound of " + maxRolloutEvents
                                + " events; the residual stock model failed to shrink");
            }
            Pending pending = queue.poll();
            Cursor cursor = cursors.get(pending.collectorIndex());
            UdonSpot spot = orderedSpots.get(pending.spotIndex());
            if (available(pending.spotIndex(), pending.arrivalStep(), opponentConsumed, ownStepsBySpot)
                    <= 0) {
                // The target became unavailable: reroute from the unchanged position and step. The
                // availability of this spot can never recover, so the retry cannot pick it again.
                select(cursor, pending.collectorIndex(), opponentConsumed, ownStepsBySpot)
                        .ifPresent(queue::add);
                continue;
            }
            opponentConsumed[pending.spotIndex()]++;
            cursor.position = spot.position();
            cursor.step = pending.arrivalStep();
            if (cursor.fuel >= 0) {
                cursor.fuel -= pending.legFuel();
            }
            cursor.visited.add(pending.spotIndex());
            cursor.routeBrands.add(spot.brand());
            OpponentClaimCommitment classification;
            if (pending.arrivalStep() == 0) {
                classification = OpponentClaimCommitment.OBSERVED_NOW;
                observedNow++;
            } else if (!cursor.directAssigned) {
                classification = OpponentClaimCommitment.DIRECT_INTENT;
                cursor.directAssigned = true;
                direct++;
            } else {
                classification = OpponentClaimCommitment.FOLLOW_ON_INTENT;
                followOn++;
            }
            claims.add(new OpponentFullDayClaim(
                    cursor.collector.groupRawId(),
                    cursor.collector.agentIndex(),
                    cursor.collector.rawKind(),
                    spot.position(),
                    pending.arrivalStep(),
                    cursor.collections,
                    pending.legSteps(),
                    pending.legFuel(),
                    classification));
            cursor.collections++;
            select(cursor, pending.collectorIndex(), opponentConsumed, ownStepsBySpot)
                    .ifPresent(queue::add);
        }
        int maxCollectorCollections = cursors.stream().mapToInt(value -> value.collections).max().orElse(0);
        return new RolloutOutcome(
                List.copyOf(claims), observedNow, direct, followOn, events, maxCollectorCollections);
    }

    /**
     * Next target for one collector under the M10 ranking semantics, with no top-K truncation.
     *
     * <p>Retires the collector when no reachable stocked opportunity is left within its remaining step
     * budget and fuel.</p>
     */
    private Optional<Pending> select(
            Cursor cursor,
            int collectorIndex,
            int[] opponentConsumed,
            Map<Position, List<Integer>> ownStepsBySpot) {
        if (cursor.retired) {
            return Optional.empty();
        }
        Choice best = null;
        for (int index = 0; index < orderedSpots.size(); index++) {
            if (cursor.visited.contains(index)) {
                continue;
            }
            UdonSpot spot = orderedSpots.get(index);
            RouteCost leg = bestLeg(cursor.position, spot.position(), cursor.step, cursor.fuel);
            if (leg == null) {
                continue;
            }
            int arrival = cursor.step + leg.steps();
            int available = available(index, arrival, opponentConsumed, ownStepsBySpot);
            if (available <= 0) {
                continue;
            }
            Choice candidate = new Choice(
                    index, spot.position(), leg, arrival, available,
                    cursor.routeBrands.contains(spot.brand()));
            if (best == null || CHOICE_ORDER.compare(candidate, best) < 0) {
                best = candidate;
            }
        }
        if (best == null) {
            cursor.retired = true;
            return Optional.empty();
        }
        return Optional.of(new Pending(
                best.arrival(), collectorIndex, best.spotIndex(),
                best.leg().steps(), best.leg().fuel()));
    }

    private static final Comparator<Choice> CHOICE_ORDER = Comparator
            .comparingInt(Choice::arrival)
            .thenComparing(Comparator.comparingInt(Choice::available).reversed())
            .thenComparing(Choice::repeatedBrand)
            .thenComparingInt(choice -> choice.spot().value());

    /** Fastest fuel-feasible cached leg, or {@code null} when the pair is unreachable in budget. */
    private RouteCost bestLeg(Position from, Position to, int usedSteps, int fuel) {
        RouteCost best = null;
        for (RouteCost cost : costsByGoal.getOrDefault(to, Map.of()).getOrDefault(from, List.of())) {
            if (usedSteps + cost.steps() > stepBudget) {
                continue;
            }
            if (fuel >= 0 && cost.fuel() > fuel) {
                continue;
            }
            if (best == null || cost.steps() < best.steps()
                    || (cost.steps() == best.steps() && cost.fuel() < best.fuel())) {
                best = cost;
            }
        }
        return best;
    }

    /**
     * Residual availability of one spot for an opponent arriving at {@code arrivalStep}.
     *
     * <p>Our own semi-realizable collections count only when strictly earlier, so an equal step never
     * removes an opponent collection. Every opponent consumption recorded so far counts regardless of
     * its step, which reproduces the M14 strict-before accounting while staying independent of the
     * order in which reroutes are processed.</p>
     */
    private int available(
            int spotIndex,
            int arrivalStep,
            int[] opponentConsumed,
            Map<Position, List<Integer>> ownStepsBySpot) {
        Position spot = orderedSpots.get(spotIndex).position();
        int stock = state.spotStock().getOrDefault(spot, 0);
        int ownBefore = 0;
        for (int step : ownStepsBySpot.getOrDefault(spot, List.of())) {
            if (step >= arrivalStep) {
                break;
            }
            ownBefore++;
        }
        return stock - ownBefore - opponentConsumed[spotIndex];
    }

    /**
     * One reverse Pareto search per Udon spot over AUTHORITATIVE CURRENT traffic.
     *
     * <p>This is the only pathfinding M15 performs. It runs once per planning run, never per candidate
     * and never per terminal plan.</p>
     */
    private static Map<Position, List<RouteCost>> reverseParetoCosts(
            DayState state, Position goal, int maxSteps) {
        HexMap map = state.matchData().map();
        Map<Position, List<RouteCost>> labels = new HashMap<>();
        PriorityQueue<RouteLabel> queue = new PriorityQueue<>(Comparator
                .comparingInt((RouteLabel value) -> value.cost().steps())
                .thenComparingInt(value -> value.cost().fuel())
                .thenComparingInt(value -> value.position().value()));
        RouteCost zero = new RouteCost(0, 0);
        labels.put(goal, new ArrayList<>(List.of(zero)));
        queue.add(new RouteLabel(goal, zero));
        while (!queue.isEmpty()) {
            RouteLabel current = queue.poll();
            if (!labels.getOrDefault(current.position(), List.of()).contains(current.cost())) {
                continue;
            }
            for (Position predecessor : map.neighbors(current.position())) {
                if (!map.isTraversable(predecessor)) {
                    continue;
                }
                TrafficStatus traffic = map.terrainAt(predecessor) == Terrain.ROAD
                        ? state.roadTraffic().get(predecessor) : null;
                MoveCost move = MovementRules.costFromSource(map, predecessor, traffic).orElse(null);
                if (move == null) {
                    continue;
                }
                RouteCost candidate = new RouteCost(
                        current.cost().steps() + move.stepCost(),
                        current.cost().fuel() + move.patrolFuelCost());
                if (candidate.steps() > maxSteps) {
                    continue;
                }
                List<RouteCost> existing = labels.computeIfAbsent(
                        predecessor, ignored -> new ArrayList<>());
                if (existing.stream().anyMatch(value -> value.dominates(candidate))) {
                    continue;
                }
                existing.removeIf(candidate::dominates);
                existing.add(candidate);
                existing.sort(Comparator.comparingInt(RouteCost::steps)
                        .thenComparingInt(RouteCost::fuel));
                queue.add(new RouteLabel(predecessor, candidate));
            }
        }
        Map<Position, List<RouteCost>> immutable = new HashMap<>();
        labels.forEach((position, costs) -> immutable.put(position, List.copyOf(costs)));
        return Map.copyOf(immutable);
    }

    /** One M12.1 semi-realizable own collection, the only channel our plan has into the rollout. */
    record OwnSemiCollection(Position spot, int step) {
        OwnSemiCollection {
            Objects.requireNonNull(spot, "Own collection spot must not be null");
            if (step < 0) {
                throw new IllegalArgumentException("Own collection step must be non-negative");
            }
        }
    }

    /** Bounded per-candidate contest counts over the fixed full-day baseline. */
    record FullDayRouteContest(int contestedCollections, int strongContestedCollections) {
        FullDayRouteContest {
            if (contestedCollections < 0 || strongContestedCollections < 0
                    || strongContestedCollections > contestedCollections) {
                throw new IllegalArgumentException("Full-day contest counts must be consistent");
            }
        }

        static FullDayRouteContest empty() {
            return new FullDayRouteContest(0, 0);
        }
    }

    private record Collector(int groupRawId, int agentIndex, int rawKind, Position position, int fuel) {
    }

    private record Pending(int arrivalStep, int collectorIndex, int spotIndex, int legSteps, int legFuel) {
    }

    private record Choice(
            int spotIndex, Position spot, RouteCost leg, int arrival, int available,
            boolean repeatedBrand) {
    }

    private record RolloutOutcome(
            List<OpponentFullDayClaim> claims,
            int observedNow,
            int directIntent,
            int followOnIntent,
            int events,
            int maxCollectorCollections) {
    }

    private record RouteCost(int steps, int fuel) {
        private boolean dominates(RouteCost other) {
            return steps <= other.steps && fuel <= other.fuel;
        }
    }

    private record RouteLabel(Position position, RouteCost cost) {
    }

    /** Mutable per-collector cursor; never escapes one rollout invocation. */
    private static final class Cursor {

        private final Collector collector;
        private final Set<Integer> visited = new LinkedHashSet<>();
        private final Set<BrandId> routeBrands = new LinkedHashSet<>();
        private Position position;
        private int step;
        private int fuel;
        private int collections;
        private boolean directAssigned;
        private boolean retired;

        private Cursor(Collector collector) {
            this.collector = collector;
            this.position = collector.position();
            this.step = 0;
            this.fuel = collector.fuel();
        }
    }
}
