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
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
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
import vn.ptit.procon.engine.MoveCompletedEvent;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.rules.MovementRules;

/**
 * M16 deterministic bounded COUPLED competitive rollout of the CURRENT day.
 *
 * <h2>Why this replaces the M15 residual model</h2>
 *
 * <p>M15 froze every M12.1 semi-realizable own collection as a guaranteed stock removal and then let
 * the opponent reroute around those frozen removals. A rerouted collector could therefore reach a spot
 * strictly before one of our frozen own events, and yet that own event still consumed stock. The
 * counterfactual was asymmetric — our events fixed, the opponent's adaptive — and it made the
 * plan-conditioned margin strongly optimistic for us.</p>
 *
 * <p>Here our ACTION routes stay fixed, exactly as the planner committed them, but our COLLECTIONS are
 * not guaranteed. Every side draws from ONE mutable shared stock along ONE chronological timeline. An
 * own planned arrival that finds its spot already empty collects nothing and, crucially, removes no
 * stock afterwards.</p>
 *
 * <h2>Timing and ties</h2>
 *
 * <ul>
 *   <li>Own arrival strictly earlier than an opponent arrival at the same spot: we consume first, and
 *       the opponent's later arrival may fail.</li>
 *   <li>Opponent arrival strictly earlier: it consumes first, and our later planned arrival may fail.
 *       That failure is what {@code ownPlannedEventsInvalidatedByOpponent} counts.</li>
 *   <li>EQUAL step: the opponent is resolved first, always. Equal-step stock that cannot satisfy both
 *       sides is therefore never counted as a guaranteed own collection — conservative from our side —
 *       and an equal step never removes an opponent collection either, so ties can never manufacture
 *       denial in our favour. When the stock is at least two, both sides succeed and the tie is
 *       irrelevant.</li>
 * </ul>
 *
 * <h2>Pathfinding</h2>
 *
 * <p>One bounded reverse Pareto search per static Udon spot, built once in {@link #forState}, over the
 * AUTHORITATIVE CURRENT traffic of {@link DayState#roadTraffic()} and the official source-terrain cost.
 * That single cache serves collector-start-to-spot and spot-to-spot legs alike, so
 * {@link #pathfindingExecutions()} equals the static spot count and there is ZERO Dijkstra per terminal
 * plan, per opponent reroute and per event.</p>
 *
 * <h2>Bounded adversarial best response</h2>
 *
 * <p>Opponent target choice is not the M15 arrival-first ranking. Its primary key is a deterministic
 * per-collector upper bound on the collections still reachable through the rest of the day — one plus
 * the length of the greedy earliest-arrival chain out of the candidate — computed only from cached
 * route costs and the current shared availability. That bound deliberately ignores the future
 * consumption of the collector's own teammates, so it is biased AGAINST underestimating opponent
 * throughput. Collections tie-break on how many of our remaining planned opportunities the chain
 * deterministically starves. No game tree is searched, and our own alternative actions are never
 * explored inside the rollout.</p>
 *
 * <h2>Reroute semantics</h2>
 *
 * <p>A collector whose target is empty on arrival reroutes FROM the spot it physically reached, at the
 * step it reached, having paid that leg. This differs from M15, which rerouted from the unchanged
 * earlier position and step. A shared chronological timeline cannot admit a retroactive arrival: a
 * free do-over would let a collector be inserted at a step whose own events are already resolved,
 * which would silently hand us stock the opponent had really taken. Because the selection rule already
 * subtracts our strictly-earlier planned arrivals and the pending reservations of the collector's
 * teammates, an empty-on-arrival reroute only happens when the whole opponent team over-subscribed one
 * spot.</p>
 */
public final class CoupledCompetitiveRollout {

    private static final Comparator<Arrival> TIMELINE_ORDER = Comparator
            .comparingInt(Arrival::step)
            .thenComparingInt(Arrival::sideRank)
            .thenComparingInt(Arrival::tieA)
            .thenComparingInt(Arrival::tieB);

