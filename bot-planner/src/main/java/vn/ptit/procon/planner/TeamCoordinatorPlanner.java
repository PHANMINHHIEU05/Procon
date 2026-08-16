package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidation;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;

/**
 * Deterministic bounded-greedy team planner. It first compares optional start-position
 * rendezvous against one no-refill team projection, then runs the final global target loop.
 */
public final class TeamCoordinatorPlanner implements DayPlanner {

    private static final Comparator<TeamTargetCandidate> TARGET_PREFERENCE = Comparator
            .comparing(TeamTargetCandidate::newBrandForTeamToday).reversed()
            .thenComparing(TeamTargetCandidate::newBrandForPatrolToday, Comparator.reverseOrder())
            .thenComparing(Comparator.comparingInt(
                    TeamTargetCandidate::projectedCollectionGain).reversed())
            .thenComparingInt(TeamTargetCandidate::routeSteps)
            .thenComparingInt(TeamTargetCandidate::routeFuel)
            .thenComparing(Comparator.comparingInt(
                    TeamTargetCandidate::resultingFuel).reversed())
            .thenComparingInt(candidate -> candidate.targetPosition().value())
            .thenComparingInt(candidate -> candidate.patrolAgentId().value());

    private static final Comparator<RefuelAssignment> REFUEL_PREFERENCE = Comparator
            .comparingInt(RefuelAssignment::teamBrandGain).reversed()
            .thenComparing(Comparator.comparingInt(
                    RefuelAssignment::teamCollectionGain).reversed())
            .thenComparingInt(RefuelAssignment::patrolCurrentFuel)
            .thenComparingInt(assignment -> assignment.route().stepsUsed())
            .thenComparingInt(assignment -> assignment.patrol().id().value())
            .thenComparingInt(assignment -> assignment.refuel().id().value());

    static Comparator<TeamTargetCandidate> targetPreference() {
        return TARGET_PREFERENCE;
    }

    private final WeightedRouteFinder patrolRouteFinder;
    private final RefuelRouteFinder refuelRouteFinder;
    private final PlanValidator validator;
    private final DayPlanner refuelAwareFallback;

    public TeamCoordinatorPlanner() {
        this(new WeightedRouteFinder(), new RefuelRouteFinder(), new PlanValidator());
    }

    public TeamCoordinatorPlanner(
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator) {
        this.patrolRouteFinder = Objects.requireNonNull(
                patrolRouteFinder, "PATROL route finder must not be null");
        this.refuelRouteFinder = Objects.requireNonNull(
                refuelRouteFinder, "REFUEL route finder must not be null");
        this.validator = Objects.requireNonNull(validator, "Plan validator must not be null");
        this.refuelAwareFallback = new RefuelAwarePlanner(
                this.patrolRouteFinder, this.refuelRouteFinder, this.validator);
    }

    @Override
    public TeamPlan plan(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        try {
            Map<PatrolRouteKey, Optional<Route>> routeCache = new LinkedHashMap<>();
            CoordinationResult withoutRefill = coordinate(state, Optional.empty(), routeCache, false);
            Optional<RefuelAssignment> assignment = selectRefuelAssignment(
                    state, withoutRefill, routeCache);
            if (assignment.isPresent()) {
                RefuelAssignment chosen = assignment.orElseThrow();
                log("TEAM_REFUEL_ASSIGN",
                        "day", state.day().value(),
                        "refuelAgent", chosen.refuel().id().value(),
                        "patrolAgent", chosen.patrol().id().value(),
                        "projectedBrandGain", chosen.teamBrandGain(),
                        "projectedCollectionGain", chosen.teamCollectionGain(),
                        "arrivalStep", chosen.rendezvousStep());
            } else {
                log("TEAM_REFUEL_NO_ASSIGN",
                        "day", state.day().value(),
                        "reason", "NO_POSITIVE_TEAM_VALUE");
            }
            CoordinationResult selected = coordinate(state, assignment, routeCache, true);

            PlanValidation validation = validator.validate(state, selected.plan());
            if (validation.valid()) {
                log("TEAM_PLAN_VALID", "day", state.day().value());
                return selected.plan();
            }
            return fallback(state, "INVALID_COORDINATED_" + validation.failure().orElseThrow().code());
        } catch (RuntimeException exception) {
            return fallback(state, "COORDINATION_UNAVAILABLE_" + exception.getClass().getSimpleName());
        }
    }

