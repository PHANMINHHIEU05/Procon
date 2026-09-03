package vn.ptit.procon.planner.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.rules.MovementRules;

/** Immutable whole-team state whose stock timeline is rebuilt from all committed route prefixes. */
final class JointTeamSearchState {

    /** A completed REFUEL prefix used to seed a joint search root. */
    record RefuelRootSchedule(AgentId refuelId, Map<AgentId, PatrolSupport> patrolSupports,
            List<AgentAction> refuelActions, int refuelElapsedSteps, String signature) {
        RefuelRootSchedule {
            Objects.requireNonNull(refuelId, "REFUEL id must not be null");
            patrolSupports = Map.copyOf(Objects.requireNonNull(patrolSupports, "PATROL supports must not be null"));
            refuelActions = List.copyOf(Objects.requireNonNull(refuelActions, "REFUEL actions must not be null"));
            Objects.requireNonNull(signature, "REFUEL signature must not be null");
            if (patrolSupports.isEmpty() || refuelElapsedSteps <= 0) {
                throw new IllegalArgumentException("REFUEL root must contain a positive completed service prefix");
            }
        }

        RefuelRootSchedule(AgentId refuelId, AgentId patrolId, Route route, int arrivalStep) {
            this(refuelId, Map.of(patrolId, new PatrolSupport(route.goal(), arrivalStep, Integer.MAX_VALUE,
                            List.of(new WaitAction(arrivalStep)))),
                    refuelPrefix(route, arrivalStep), arrivalStep,
                    refuelId.value() + ">" + patrolId.value() + "@" + arrivalStep + ":"
                            + directionKey(route.directions()));
        }

        String futureKey() {
            // The completed route history is deliberately excluded. The patrol state and collection
            // chronology below retain every effect that can change continuation semantics.
            return refuelId.value() + "@" + refuelElapsedSteps;
        }

        private static List<AgentAction> refuelPrefix(Route route, int arrivalStep) {
            List<AgentAction> result = new ArrayList<>(route.toMoveActions());
            if (arrivalStep > route.stepsUsed()) result.add(new WaitAction(arrivalStep - route.stepsUsed()));
            return List.copyOf(result);
        }
    }

    record PatrolSupport(Position position, int elapsedSteps, int remainingFuel, List<AgentAction> actions) {
        PatrolSupport {
            Objects.requireNonNull(position, "PATROL support position must not be null");
            if (elapsedSteps < 0 || remainingFuel < 0) {
                throw new IllegalArgumentException("PATROL support resources must be non-negative");
            }
            actions = List.copyOf(Objects.requireNonNull(actions, "PATROL support actions must not be null"));
        }
    }

    record PatrolPrefix(
            AgentId id,
            Position start,
            Position position,
            int elapsedSteps,
            int remainingFuel,
            boolean stopped,
            int zeroGainCommittedLegs,
            int strategicDecisionCount,
            List<AgentAction> actions) {
        PatrolPrefix {
            Objects.requireNonNull(id, "PATROL id must not be null");
            Objects.requireNonNull(start, "PATROL start must not be null");
            Objects.requireNonNull(position, "PATROL position must not be null");
            if (elapsedSteps < 0 || remainingFuel < 0 || zeroGainCommittedLegs < 0
                    || strategicDecisionCount < 0) {
                throw new IllegalArgumentException("PATROL resources must be non-negative");
            }
            actions = List.copyOf(Objects.requireNonNull(actions, "PATROL actions must not be null"));
        }

        PatrolPrefix extend(JointRouteCatalog.CatalogRoute route, boolean gainedCollection) {
            Route materialized = route.route();
            if (!position.equals(materialized.start())) {
                throw new IllegalArgumentException("Route does not begin at the PATROL position");
            }
            List<AgentAction> nextActions = new ArrayList<>(actions);
            nextActions.addAll(materialized.toMoveActions());
            return new PatrolPrefix(
                    id,
                    start,
                    materialized.goal(),
                    elapsedSteps + materialized.stepsUsed(),
                    remainingFuel - materialized.fuelUsed(),
                    false,
                    zeroGainCommittedLegs + (gainedCollection ? 0 : 1),
                    strategicDecisionCount + 1,
                    nextActions);
        }

        PatrolPrefix stop() {
            return new PatrolPrefix(id, start, position, elapsedSteps, remainingFuel, true,
                    zeroGainCommittedLegs, strategicDecisionCount + 1, actions);
        }
    }

    record TimelineArrival(AgentId agentId, Position spot, int step) {
    }