    private final DayState state;
    private final int stepBudget;
    private final List<UdonSpot> orderedSpots;
    private final Map<Position, Integer> spotIndexByPosition;
    private final Map<Position, Map<Position, List<RouteCost>>> costsByGoal;
    private final List<Collector> collectors;
    private final int[] initialStock;
    private final int routeCostCacheEntries;
    private final int pathfindingExecutions;
    private final int maxCollectorEvents;

    private CoupledCompetitiveRollout(
            DayState state,
            int stepBudget,
            List<UdonSpot> orderedSpots,
            Map<Position, Map<Position, List<RouteCost>>> costsByGoal,
            List<Collector> collectors,
            int pathfindingExecutions) {
        this.state = state;
        this.stepBudget = stepBudget;
        this.orderedSpots = orderedSpots;
        Map<Position, Integer> indices = new LinkedHashMap<>();
        for (int index = 0; index < orderedSpots.size(); index++) {
            indices.put(orderedSpots.get(index).position(), index);
        }
        this.spotIndexByPosition = Map.copyOf(indices);
        this.costsByGoal = costsByGoal;
        this.collectors = collectors;
        this.pathfindingExecutions = pathfindingExecutions;
        this.routeCostCacheEntries = costsByGoal.values().stream().mapToInt(Map::size).sum();
        this.initialStock = new int[orderedSpots.size()];
        for (int index = 0; index < orderedSpots.size(); index++) {
            initialStock[index] =
                    state.spotStock().getOrDefault(orderedSpots.get(index).position(), 0);
        }
        // Every collector either consumes a spot or permanently burns a visit per arrival, plus one
        // final empty selection. Own events resolve exactly once each.
        this.maxCollectorEvents = collectors.size() * (2 * orderedSpots.size() + 2) + 1;
    }