    private Optional<RefuelAssignment> selectRefuelAssignment(
            DayState state,
            CoordinationResult withoutRefill,
            Map<PatrolRouteKey, Optional<Route>> routeCache) {
        int capacity = state.matchData().patrolFuelCapacity().value();
        List<RefuelAssignment> candidates = new ArrayList<>();
        for (AgentState refuel : state.agents()) {
            if (refuel.kind() != AgentKind.REFUEL) {
                continue;
            }
            for (AgentState patrol : state.agents()) {
                if (patrol.kind() != AgentKind.PATROL) {
                    continue;
                }
                int currentFuel = ((FiniteFuel) patrol.fuel()).amount();
                if (currentFuel >= capacity) {
                    continue;
                }
                Optional<Route> possibleRoute = refuelRouteFinder.find(
                        state, refuel, patrol.position());
                if (possibleRoute.isEmpty()) {
                    continue;
                }
                Route route = possibleRoute.orElseThrow();
                int rendezvousStep = Math.max(1, route.stepsUsed());
                if (rendezvousStep >= state.stepBudget()) {
                    continue;
                }
                RefuelAssignment provisional = new RefuelAssignment(
                        refuel, patrol, route, rendezvousStep, currentFuel, 0, 0);
                CoordinationResult withRefill = coordinate(
                        state, Optional.of(provisional), routeCache, false);
                int collectionGain = withRefill.collections() - withoutRefill.collections();
                int brandGain = withRefill.brands().size() - withoutRefill.brands().size();
                candidates.add(new RefuelAssignment(
                        refuel,
                        patrol,
                        route,
                        rendezvousStep,
                        currentFuel,
                        collectionGain,
                        brandGain));
            }
        }
        return candidates.stream()
                .filter(RefuelAssignment::positiveTeamValue)
                .min(REFUEL_PREFERENCE);
    }

    private CoordinationResult coordinate(
            DayState state,
            Optional<RefuelAssignment> assignment,
            Map<PatrolRouteKey, Optional<Route>> routeCache,
            boolean diagnostics) {
        ProjectedTeam team = new ProjectedTeam(state, assignment);
        team.projectStartingCollections();

        int iteration = 0;
        while (true) {
            Optional<TeamTargetCandidate> selected = generateCandidates(
                    state, team, routeCache).stream().min(TARGET_PREFERENCE);
            if (selected.isEmpty()) {
                break;
            }
            TeamTargetCandidate candidate = selected.orElseThrow();
            iteration++;
            if (diagnostics) {
                log("TEAM_TARGET_SELECT",
                        "day", state.day().value(),
                        "iteration", iteration,
                        "agent", candidate.patrolAgentId().value(),
                        "target", candidate.targetPosition().value(),
                        "brand", candidate.brand().value(),
                        "teamNewBrand", candidate.newBrandForTeamToday(),
                        "steps", candidate.routeSteps(),
                        "fuel", candidate.routeFuel());
            }
            team.apply(candidate);
        }
        return team.toResult();
    }

    private List<TeamTargetCandidate> generateCandidates(
            DayState state,
            ProjectedTeam team,
            Map<PatrolRouteKey, Optional<Route>> routeCache) {
        List<TeamTargetCandidate> candidates = new ArrayList<>();
        for (ProjectedPatrol patrol : team.patrols.values()) {
            if (patrol.remainingSteps == 0) {
                continue;
            }
            AgentState projectedAgent = AgentState.patrol(
                    patrol.id, patrol.position, patrol.remainingFuel);
            for (UdonSpot spot : team.orderedSpots) {
                if (team.stock.getOrDefault(spot.position(), 0) <= 0
                        || patrol.visitedSpots.contains(spot.position())) {
                    continue;
                }
                PatrolRouteKey key = new PatrolRouteKey(
                        patrol.id, patrol.position, patrol.remainingFuel, spot.position());
                Optional<Route> possibleRoute = routeCache.computeIfAbsent(
                        key, ignored -> patrolRouteFinder.find(state, projectedAgent, spot.position()));
                if (possibleRoute.isEmpty()) {
                    continue;
                }
                Route route = possibleRoute.orElseThrow();
                if (route.stepsUsed() > patrol.remainingSteps
                        || route.fuelUsed() > patrol.remainingFuel) {
                    continue;
                }
                int collectionGain = team.projectedCollectionsOn(route, patrol);
                if (collectionGain == 0) {
                    continue;
                }
                candidates.add(new TeamTargetCandidate(
                        patrol.id,
                        spot.position(),
                        spot.brand(),
                        route,
                        route.stepsUsed(),
                        route.fuelUsed(),
                        !patrol.brands.contains(spot.brand()),
                        !team.brands.contains(spot.brand()),
                        collectionGain,
                        patrol.remainingFuel - route.fuelUsed()));
            }
        }
        return candidates;
    }

