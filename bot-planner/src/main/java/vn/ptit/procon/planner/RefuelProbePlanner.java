package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.MoveStartedEvent;
import vn.ptit.procon.engine.PlanValidation;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.SimulationEvent;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.rules.FuelRules;
import vn.ptit.procon.rules.MovementRules;

/** Isolated deterministic planner for exercising one locally modeled REFUEL rendezvous. */
public final class RefuelProbePlanner implements DayPlanner {

    private final WeightedRouteFinder patrolRouteFinder;
    private final RefuelRouteFinder refuelRouteFinder;
    private final PlanValidator validator;
    private final DaySimulator simulator;
    private final DayPlanner brandAwareFallback;

    public RefuelProbePlanner() {
        this(new WeightedRouteFinder(), new RefuelRouteFinder(), new DaySimulator());
    }

    public RefuelProbePlanner(
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            DaySimulator simulator) {
        this(
                patrolRouteFinder,
                refuelRouteFinder,
                new PlanValidator(Objects.requireNonNull(simulator, "Day simulator must not be null")),
                simulator);
    }

    RefuelProbePlanner(
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator,
            DaySimulator simulator) {
        this.patrolRouteFinder = Objects.requireNonNull(
                patrolRouteFinder, "PATROL route finder must not be null");
        this.refuelRouteFinder = Objects.requireNonNull(
                refuelRouteFinder, "REFUEL route finder must not be null");
        this.validator = Objects.requireNonNull(validator, "Plan validator must not be null");
        this.simulator = Objects.requireNonNull(simulator, "Day simulator must not be null");
        this.brandAwareFallback = new BrandAwarePlanner(this.patrolRouteFinder, this.validator);
    }

    @Override
    public TeamPlan plan(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        Optional<Assignment> selected = selectAssignment(state);
        if (selected.isEmpty()) {
            log("REFUEL_PROBE_NO_ASSIGN",
                    "day", state.day().value(),
                    "reason", "NO_FEASIBLE_RENDEZVOUS_AND_MOVE");
            return fallback(state, "NO_FEASIBLE_RENDEZVOUS_AND_MOVE");
        }

        Assignment assignment = selected.orElseThrow();
        TeamPlan basePlan;
        try {
            basePlan = brandAwareFallback.plan(state);
        } catch (RuntimeException exception) {
            basePlan = validatedWaitPlan(state);
            log("REFUEL_PROBE_BASE_PLAN_FALLBACK",
                    "reason", "BRAND_AWARE_UNAVAILABLE_" + exception.getClass().getSimpleName(),
                    "mode", "WAIT");
        }

        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>(basePlan.actionsByAgent());
        actions.put(assignment.refuel().id(), refuelActions(assignment, state.stepBudget()));
        actions.put(assignment.patrol().id(), patrolActions(assignment, state.stepBudget()));
        TeamPlan probe = new TeamPlan(actions);
        PlanValidation validation = validator.validate(state, probe);
        if (!validation.valid()) {
            return fallback(state, "INVALID_PROBE_" + validation.failure().orElseThrow().code());
        }

        DaySimulationResult simulation = simulator.simulate(state, probe);
        if (!(simulation instanceof ValidDaySimulationResult valid)
                || !containsExpectedProbe(valid, assignment)) {
            return fallback(state, "PROBE_TIMELINE_NOT_OBSERVED");
        }

        log("REFUEL_PROBE_ASSIGN",
                "day", state.day().value(),
                "refuelAgent", assignment.refuel().id().value(),
                "patrolAgent", assignment.patrol().id().value(),
                "rendezvous", assignment.patrol().position().value(),
                "arrivalStep", assignment.arrivalStep(),
                "fuelBefore", assignment.patrolFuel());
        log("REFUEL_PROBE_PLAN_VALID");
        return probe;
    }

    private Optional<Assignment> selectAssignment(DayState state) {
        int capacity = state.matchData().patrolFuelCapacity().value();
        List<Assignment> candidates = new ArrayList<>();
        for (AgentState patrol : state.agents()) {
            if (patrol.kind() != AgentKind.PATROL) {
                continue;
            }
            int patrolFuel = ((FiniteFuel) patrol.fuel()).amount();
            if (patrolFuel >= capacity) {
                continue;
            }
            for (AgentState refuel : state.agents()) {
                if (refuel.kind() != AgentKind.REFUEL) {
                    continue;
                }
                refuelRouteFinder.find(state, refuel, patrol.position()).ifPresent(route -> {
                    int arrivalStep = Math.max(1, route.stepsUsed());
                    int remainingSteps = state.stepBudget() - arrivalStep;
                    selectPostRefillMove(state, patrol, capacity, remainingSteps)
                            .map(move -> new Assignment(
                                    refuel, patrol, route, arrivalStep, patrolFuel, move))
                            .ifPresent(candidates::add);
                });
            }
        }

        Comparator<Assignment> preference = Comparator
                .comparingInt((Assignment candidate) -> candidate.refuelRoute().stepsUsed())
                .thenComparingInt(Assignment::patrolFuel)
                .thenComparingInt(candidate -> candidate.patrol().id().value())
                .thenComparingInt(candidate -> candidate.refuel().id().value());
        return candidates.stream().min(preference);
    }