    public static CoupledCompetitiveRollout forState(DayState state, OpponentIntentConfig config) {
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
            return new CoupledCompetitiveRollout(
                    state, budget, ordered, Map.of(), List.copyOf(collectors), 0);
        }
        Map<Position, Map<Position, List<RouteCost>>> cache = new HashMap<>();
        for (UdonSpot spot : ordered) {
            cache.put(spot.position(), reverseParetoCosts(state, spot.position(), budget));
        }
        return new CoupledCompetitiveRollout(
                state, budget, ordered, Map.copyOf(cache), List.copyOf(collectors), ordered.size());
    }

    /** The immutable no-own-plan baseline, computed once per planning run before any candidate plan. */
    public CoupledCompetitiveBaseline baseline() {
        Outcome outcome = rollout(List.of(), new boolean[0]);
        Map<Position, List<OpponentFullDayClaim>> bySpot = new LinkedHashMap<>();
        for (OpponentFullDayClaim claim : outcome.opponentClaims()) {
            bySpot.computeIfAbsent(claim.spot(), ignored -> new ArrayList<>()).add(claim);
        }
        int stocked = (int) state.spotStock().values().stream().filter(stock -> stock > 0).count();
        return new CoupledCompetitiveBaseline(
                outcome.opponentClaims(),
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
     * Immutable planned own opportunity events of one simulated complete plan.
     *
     * <p>Not every raw planned arrival becomes one. The day simulator lets a PATROL collect only the
     * FIRST time it stands on a given spot, and it marks that visit before it looks at the stock, so a
     * later re-arrival can never collect however much stock is left. The set is therefore the PATROLs
     * standing on a spot at step zero plus every completed PATROL move whose destination is a spot,
     * deduplicated per {@code (agent, spot)} on the earliest step.</p>
     *
     * <p>The list carries no notion of collecting. It states only where and when our PATROLs are
     * physically scheduled to be, which is exactly what stays fixed while the opponent adapts.</p>
     */
    public List<PlannedOwnOpportunityEvent> plannedOwnOpportunityEvents(
            ValidDaySimulationResult simulation) {
        Objects.requireNonNull(simulation, "Simulation must not be null");
        Map<AgentId, Integer> ordinalById = new LinkedHashMap<>();
        Set<AgentId> patrols = new LinkedHashSet<>();
        for (int index = 0; index < state.agents().size(); index++) {
            AgentState agent = state.agents().get(index);
            ordinalById.put(agent.id(), index);
            if (agent.kind() == AgentKind.PATROL) {
                patrols.add(agent.id());
            }
        }
        Map<String, Raw> earliest = new LinkedHashMap<>();
        if (vn.ptit.procon.engine.SimulationSemantics.collectAtStartOfDay()) {
            for (AgentState agent : state.agents()) {
                if (agent.kind() == AgentKind.PATROL
                        && spotIndexByPosition.containsKey(agent.position())) {
                    earliest.put(agent.id().value() + ":" + agent.position().value(),
                            new Raw(agent.id(), agent.position(), 0));
                }
            }
        }
        simulation.events().stream()
                .filter(MoveCompletedEvent.class::isInstance)
                .map(MoveCompletedEvent.class::cast)
                .sorted(Comparator.comparingInt(MoveCompletedEvent::step)
                        .thenComparingInt(event -> event.agentId().value()))
                .forEach(event -> {
                    if (!patrols.contains(event.agentId())
                            || !spotIndexByPosition.containsKey(event.destination())) {
                        return;
                    }
                    earliest.putIfAbsent(
                            event.agentId().value() + ":" + event.destination().value(),
                            new Raw(event.agentId(), event.destination(), event.step()));
                });
        List<Raw> ordered = new ArrayList<>(earliest.values());
        ordered.sort(Comparator.comparingInt(Raw::step)
                .thenComparingInt(raw -> ordinalById.getOrDefault(raw.agentId(), 0))
                .thenComparingInt(raw -> raw.spot().value()));
        List<PlannedOwnOpportunityEvent> events = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            Raw raw = ordered.get(index);
            events.add(new PlannedOwnOpportunityEvent(raw.agentId(), raw.spot(), raw.step(), index));
        }
        return List.copyOf(events);
    }

    /**
     * Coupled competitive rollout of one complete candidate plan against the fixed baseline.
     *
     * <p>Nothing here recomputes the opponent forecast or the route cache. The own-only replay is
     * cross-checked against the simulator's own collections, so a divergence between the planned event
     * derivation and the authoritative engine fails loudly instead of silently skewing the margin.</p>
     */
    public CoupledCompetitiveRolloutResult evaluate(
            CoupledCompetitiveBaseline baseline, ValidDaySimulationResult simulation) {
        Objects.requireNonNull(baseline, "Coupled baseline must not be null");
        Objects.requireNonNull(simulation, "Simulation must not be null");
        List<PlannedOwnOpportunityEvent> events = plannedOwnOpportunityEvents(simulation);
        boolean[] ownOnly = ownOnlyCollected(events);
        int ownOnlySuccesses = 0;
        for (boolean collected : ownOnly) {
            if (collected) {
                ownOnlySuccesses++;
            }
        }
        long simulated = simulation.events().stream()
                .filter(UdonCollectedEvent.class::isInstance).count();
        if (ownOnlySuccesses != simulated) {
            throw new IllegalStateException(
                    "Own-only replay of the planned opportunity events must reproduce the simulator's "
                            + simulated + " collections but produced " + ownOnlySuccesses);
        }
        return evaluate(baseline, events, ownOnly);
    }

    /** Test-facing entry point: the caller supplies the fixed planned own opportunity events. */
    CoupledCompetitiveRolloutResult evaluate(
            CoupledCompetitiveBaseline baseline, List<PlannedOwnOpportunityEvent> events) {
        Objects.requireNonNull(events, "Planned own opportunity events must not be null");
        return evaluate(baseline, List.copyOf(events), ownOnlyCollected(events));
    }

    private CoupledCompetitiveRolloutResult evaluate(
            CoupledCompetitiveBaseline baseline,
            List<PlannedOwnOpportunityEvent> events,
            boolean[] ownOnly) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).stableOrdinal() != index) {
                throw new IllegalArgumentException(
                        "Planned own opportunity events must arrive in stable ordinal order");
            }
        }
        Outcome outcome = rollout(events, ownOnly);
        Set<String> baselineKeys = new HashSet<>();
        baseline.claims().forEach(claim -> baselineKeys.add(claim.collectorSpotKey()));
        int replacement = 0;
        for (OpponentFullDayClaim claim : outcome.opponentClaims()) {
            if (!baselineKeys.contains(claim.collectorSpotKey())) {
                replacement++;
            }
        }
        int collected = 0;
        int invalidated = 0;
        int exhausted = 0;
        Set<BrandId> brands = new LinkedHashSet<>();
        for (CoupledOwnEventResult result : outcome.ownResults()) {
            switch (result.outcome()) {
                case COLLECTED -> {
                    collected++;
                    brands.add(result.brand());
                }
                case INVALIDATED_BY_OPPONENT -> invalidated++;
                case EXHAUSTED_BY_OWN_TEAM -> exhausted++;
            }
        }
        return new CoupledCompetitiveRolloutResult(
                baseline,
                events,
                outcome.ownResults(),
                outcome.opponentClaims(),
                collected,
                brands,
                invalidated,
                exhausted,
                replacement,
                outcome.observedNow(),
                outcome.directIntent(),
                outcome.followOnIntent(),
                outcome.equalStepContests(),
                outcome.events(),
                outcome.maxCollectorCollections());
    }

    /**
     * M16-only candidate guidance over the fixed no-own-plan baseline.
     *
     * <p>A linear walk of one candidate route counting how many baseline collections it could reach
     * strictly first. Guidance only: the authoritative terminal evaluation is the coupled rollout. No
     * rollout and no pathfinding happens per candidate.</p>
     */
    CoupledRouteContest contestRoute(
            CoupledCompetitiveBaseline baseline,
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
        return new CoupledRouteContest(contested, strongContested);
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

    /** Upper bound on the events one coupled rollout of {@code ownEvents} own arrivals may process. */
    public int maxRolloutEvents(int ownEvents) {
        return maxCollectorEvents + Math.max(0, ownEvents);
    }

    /**
     * Own-only replay of the same fixed plan on a private stock copy.
     *
     * <p>Events arrive in stable ordinal order, which is exactly the order the day simulator resolves
     * collections in, so this reproduces the simulator's own collections without the opponent. Its
     * only purpose is to separate "the opponent took this from us" from "our own team over-subscribed
     * this spot"; it never feeds the margin.</p>
     */
    private boolean[] ownOnlyCollected(List<PlannedOwnOpportunityEvent> events) {
        int[] stock = initialStock.clone();
        boolean[] collected = new boolean[events.size()];
        for (int index = 0; index < events.size(); index++) {
            Integer spotIndex = spotIndexByPosition.get(events.get(index).spot());
            if (spotIndex == null) {
                throw new IllegalArgumentException(
                        "Planned own opportunity event is not on a static Udon spot");
            }
            if (stock[spotIndex] > 0) {
                stock[spotIndex]--;
                collected[index] = true;
            }
        }
        return collected;
    }

    /**
     * The one shared chronological timeline.
     *
     * <p>A single mutable stock array serves both sides, so no quantity is ever counted twice. Own
     * arrivals are all known up front because our routes are fixed; each opponent collector holds one
     * pending arrival at a time and re-selects adaptively from the updated shared stock after every
     * consumption or failure.</p>
     */
    private Outcome rollout(List<PlannedOwnOpportunityEvent> ownEvents, boolean[] ownOnly) {
        int spots = orderedSpots.size();
        int[] stock = initialStock.clone();
        int[] opponentReserved = new int[spots];
        int[] ownResolved = new int[spots];
        int[][] ownStepsBySpot = ownStepsBySpot(ownEvents);
        List<Cursor> cursors = collectors.stream().map(Cursor::new).toList();
        PriorityQueue<Arrival> queue = new PriorityQueue<>(TIMELINE_ORDER);
        for (PlannedOwnOpportunityEvent event : ownEvents) {
            queue.add(Arrival.own(
                    event.arrivalStep(), spotIndexByPosition.get(event.spot()),
                    event.stableOrdinal()));
        }
        for (int index = 0; index < cursors.size(); index++) {
            select(cursors.get(index), index, stock, opponentReserved, ownResolved, ownStepsBySpot)
                    .ifPresent(arrival -> {
                        opponentReserved[arrival.spotIndex()]++;
                        queue.add(arrival);
                    });
        }
        CoupledOwnEventOutcome[] outcomes = new CoupledOwnEventOutcome[ownEvents.size()];
        boolean[] tied = new boolean[ownEvents.size()];
        Set<Long> opponentConsumedAtStep = new HashSet<>();
        List<OpponentFullDayClaim> claims = new ArrayList<>();
        int observedNow = 0;
        int direct = 0;
        int followOn = 0;
        int events = 0;
        int bound = maxRolloutEvents(ownEvents.size());
        while (!queue.isEmpty()) {
            if (++events > bound) {
                throw new IllegalStateException(
                        "Coupled competitive rollout exceeded its bound of " + bound
                                + " events; the shared stock model failed to shrink");
            }
            Arrival arrival = queue.poll();
            int spotIndex = arrival.spotIndex();
            if (arrival.own()) {
                int ordinal = arrival.tieA();
                tied[ordinal] = opponentConsumedAtStep.contains(
                        key(spotIndex, arrival.step()));
                if (stock[spotIndex] > 0) {
                    stock[spotIndex]--;
                    outcomes[ordinal] = CoupledOwnEventOutcome.COLLECTED;
                } else {
                    // The own-only replay separates an opponent steal from our own over-subscription.
                    outcomes[ordinal] = ownOnly[ordinal]
                            ? CoupledOwnEventOutcome.INVALIDATED_BY_OPPONENT
                            : CoupledOwnEventOutcome.EXHAUSTED_BY_OWN_TEAM;
                }
                ownResolved[spotIndex]++;
                continue;
            }
            Cursor cursor = cursors.get(arrival.tieA());
            UdonSpot spot = orderedSpots.get(spotIndex);
            opponentReserved[spotIndex]--;
            // The collector really travelled, so its position, step and fuel advance either way.
            cursor.position = spot.position();
            cursor.step = arrival.step();
            if (cursor.fuel >= 0) {
                cursor.fuel -= arrival.legFuel();
            }
            cursor.visited.add(spotIndex);
            if (stock[spotIndex] > 0) {
                stock[spotIndex]--;
                opponentConsumedAtStep.add(key(spotIndex, arrival.step()));
                cursor.routeBrands.add(spot.brand());
                OpponentClaimCommitment classification;
                if (arrival.step() == 0) {
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
                        arrival.step(),
                        cursor.collections,
                        arrival.legSteps(),
                        arrival.legFuel(),
                        classification));
                cursor.collections++;
            }
            select(cursor, arrival.tieA(), stock, opponentReserved, ownResolved, ownStepsBySpot)
                    .ifPresent(next -> {
                        opponentReserved[next.spotIndex()]++;
                        queue.add(next);
                    });
        }
        List<CoupledOwnEventResult> results = new ArrayList<>();
        int equalStepContests = 0;
        for (PlannedOwnOpportunityEvent event : ownEvents) {
            int ordinal = event.stableOrdinal();
            if (tied[ordinal]) {
                equalStepContests++;
            }
            results.add(new CoupledOwnEventResult(
                    event,
                    orderedSpots.get(spotIndexByPosition.get(event.spot())).brand(),
                    outcomes[ordinal],
                    tied[ordinal]));
        }
        int maxCollectorCollections =
                cursors.stream().mapToInt(value -> value.collections).max().orElse(0);
        return new Outcome(
                List.copyOf(claims), List.copyOf(results), observedNow, direct, followOn,
                equalStepContests, events, maxCollectorCollections);
    }

    /**
     * Bounded deterministic adversarial best response for one collector.
     *
     * <p>Every candidate is ranked first by a per-collector upper bound on the collections still
     * reachable through the rest of the day, then by how many of our remaining planned opportunities
     * the resulting chain deterministically starves, then by the unchanged M15 tie-breaks. Nothing but
     * cached route costs and the current shared availability is consulted, so this is a bounded
     * event-level choice rather than a game tree, and our own alternative actions are never explored.
     * </p>
     */
    private Optional<Arrival> select(
            Cursor cursor,
            int collectorIndex,
            int[] stock,
            int[] opponentReserved,
            int[] ownResolved,
            int[][] ownStepsBySpot) {
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
            int available = available(
                    index, arrival, stock, opponentReserved, ownResolved, ownStepsBySpot, 0);
            if (available <= 0) {
                continue;
            }
            Chain chain = chainFrom(
                    cursor, index, arrival, leg, stock, opponentReserved, ownResolved,
                    ownStepsBySpot);
            Choice candidate = new Choice(
                    index, spot.position(), leg, arrival, available, chain.capacity(),
                    chain.ownOpportunitiesStarved(), cursor.routeBrands.contains(spot.brand()));
            if (best == null || CHOICE_ORDER.compare(candidate, best) < 0) {
                best = candidate;
            }
        }
        if (best == null) {
            cursor.retired = true;
            return Optional.empty();
        }
        return Optional.of(Arrival.opponent(
                best.arrival(), collectorIndex, best.spotIndex(),
                best.leg().steps(), best.leg().fuel()));
    }

    /**
     * Greedy earliest-arrival continuation out of one candidate target.
     *
     * <p>Deliberately an upper bound: it ignores what the collector's teammates will consume later, so
     * it can only over-state opponent throughput, never under-state it. Uses cached route costs only.
     * </p>
     */
    private Chain chainFrom(
            Cursor cursor,
            int firstSpotIndex,
            int firstArrival,
            RouteCost firstLeg,
            int[] stock,
            int[] opponentReserved,
            int[] ownResolved,
            int[][] ownStepsBySpot) {
        Set<Integer> visited = new HashSet<>(cursor.visited);
        visited.add(firstSpotIndex);
        int[] taken = new int[orderedSpots.size()];
        taken[firstSpotIndex] = 1;
        Position position = orderedSpots.get(firstSpotIndex).position();
        int step = firstArrival;
        int fuel = cursor.fuel >= 0 ? cursor.fuel - firstLeg.fuel() : cursor.fuel;
        int capacity = 1;
        int starved = starved(firstSpotIndex, firstArrival, ownResolved, ownStepsBySpot);
        while (capacity < orderedSpots.size()) {
            int bestIndex = -1;
            int bestArrival = Integer.MAX_VALUE;
            RouteCost bestLeg = null;
            for (int index = 0; index < orderedSpots.size(); index++) {
                if (visited.contains(index)) {
                    continue;
                }
                RouteCost leg = bestLeg(position, orderedSpots.get(index).position(), step, fuel);
                if (leg == null) {
                    continue;
                }
                int arrival = step + leg.steps();
                if (available(index, arrival, stock, opponentReserved, ownResolved, ownStepsBySpot,
                        taken[index]) <= 0) {
                    continue;
                }
                if (arrival < bestArrival
                        || (arrival == bestArrival && index < bestIndex)) {
                    bestIndex = index;
                    bestArrival = arrival;
                    bestLeg = leg;
                }
            }
            if (bestIndex < 0) {
                break;
            }
            visited.add(bestIndex);
            taken[bestIndex]++;
            starved += starved(bestIndex, bestArrival, ownResolved, ownStepsBySpot);
            position = orderedSpots.get(bestIndex).position();
            step = bestArrival;
            if (fuel >= 0) {
                fuel -= bestLeg.fuel();
            }
            capacity++;
        }
        return new Chain(capacity, starved);
    }

    /**
     * Own planned opportunities this leg can deterministically starve, capped at the one unit it takes.
     *
     * <p>Only our unresolved planned arrivals at the same spot on the same step or later can lose to
     * this leg, and the leg removes exactly one portion, so the observable denial per leg is zero or
     * one. Used solely as a tie-break behind opponent throughput.</p>
     */
    private int starved(
            int spotIndex, int arrivalStep, int[] ownResolved, int[][] ownStepsBySpot) {
        int[] steps = ownStepsBySpot[spotIndex];
        for (int index = ownResolved[spotIndex]; index < steps.length; index++) {
            if (steps[index] >= arrivalStep) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * Shared availability of one spot for an opponent arriving at {@code arrivalStep}.
     *
     * <p>{@code stock} is the LIVE shared quantity, already net of every resolved own collection and
     * opponent consumption. Subtracted on top of it: our still-unresolved planned arrivals STRICTLY
     * before this arrival, the pending arrivals of the collector's own teammates, and any units a
     * hypothetical look-ahead chain has already taken. An own arrival on the SAME step is not
     * subtracted, which is the tie policy: the opponent resolves first.</p>
     */
    private int available(
            int spotIndex,
            int arrivalStep,
            int[] stock,
            int[] opponentReserved,
            int[] ownResolved,
            int[][] ownStepsBySpot,
            int lookaheadTaken) {
        int[] steps = ownStepsBySpot[spotIndex];
        int ownBefore = 0;
        for (int index = ownResolved[spotIndex]; index < steps.length; index++) {
            if (steps[index] >= arrivalStep) {
                break;
            }
            ownBefore++;
        }
        return stock[spotIndex] - ownBefore - opponentReserved[spotIndex] - lookaheadTaken;
    }

    private int[][] ownStepsBySpot(List<PlannedOwnOpportunityEvent> ownEvents) {
        List<List<Integer>> buckets = new ArrayList<>();
        for (int index = 0; index < orderedSpots.size(); index++) {
            buckets.add(new ArrayList<>());
        }
        for (PlannedOwnOpportunityEvent event : ownEvents) {
            buckets.get(spotIndexByPosition.get(event.spot())).add(event.arrivalStep());
        }
        int[][] steps = new int[orderedSpots.size()][];
        for (int index = 0; index < orderedSpots.size(); index++) {
            List<Integer> bucket = buckets.get(index);
            bucket.sort(Comparator.naturalOrder());
            steps[index] = bucket.stream().mapToInt(Integer::intValue).toArray();
        }
        return steps;
    }

    private static final Comparator<Choice> CHOICE_ORDER = Comparator
            .comparingInt(Choice::capacity).reversed()
            .thenComparing(Comparator.comparingInt(Choice::ownOpportunitiesStarved).reversed())
            .thenComparingInt(Choice::arrival)
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

    private static long key(int spotIndex, int step) {
        return ((long) spotIndex << 32) | Integer.toUnsignedLong(step);
    }

    /**
     * One reverse Pareto search per Udon spot over AUTHORITATIVE CURRENT traffic.
     *
     * <p>This is the only pathfinding M16 performs. It runs once per planning run, never per candidate,
     * never per terminal plan, never per reroute and never per event.</p>
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

    /** Bounded per-candidate contest counts over the fixed no-own-plan baseline. */
    record CoupledRouteContest(int contestedCollections, int strongContestedCollections) {
        CoupledRouteContest {
            if (contestedCollections < 0 || strongContestedCollections < 0
                    || strongContestedCollections > contestedCollections) {
                throw new IllegalArgumentException("Coupled contest counts must be consistent");
            }
        }

        static CoupledRouteContest empty() {
            return new CoupledRouteContest(0, 0);
        }
    }

    private record Raw(AgentId agentId, Position spot, int step) {
    }

    private record Collector(int groupRawId, int agentIndex, int rawKind, Position position, int fuel) {
    }

    /**
     * One entry on the shared chronological timeline.
     *
     * <p>{@code sideRank} is zero for the opponent and one for us, which is the whole equal-step tie
     * policy: at an identical step the opponent is always resolved first. For an opponent arrival
     * {@code tieA} is the collector index and {@code tieB} the spot index; for an own arrival
     * {@code tieA} is the globally unique stable ordinal.</p>
     */
    private record Arrival(
            int step, int sideRank, int tieA, int tieB, int spotIndex, int legSteps, int legFuel) {

        private static Arrival opponent(
                int step, int collectorIndex, int spotIndex, int legSteps, int legFuel) {
            return new Arrival(step, 0, collectorIndex, spotIndex, spotIndex, legSteps, legFuel);
        }

        private static Arrival own(int step, int spotIndex, int stableOrdinal) {
            return new Arrival(step, 1, stableOrdinal, spotIndex, spotIndex, 0, 0);
        }

        private boolean own() {
            return sideRank == 1;
        }
    }

    private record Choice(
            int spotIndex, Position spot, RouteCost leg, int arrival, int available, int capacity,
            int ownOpportunitiesStarved, boolean repeatedBrand) {
    }

    private record Chain(int capacity, int ownOpportunitiesStarved) {
    }

    private record Outcome(
            List<OpponentFullDayClaim> opponentClaims,
            List<CoupledOwnEventResult> ownResults,
            int observedNow,
            int directIntent,
            int followOnIntent,
            int equalStepContests,
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