    private TeamPlan fallback(DayState state, String reason) {
        try {
            TeamPlan refuelAware = refuelAwareFallback.plan(state);
            if (validator.validate(state, refuelAware).valid()) {
                log("TEAM_PLAN_FALLBACK", "reason", reason, "mode", "REFUEL_AWARE");
                return refuelAware;
            }
        } catch (RuntimeException exception) {
            reason += "_REFUEL_AWARE_UNAVAILABLE_" + exception.getClass().getSimpleName();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        PlanValidation waitValidation = validator.validate(state, waitAll);
        if (!waitValidation.valid()) {
            throw new IllegalStateException(
                    "Safe fallback plan rejected: " + waitValidation.failure().orElseThrow());
        }
        log("TEAM_PLAN_FALLBACK", "reason", reason, "mode", "WAIT");
        return waitAll;
    }

    private void log(String event, Object... fields) {
        StringBuilder message = new StringBuilder(event);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            message.append(' ').append(fields[index]).append('=').append(fields[index + 1]);
        }
        System.out.println(message);
    }

    private static final class ProjectedTeam {

        private final DayState state;
        private final Optional<RefuelAssignment> assignment;
        private final Map<Position, Integer> stock;
        private final Set<BrandId> brands = new LinkedHashSet<>();
        private final Map<AgentId, ProjectedPatrol> patrols = new LinkedHashMap<>();
        private final Map<AgentId, ProjectedRefuel> refuels = new LinkedHashMap<>();
        private final Map<Position, UdonSpot> spotsByPosition = new LinkedHashMap<>();
        private final List<UdonSpot> orderedSpots;
        private int collections;

        private ProjectedTeam(DayState state, Optional<RefuelAssignment> assignment) {
            this.state = state;
            this.assignment = assignment;
            this.stock = new LinkedHashMap<>(state.spotStock());
            this.orderedSpots = state.matchData().udonSpots().stream()
                    .sorted(Comparator.comparingInt(spot -> spot.position().value()))
                    .toList();
            for (UdonSpot spot : orderedSpots) {
                spotsByPosition.put(spot.position(), spot);
            }
            int capacity = state.matchData().patrolFuelCapacity().value();
            for (AgentState agent : state.agents()) {
                if (agent.kind() == AgentKind.REFUEL) {
                    Optional<RefuelAssignment> service = assignment.filter(
                            value -> value.refuel().id().equals(agent.id()));
                    Position position = service.map(value -> value.route().goal())
                            .orElse(agent.position());
                    int remainingSteps = state.stepBudget()
                            - service.map(value -> value.route().stepsUsed()).orElse(0);
                    refuels.put(agent.id(), new ProjectedRefuel(
                            agent.id(), position, remainingSteps, service.map(value -> value.patrol().id())));
                    continue;
                }
                boolean served = assignment.filter(value -> value.patrol().id().equals(agent.id())).isPresent();
                int initialWait = served ? assignment.orElseThrow().rendezvousStep() : 0;
                int fuel = served ? capacity : ((FiniteFuel) agent.fuel()).amount();
                patrols.put(agent.id(), new ProjectedPatrol(
                        agent.id(), agent.position(), fuel, state.stepBudget() - initialWait, initialWait));
            }
        }

        private void projectStartingCollections() {
            for (ProjectedPatrol patrol : patrols.values()) {
                projectCollection(patrol.position, patrol);
            }
        }