    static final class OwnTeamTimeline {
        private final Map<Position, Integer> remainingStock;
        private final Map<AgentId, Set<Position>> visitedByPatrol;
        private final Map<Position, List<TimelineArrival>> arrivalsBySpot;
        private final Set<BrandId> brands;
        private final int successfulCollections;

        private OwnTeamTimeline(
                Map<Position, Integer> remainingStock,
                Map<AgentId, Set<Position>> visitedByPatrol,
                Map<Position, List<TimelineArrival>> arrivalsBySpot,
                Set<BrandId> brands,
                int successfulCollections) {
            this.remainingStock = immutablePositions(remainingStock);
            this.visitedByPatrol = immutableVisited(visitedByPatrol);
            this.arrivalsBySpot = immutableArrivals(arrivalsBySpot);
            this.brands = Set.copyOf(brands);
            this.successfulCollections = successfulCollections;
        }

        Map<Position, Integer> remainingStock() {
            return remainingStock;
        }

        Set<Position> visitedBy(AgentId patrolId) {
            return visitedByPatrol.getOrDefault(patrolId, Set.of());
        }

        Set<BrandId> brands() {
            return brands;
        }

        int successfulCollections() {
            return successfulCollections;
        }

        Map<Position, List<TimelineArrival>> arrivalsBySpot() {
            return arrivalsBySpot;
        }

        private static OwnTeamTimeline replay(
                DayState state,
                Map<AgentId, PatrolPrefix> patrols) {
            Map<Position, UdonSpot> spotsByPosition = new LinkedHashMap<>();
            for (UdonSpot spot : state.matchData().udonSpots()) {
                spotsByPosition.put(spot.position(), spot);
            }
            List<PositionArrival> events = new ArrayList<>();
            for (PatrolPrefix patrol : patrols.values()) {
                events.add(new PositionArrival(patrol.id(), patrol.start(), 0));
                Position cursor = patrol.start();
                int elapsed = 0;
                for (AgentAction action : patrol.actions()) {
                    if (action instanceof WaitAction wait) {
                        elapsed += wait.steps();
                        continue;
                    }
                    Direction direction = ((MoveAction) action).direction();
                    TrafficStatus traffic = state.matchData().map().terrainAt(cursor) == Terrain.ROAD
                            ? state.roadTraffic().get(cursor) : null;
                    MoveCost cost = MovementRules.costFromSource(
                            state.matchData().map(), cursor, traffic).orElseThrow(() ->
                                    new IllegalStateException("Committed route has an impassable source"));
                    elapsed += cost.stepCost();
                    cursor = state.matchData().map().neighbor(cursor, direction).orElseThrow(() ->
                            new IllegalStateException("Committed route leaves the map"));
                    events.add(new PositionArrival(patrol.id(), cursor, elapsed));
                }
                if (!cursor.equals(patrol.position()) || elapsed != patrol.elapsedSteps()) {
                    throw new IllegalStateException("PATROL prefix no longer matches its action trajectory");
                }
            }
            events.sort(Comparator.comparingInt(PositionArrival::step)
                    .thenComparingInt(value -> value.agentId().value()));

            Map<Position, Integer> stock = new LinkedHashMap<>(state.spotStock());
            Map<AgentId, Set<Position>> visited = new LinkedHashMap<>();
            patrols.keySet().forEach(id -> visited.put(id, new LinkedHashSet<>()));
            Map<Position, List<TimelineArrival>> arrivals = new LinkedHashMap<>();
            Set<BrandId> brands = new LinkedHashSet<>();
            int collections = 0;
            for (PositionArrival event : events) {
                UdonSpot spot = spotsByPosition.get(event.position());
                if (spot == null) {
                    continue;
                }
                arrivals.computeIfAbsent(event.position(), ignored -> new ArrayList<>())
                        .add(new TimelineArrival(event.agentId(), event.position(), event.step()));
                Set<Position> patrolVisited = visited.get(event.agentId());
                if (!patrolVisited.add(event.position())) {
                    continue;
                }
                int available = stock.getOrDefault(event.position(), 0);
                if (available <= 0) {
                    continue;
                }
                stock.put(event.position(), available - 1);
                brands.add(spot.brand());
                collections++;
            }
            return new OwnTeamTimeline(stock, visited, arrivals, brands, collections);
        }

        private static Map<Position, Integer> immutablePositions(Map<Position, Integer> source) {
            Map<Position, Integer> result = new LinkedHashMap<>();
            source.entrySet().stream().sorted(Map.Entry.comparingByKey(
                    Comparator.comparingInt(Position::value))).forEach(entry -> result.put(
                            entry.getKey(), entry.getValue()));
            return Collections.unmodifiableMap(result);
        }