    private Optional<PostRefillMove> selectPostRefillMove(
            DayState state,
            AgentState patrol,
            int capacity,
            int remainingSteps) {
        if (remainingSteps <= 0) {
            return Optional.empty();
        }

        AgentState refilled = AgentState.patrol(patrol.id(), patrol.position(), capacity);
        Comparator<UdonRoute> preference = Comparator
                .comparingInt((UdonRoute candidate) -> candidate.route().stepsUsed())
                .thenComparingInt(candidate -> candidate.route().fuelUsed())
                .thenComparingInt(candidate -> candidate.target().value());
        Optional<UdonRoute> udonRoute = state.matchData().udonSpots().stream()
                .filter(spot -> state.spotStock().getOrDefault(spot.position(), 0) > 0)
                .map(spot -> patrolRouteFinder.find(state, refilled, spot.position())
                        .map(route -> new UdonRoute(spot.position(), route)))
                .flatMap(Optional::stream)
                .filter(candidate -> !candidate.route().directions().isEmpty())
                .filter(candidate -> candidate.route().stepsUsed() <= remainingSteps)
                .min(preference);
        if (udonRoute.isPresent()) {
            Direction direction = udonRoute.orElseThrow().route().directions().getFirst();
            return postRefillMove(state, refilled, direction, remainingSteps);
        }

        for (Direction direction : Direction.values()) {
            Optional<PostRefillMove> move = postRefillMove(
                    state, refilled, direction, remainingSteps);
            if (move.isPresent()) {
                return move;
            }
        }
        return Optional.empty();
    }

    private Optional<PostRefillMove> postRefillMove(
            DayState state,
            AgentState patrol,
            Direction direction,
            int remainingSteps) {
        HexMap map = state.matchData().map();
        Optional<Position> destination = map.neighbor(patrol.position(), direction);
        if (destination.isEmpty() || map.terrainAt(destination.orElseThrow()) == Terrain.POND) {
            return Optional.empty();
        }
        TrafficStatus traffic = map.terrainAt(patrol.position()) == Terrain.ROAD
                ? state.roadTraffic().get(patrol.position())
                : null;
        if (map.terrainAt(patrol.position()) == Terrain.ROAD && traffic == null) {
            return Optional.empty();
        }
        Optional<MoveCost> possibleCost = MovementRules.costFromSource(map, patrol.position(), traffic);
        if (possibleCost.isEmpty()) {
            return Optional.empty();
        }
        MoveCost cost = possibleCost.orElseThrow();
        if (cost.stepCost() > remainingSteps || !FuelRules.canAfford(patrol.fuel(), cost)) {
            return Optional.empty();
        }
        return Optional.of(new PostRefillMove(direction, cost.stepCost()));
    }

    private List<AgentAction> refuelActions(Assignment assignment, int daySteps) {
        return ActionPlanCompleter.complete(
                assignment.refuelRoute().toMoveActions(),
                assignment.refuelRoute().stepsUsed(),
                daySteps);
    }

    private List<AgentAction> patrolActions(Assignment assignment, int daySteps) {
        List<AgentAction> actions = new ArrayList<>();
        actions.add(new WaitAction(assignment.arrivalStep()));
        actions.add(new MoveAction(assignment.postRefillMove().direction()));
        int usedSteps = assignment.arrivalStep() + assignment.postRefillMove().stepsUsed();
        return ActionPlanCompleter.complete(actions, usedSteps, daySteps);
    }

    private boolean containsExpectedProbe(
            ValidDaySimulationResult simulation, Assignment assignment) {
        List<SimulationEvent> events = simulation.events();
        int refillIndex = -1;
        for (int index = 0; index < events.size(); index++) {
            SimulationEvent event = events.get(index);
            if (event instanceof RefueledEvent refueled
                    && refueled.step() == assignment.arrivalStep()
                    && refueled.patrolId().equals(assignment.patrol().id())
                    && refueled.refuelAgents().contains(assignment.refuel().id())) {
                refillIndex = index;
                break;
            }
        }
        if (refillIndex < 0) {
            return false;
        }
        for (int index = refillIndex + 1; index < events.size(); index++) {
            SimulationEvent event = events.get(index);
            if (event instanceof MoveStartedEvent move
                    && move.agentId().equals(assignment.patrol().id())
                    && move.step() >= assignment.arrivalStep()) {
                return true;
            }
        }
        return false;
    }

    private TeamPlan fallback(DayState state, String reason) {
        try {
            TeamPlan brandAware = brandAwareFallback.plan(state);
            if (validator.validate(state, brandAware).valid()) {
                log("REFUEL_PROBE_FALLBACK", "reason", reason, "mode", "BRAND_AWARE");
                return brandAware;
            }
        } catch (RuntimeException exception) {
            reason = reason + "_BRAND_AWARE_UNAVAILABLE_" + exception.getClass().getSimpleName();
        }
        return waitFallback(state, reason);
    }

    private TeamPlan waitFallback(DayState state, String reason) {
        TeamPlan waitAll = validatedWaitPlan(state);
        log("REFUEL_PROBE_FALLBACK", "reason", reason, "mode", "WAIT");
        return waitAll;
    }

    private TeamPlan validatedWaitPlan(DayState state) {
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        PlanValidation validation = validator.validate(state, waitAll);
        if (!validation.valid()) {
            throw new IllegalStateException(
                    "Safe fallback plan rejected: " + validation.failure().orElseThrow());
        }
        return waitAll;
    }

    private void log(String event, Object... fields) {
        StringBuilder message = new StringBuilder(event);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            message.append(' ').append(fields[index]).append('=').append(fields[index + 1]);
        }
        System.out.println(message);
    }

    private record Assignment(
            AgentState refuel,
            AgentState patrol,
            Route refuelRoute,
            int arrivalStep,
            int patrolFuel,
            PostRefillMove postRefillMove) {
    }

    private record PostRefillMove(Direction direction, int stepsUsed) {
    }

    private record UdonRoute(Position target, Route route) {
    }
}