        private int projectedCollectionsOn(Route route, ProjectedPatrol patrol) {
            Map<Position, Integer> available = new LinkedHashMap<>(stock);
            Set<Position> visited = new LinkedHashSet<>(patrol.visitedSpots);
            Position cursor = route.start();
            int gain = 0;
            for (Direction direction : route.directions()) {
                cursor = state.matchData().map().neighbor(cursor, direction).orElseThrow();
                UdonSpot spot = spotsByPosition.get(cursor);
                if (spot != null && visited.add(cursor) && available.getOrDefault(cursor, 0) > 0) {
                    available.put(cursor, available.get(cursor) - 1);
                    gain++;
                }
            }
            return gain;
        }

        private void apply(TeamTargetCandidate candidate) {
            ProjectedPatrol patrol = patrols.get(candidate.patrolAgentId());
            patrol.actions.addAll(candidate.route().toMoveActions());
            patrol.remainingSteps -= candidate.routeSteps();
            patrol.remainingFuel -= candidate.routeFuel();
            for (Direction direction : candidate.route().directions()) {
                patrol.position = state.matchData().map()
                        .neighbor(patrol.position, direction).orElseThrow();
                projectCollection(patrol.position, patrol);
            }
        }

        private void projectCollection(Position position, ProjectedPatrol patrol) {
            UdonSpot spot = spotsByPosition.get(position);
            if (spot == null || !patrol.visitedSpots.add(position)) {
                return;
            }
            int available = stock.getOrDefault(position, 0);
            if (available <= 0) {
                return;
            }
            stock.put(position, available - 1);
            patrol.brands.add(spot.brand());
            brands.add(spot.brand());
            collections++;
        }

        private CoordinationResult toResult() {
            Map<AgentId, List<AgentAction>> actionsByAgent = new LinkedHashMap<>();
            for (AgentState agent : state.agents()) {
                if (agent.kind() == AgentKind.PATROL) {
                    ProjectedPatrol patrol = patrols.get(agent.id());
                    int usedSteps = state.stepBudget() - patrol.remainingSteps;
                    actionsByAgent.put(agent.id(), ActionPlanCompleter.complete(
                            patrol.actions, usedSteps, state.stepBudget()));
                } else if (assignment.filter(value -> value.refuel().id().equals(agent.id())).isPresent()) {
                    Route route = assignment.orElseThrow().route();
                    ProjectedRefuel refuel = refuels.get(agent.id());
                    actionsByAgent.put(agent.id(), ActionPlanCompleter.complete(
                            route.toMoveActions(),
                            state.stepBudget() - refuel.remainingSteps,
                            state.stepBudget()));
                } else {
                    actionsByAgent.put(agent.id(), List.of(new WaitAction(state.stepBudget())));
                }
            }
            return new CoordinationResult(new TeamPlan(actionsByAgent), collections, brands);
        }
    }

    private static final class ProjectedPatrol {

        private final AgentId id;
        private final Set<Position> visitedSpots = new LinkedHashSet<>();
        private final Set<BrandId> brands = new LinkedHashSet<>();
        private final List<AgentAction> actions = new ArrayList<>();
        private Position position;
        private int remainingFuel;
        private int remainingSteps;

        private ProjectedPatrol(
                AgentId id, Position position, int fuel, int remainingSteps, int initialWait) {
            this.id = id;
            this.position = position;
            this.remainingFuel = fuel;
            this.remainingSteps = remainingSteps;
            if (initialWait > 0) {
                actions.add(new WaitAction(initialWait));
            }
        }
    }

    private static final class ProjectedRefuel {

        private final AgentId id;
        private final Position position;
        private final int remainingSteps;
        private final Optional<AgentId> servicePatrolId;

        private ProjectedRefuel(
                AgentId id,
                Position position,
                int remainingSteps,
                Optional<AgentId> servicePatrolId) {
            this.id = id;
            this.position = position;
            this.remainingSteps = remainingSteps;
            this.servicePatrolId = servicePatrolId;
        }
    }

    private record PatrolRouteKey(
            AgentId agentId, Position start, int fuel, Position target) {
    }

    private record CoordinationResult(TeamPlan plan, int collections, Set<BrandId> brands) {

        private CoordinationResult {
            brands = Set.copyOf(brands);
        }
    }

    private record RefuelAssignment(
            AgentState refuel,
            AgentState patrol,
            Route route,
            int rendezvousStep,
            int patrolCurrentFuel,
            int teamCollectionGain,
            int teamBrandGain) {

        private boolean positiveTeamValue() {
            return teamBrandGain > 0 || teamBrandGain == 0 && teamCollectionGain > 0;
        }
    }
}