        private static Map<AgentId, Set<Position>> immutableVisited(
                Map<AgentId, Set<Position>> source) {
            Map<AgentId, Set<Position>> result = new LinkedHashMap<>();
            source.entrySet().stream().sorted(Map.Entry.comparingByKey(
                    Comparator.comparingInt(AgentId::value))).forEach(entry -> result.put(
                            entry.getKey(), Set.copyOf(entry.getValue())));
            return Collections.unmodifiableMap(result);
        }

        private static Map<Position, List<TimelineArrival>> immutableArrivals(
                Map<Position, List<TimelineArrival>> source) {
            Map<Position, List<TimelineArrival>> result = new LinkedHashMap<>();
            source.entrySet().stream().sorted(Map.Entry.comparingByKey(
                    Comparator.comparingInt(Position::value))).forEach(entry -> result.put(
                            entry.getKey(), List.copyOf(entry.getValue())));
            return Collections.unmodifiableMap(result);
        }
    }

    private record PositionArrival(AgentId agentId, Position position, int step) {
    }

    private final Map<AgentId, PatrolPrefix> patrols;
    private final Optional<RefuelRootSchedule> refuelRoot;
    private final OwnTeamTimeline timeline;
    private final String exactKey;
    private final String dominanceKey;

    JointTeamSearchState(
            DayState state,
            Map<AgentId, PatrolPrefix> patrols,
            Optional<RefuelRootSchedule> refuelRoot) {
        this.patrols = immutablePatrols(patrols);
        this.refuelRoot = Objects.requireNonNull(refuelRoot, "REFUEL root must not be null");
        this.timeline = OwnTeamTimeline.replay(state, this.patrols);
        this.exactKey = buildExactKey();
        this.dominanceKey = buildDominanceKey();
    }

    static JointTeamSearchState root(DayState state, Optional<RefuelRootSchedule> refuelRoot) {
        Objects.requireNonNull(state, "Day state must not be null");
        Map<AgentId, PatrolPrefix> patrols = new LinkedHashMap<>();
        int capacity = state.matchData().patrolFuelCapacity().value();
        for (AgentState agent : state.agents()) {
            if (agent.kind() != AgentKind.PATROL) {
                continue;
            }
            PatrolSupport support = refuelRoot.map(root -> root.patrolSupports().get(agent.id())).orElse(null);
            boolean refueled = support != null;
            int wait = refueled ? support.elapsedSteps() : 0;
            List<AgentAction> actions = refueled ? support.actions()
                    : List.of();
            patrols.put(agent.id(), new PatrolPrefix(
                    agent.id(), agent.position(), refueled ? support.position() : agent.position(), wait,
                    refueled ? Math.min(capacity, support.remainingFuel()) : ((FiniteFuel) agent.fuel()).amount(),
                    false, 0, 0, actions));
        }
        return new JointTeamSearchState(state, patrols, refuelRoot);
    }

    Map<AgentId, PatrolPrefix> patrols() {
        return patrols;
    }

    Optional<RefuelRootSchedule> refuelRoot() {
        return refuelRoot;
    }

    OwnTeamTimeline timeline() {
        return timeline;
    }

    String exactKey() {
        return exactKey;
    }

    String dominanceKey() {
        return dominanceKey;
    }

    int strategicDecisionCount() {
        return patrols.values().stream().mapToInt(PatrolPrefix::strategicDecisionCount).sum();
    }

    int zeroGainCommittedLegs() {
        return patrols.values().stream().mapToInt(PatrolPrefix::zeroGainCommittedLegs).sum();
    }

    JointTeamSearchState extend(
            DayState state,
            AgentId patrolId,
            JointRouteCatalog.CatalogRoute route) {
        PatrolPrefix current = patrols.get(patrolId);
        if (current == null || current.stopped()) {
            throw new IllegalArgumentException("Only active PATROL agents can be extended");
        }
        Route materialized = route.route();
        if (current.elapsedSteps() + materialized.stepsUsed() > state.stepBudget()
                || current.remainingFuel() < materialized.fuelUsed()) {
            throw new IllegalArgumentException("Route exceeds the PATROL's remaining resources");
        }
        Map<AgentId, PatrolPrefix> next = new LinkedHashMap<>(patrols);
        next.put(patrolId, current.extend(route, true));
        OwnTeamTimeline provisional = OwnTeamTimeline.replay(state, immutablePatrols(next));
        boolean gainedCollection = provisional.successfulCollections() > timeline.successfulCollections();
        next.put(patrolId, current.extend(route, gainedCollection));
        return new JointTeamSearchState(state, next, refuelRoot);
    }

    JointTeamSearchState stop(DayState state, AgentId patrolId) {
        PatrolPrefix current = patrols.get(patrolId);
        if (current == null || current.stopped()) {
            throw new IllegalArgumentException("Only active PATROL agents can stop");
        }
        Map<AgentId, PatrolPrefix> next = new LinkedHashMap<>(patrols);
        next.put(patrolId, current.stop());
        return new JointTeamSearchState(state, next, refuelRoot);
    }

    TeamPlan completePlan(DayState state) {
        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            if (agent.kind() == AgentKind.PATROL) {
                PatrolPrefix patrol = patrols.get(agent.id());
                List<AgentAction> complete = new ArrayList<>(patrol.actions());
                appendWait(complete, state.stepBudget() - patrol.elapsedSteps());
                actions.put(agent.id(), List.copyOf(complete));
            } else if (refuelRoot.filter(root -> root.refuelId().equals(agent.id())).isPresent()) {
                RefuelRootSchedule root = refuelRoot.orElseThrow();
                List<AgentAction> complete = new ArrayList<>(root.refuelActions());
                appendWait(complete, state.stepBudget() - root.refuelElapsedSteps());
                actions.put(agent.id(), List.copyOf(complete));
            } else {
                actions.put(agent.id(), List.of(new WaitAction(state.stepBudget())));
            }
        }
        return new TeamPlan(actions);
    }

    private String buildExactKey() {
        StringBuilder value = new StringBuilder();
        value.append(refuelRoot.map(RefuelRootSchedule::futureKey).orElse("NO_REFUEL")).append('|');
        for (PatrolPrefix patrol : patrols.values()) {
            value.append(patrol.id().value()).append('@').append(patrol.position().value())
                    .append(':').append(patrol.elapsedSteps()).append(':')
                    .append(patrol.remainingFuel()).append(':').append(patrol.stopped()).append(':');
            timeline.visitedBy(patrol.id()).stream().map(Position::value).sorted()
                    .forEach(position -> value.append(position).append(','));
            value.append(';');
        }
        value.append("stock=");
        timeline.remainingStock().entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparingInt(Position::value))).forEach(entry -> value.append(
                        entry.getKey().value()).append(':').append(entry.getValue()).append(','));
        value.append("brands=");
        timeline.brands().stream().map(BrandId::value).sorted()
                .forEach(brand -> value.append(brand).append(','));
        value.append("arrivals=");
        timeline.arrivalsBySpot().entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparingInt(Position::value))).forEach(entry -> {
                    value.append(entry.getKey().value()).append(':');
                    entry.getValue().forEach(arrival -> value.append(arrival.agentId().value())
                            .append('@').append(arrival.step()).append(','));
                    value.append(';');
                });
        return value.toString();
    }

    private String buildDominanceKey() {
        StringBuilder value = new StringBuilder();
        value.append(refuelRoot.map(RefuelRootSchedule::futureKey).orElse("NO_REFUEL")).append('|');
        for (PatrolPrefix patrol : patrols.values()) {
            value.append(patrol.id().value()).append('@').append(patrol.position().value())
                    .append(':').append(patrol.stopped()).append(':');
            timeline.visitedBy(patrol.id()).stream().map(Position::value).sorted()
                    .forEach(position -> value.append(position).append(','));
            value.append(';');
        }
        value.append("stock=");
        timeline.remainingStock().entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparingInt(Position::value))).forEach(entry -> value.append(
                        entry.getKey().value()).append(':').append(entry.getValue()).append(','));
        return value.toString();
    }

    private static Map<AgentId, PatrolPrefix> immutablePatrols(Map<AgentId, PatrolPrefix> source) {
        Map<AgentId, PatrolPrefix> result = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparingInt(AgentId::value))).forEach(entry -> result.put(
                        entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(result);
    }

    private static void appendWait(List<AgentAction> actions, int remaining) {
        if (remaining < 0) {
            throw new IllegalStateException("Committed actions exceed the day budget");
        }
        if (remaining > 0) {
            actions.add(new WaitAction(remaining));
        }
    }

    private static String directionKey(List<Direction> directions) {
        StringBuilder value = new StringBuilder(directions.size());
        for (Direction direction : directions) {
            value.append((char) ('0' + direction.code()));
        }
        return value.toString();
    }
}
