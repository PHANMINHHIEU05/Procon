package vn.ptit.procon.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentFuel;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.rules.ActionRules;
import vn.ptit.procon.rules.FuelRules;
import vn.ptit.procon.rules.MovementRules;

/** Stateless, deterministic, team-level exact day execution engine. */
public final class DaySimulator {

    public DaySimulationResult simulate(DayState state, TeamPlan plan) {
        if (state == null) {
            return invalid(SimulationFailure.team(
                    SimulationFailureCode.INVALID_STATE, "Day state must not be null"), List.of());
        }
        if (plan == null) {
            return invalid(SimulationFailure.team(
                    SimulationFailureCode.MISSING_AGENT_PLAN, "Team plan must not be null"), List.of());
        }

        Optional<SimulationFailure> planFailure = validatePlanShape(state, plan);
        if (planFailure.isPresent()) {
            return invalid(planFailure.orElseThrow(), List.of());
        }

        Execution execution = new Execution(state, plan);
        return execution.run();
    }

    private Optional<SimulationFailure> validatePlanShape(DayState state, TeamPlan plan) {
        Set<AgentId> stateIds = new HashSet<>();
        for (AgentState agent : state.agents()) {
            stateIds.add(agent.id());
        }
        for (AgentId planId : plan.actionsByAgent().keySet()) {
            if (!stateIds.contains(planId)) {
                return Optional.of(SimulationFailure.agent(
                        SimulationFailureCode.UNKNOWN_AGENT,
                        planId,
                        0,
                        -1,
                        "Plan contains unknown agent " + planId));
            }
        }
        for (AgentState agent : state.agents()) {
            if (!plan.actionsByAgent().containsKey(agent.id())) {
                return Optional.of(SimulationFailure.agent(
                        SimulationFailureCode.MISSING_AGENT_PLAN,
                        agent.id(),
                        0,
                        -1,
                        "Plan is missing agent " + agent.id()));
            }
        }
        return Optional.empty();
    }

    private static InvalidDaySimulationResult invalid(
            SimulationFailure failure, List<SimulationEvent> events) {
        return new InvalidDaySimulationResult(failure, events);
    }

    private static final class Execution {

        private final DayState state;
        private final HexMap map;
        private final int budget;
        private final List<RuntimeAgent> agents = new ArrayList<>();
        private final Map<Position, UdonSpot> spots = new HashMap<>();
        private final Map<Position, Integer> stock;
        private final Map<AgentId, Set<Position>> visitedSpots = new HashMap<>();
        private final Map<AgentId, Integer> portions = new LinkedHashMap<>();
        private final Set<BrandId> brands = new LinkedHashSet<>();
        private final Map<Position, Integer> roadSteps = new LinkedHashMap<>();
        private final List<TimelineStep> timeline = new ArrayList<>();
        private final List<SimulationEvent> events = new ArrayList<>();

        private Execution(DayState state, TeamPlan plan) {
            this.state = state;
            this.map = state.matchData().map();
            this.budget = state.stepBudget();
            this.stock = new LinkedHashMap<>(state.spotStock());

            for (UdonSpot spot : state.matchData().udonSpots()) {
                spots.put(spot.position(), spot);
            }
            for (AgentState agent : state.agents()) {
                agents.add(new RuntimeAgent(agent, plan.actionsFor(agent.id())));
                visitedSpots.put(agent.id(), new HashSet<>());
                portions.put(agent.id(), 0);
            }
        }

        private DaySimulationResult run() {
            if (SimulationSemantics.collectAtStartOfDay()) {
                collectUdon(0, agents);
            }

            for (int step = 1; step <= budget; step++) {
                for (RuntimeAgent agent : agents) {
                    Optional<SimulationFailure> failure = prepareAction(agent, step - 1);
                    if (failure.isPresent()) {
                        return invalid(failure.orElseThrow(), events);
                    }
                }

                List<RuntimeAgent> arrivals = new ArrayList<>();
                Map<AgentId, AgentActivity> activities = new LinkedHashMap<>();
                for (RuntimeAgent agent : agents) {
                    advanceOneStep(agent, step, arrivals, activities);
                }

                collectUdon(step, arrivals);
                applyRefueling(step);
                countRoadOccupancy();
                timeline.add(snapshot(step, activities));
            }

            for (RuntimeAgent agent : agents) {
                if (agent.actionIndex < agent.actions.size() || agent.operation != null) {
                    return invalid(SimulationFailure.agent(
                            SimulationFailureCode.STEP_OVERFLOW,
                            agent.id,
                            budget,
                            agent.actionIndex,
                            "Agent has actions remaining after the day step budget"), events);
                }
            }

            events.add(new DayCompletedEvent(budget));
            return validResult();
        }

        private Optional<SimulationFailure> prepareAction(RuntimeAgent agent, int elapsedSteps) {
            if (agent.operation != null || agent.actionIndex >= agent.actions.size()) {
                return Optional.empty();
            }

            AgentAction action = agent.actions.get(agent.actionIndex);
            if (action instanceof WaitAction wait) {
                int duration = ActionRules.waitCost(wait).steps();
                if (elapsedSteps + duration > budget) {
                    return Optional.of(stepOverflow(agent, elapsedSteps, duration));
                }
                agent.operation = new WaitOperation(duration);
                return Optional.empty();
            }

            MoveAction move = (MoveAction) action;
            Optional<Position> geometricNeighbor = map.neighbor(agent.position, move.direction());
            if (geometricNeighbor.isEmpty()) {
                return Optional.of(SimulationFailure.agent(
                        SimulationFailureCode.NOT_ADJACENT,
                        agent.id,
                        elapsedSteps,
                        agent.actionIndex,
                        "Move direction leaves the map from " + agent.position));
            }
            Position destination = geometricNeighbor.orElseThrow();
            if (map.terrainAt(destination) == Terrain.POND) {
                return Optional.of(SimulationFailure.agent(
                        SimulationFailureCode.POND_DESTINATION,
                        agent.id,
                        elapsedSteps,
                        agent.actionIndex,
                        "Move destination is POND: " + destination));
            }

            TrafficStatus sourceTraffic = null;
            if (map.terrainAt(agent.position) == Terrain.ROAD) {
                sourceTraffic = state.roadTraffic().get(agent.position);
                if (sourceTraffic == null) {
                    return Optional.of(SimulationFailure.agent(
                            SimulationFailureCode.MISSING_TRAFFIC,
                            agent.id,
                            elapsedSteps,
                            agent.actionIndex,
                            "ROAD source lacks authoritative traffic: " + agent.position));
                }
            }
            Optional<MoveCost> possibleCost =
                    MovementRules.costFromSource(map, agent.position, sourceTraffic);
            if (possibleCost.isEmpty()) {
                return Optional.of(SimulationFailure.agent(
                        SimulationFailureCode.IMPASSABLE_SOURCE,
                        agent.id,
                        elapsedSteps,
                        agent.actionIndex,
                        "Movement is impossible from source " + agent.position));
            }
            MoveCost cost = possibleCost.orElseThrow();
            if (elapsedSteps + cost.stepCost() > budget) {
                return Optional.of(stepOverflow(agent, elapsedSteps, cost.stepCost()));
            }
            if (!FuelRules.canAfford(agent.fuel, cost)) {
                return Optional.of(SimulationFailure.agent(
                        SimulationFailureCode.NO_FUEL,
                        agent.id,
                        elapsedSteps,
                        agent.actionIndex,
                        "Agent cannot afford movement from " + agent.position));
            }

            Position source = agent.position;
            events.add(new MoveStartedEvent(
                    elapsedSteps, agent.id, source, destination, cost.stepCost()));
            if (agent.fuel instanceof FiniteFuel before) {
                agent.fuel = FuelRules.remainingFuelAfterMove(agent.fuel, cost);
                int after = ((FiniteFuel) agent.fuel).amount();
                events.add(new FuelConsumedEvent(
                        elapsedSteps, agent.id, source, before.amount(), after));
            }
            agent.operation = new MoveOperation(source, destination, cost.stepCost());
            return Optional.empty();
        }

        private SimulationFailure stepOverflow(
                RuntimeAgent agent, int elapsedSteps, int actionDuration) {
            return SimulationFailure.agent(
                    SimulationFailureCode.STEP_OVERFLOW,
                    agent.id,
                    elapsedSteps,
                    agent.actionIndex,
                    "Action duration " + actionDuration + " exceeds remaining day steps");
        }

        private void advanceOneStep(
                RuntimeAgent agent,
                int step,
                List<RuntimeAgent> arrivals,
                Map<AgentId, AgentActivity> activities) {
            if (agent.operation == null) {
                agent.automaticWaitSteps++;
                activities.put(agent.id, AgentActivity.AUTO_WAITING);
                events.add(new WaitStepEvent(step, agent.id, agent.position, true));
                return;
            }

            agent.explicitSteps++;
            if (agent.operation instanceof WaitOperation wait) {
                wait.remaining--;
                activities.put(agent.id, AgentActivity.WAITING);
                events.add(new WaitStepEvent(step, agent.id, agent.position, false));
                if (wait.remaining == 0) {
                    completeOperation(agent);
                }
                return;
            }

            MoveOperation move = (MoveOperation) agent.operation;
            move.remaining--;
            agent.position = SimulationSemantics.movePositionAfterStep(
                    move.source, move.destination, move.remaining);
            activities.put(agent.id, AgentActivity.MOVING);
            if (move.remaining == 0) {
                events.add(new MoveCompletedEvent(step, agent.id, move.source, move.destination));
                arrivals.add(agent);
                completeOperation(agent);
            }
        }

        private void completeOperation(RuntimeAgent agent) {
            agent.operation = null;
            agent.actionIndex++;
        }

        private void collectUdon(int step, List<RuntimeAgent> eligibleAgents) {
            for (RuntimeAgent agent : eligibleAgents) {
                if (agent.kind != AgentKind.PATROL) {
                    continue;
                }
                UdonSpot spot = spots.get(agent.position);
                if (spot == null || !visitedSpots.get(agent.id).add(agent.position)) {
                    continue;
                }
                int available = stock.get(agent.position);
                if (available == 0) {
                    continue;
                }
                int remaining = available - 1;
                stock.put(agent.position, remaining);
                portions.put(agent.id, portions.get(agent.id) + 1);
                brands.add(spot.brand());
                events.add(new UdonCollectedEvent(
                        step, agent.id, agent.position, spot.brand(), remaining));
            }
        }

        private void applyRefueling(int step) {
            Map<Position, List<AgentId>> refuelIdsByPosition = new HashMap<>();
            for (RuntimeAgent agent : agents) {
                if (agent.kind == AgentKind.REFUEL) {
                    refuelIdsByPosition
                            .computeIfAbsent(agent.position, ignored -> new ArrayList<>())
                            .add(agent.id);
                }
            }
            int capacity = state.matchData().patrolFuelCapacity().value();
            for (RuntimeAgent agent : agents) {
                if (agent.kind != AgentKind.PATROL) {
                    continue;
                }
                List<AgentId> refuelIds = refuelIdsByPosition.get(agent.position);
                if (refuelIds == null) {
                    continue;
                }
                int before = ((FiniteFuel) agent.fuel).amount();
                if (before < capacity) {
                    agent.fuel = new FiniteFuel(capacity);
                    events.add(new RefueledEvent(
                            step, agent.id, agent.position, before, capacity, refuelIds));
                }
            }
        }

        private void countRoadOccupancy() {
            for (RuntimeAgent agent : agents) {
                if (map.terrainAt(agent.position) == Terrain.ROAD) {
                    roadSteps.merge(agent.position, 1, Integer::sum);
                }
            }
        }

        private TimelineStep snapshot(
                int step, Map<AgentId, AgentActivity> activities) {
            Map<AgentId, AgentStepState> states = new LinkedHashMap<>();
            for (RuntimeAgent agent : agents) {
                states.put(agent.id, new AgentStepState(
                        agent.position, agent.fuel, activities.get(agent.id)));
            }
            return new TimelineStep(step, states);
        }

        private ValidDaySimulationResult validResult() {
            List<AgentState> finalAgents = new ArrayList<>();
            Map<AgentId, AgentStepUsage> usage = new LinkedHashMap<>();
            for (RuntimeAgent agent : agents) {
                finalAgents.add(new AgentState(agent.id, agent.kind, agent.position, agent.fuel));
                usage.put(agent.id, new AgentStepUsage(
                        agent.explicitSteps, agent.automaticWaitSteps));
            }
            return new ValidDaySimulationResult(
                    finalAgents,
                    stock,
                    portions,
                    brands,
                    roadSteps,
                    usage,
                    timeline,
                    events);
        }
    }

    private static final class RuntimeAgent {

        private final AgentId id;
        private final AgentKind kind;
        private final List<AgentAction> actions;
        private Position position;
        private AgentFuel fuel;
        private int actionIndex;
        private Operation operation;
        private int explicitSteps;
        private int automaticWaitSteps;

        private RuntimeAgent(AgentState initial, List<AgentAction> actions) {
            this.id = initial.id();
            this.kind = initial.kind();
            this.position = initial.position();
            this.fuel = initial.fuel();
            this.actions = actions;
        }
    }

    private sealed interface Operation permits MoveOperation, WaitOperation {
    }

    private static final class MoveOperation implements Operation {

        private final Position source;
        private final Position destination;
        private int remaining;

        private MoveOperation(Position source, Position destination, int duration) {
            this.source = source;
            this.destination = destination;
            this.remaining = duration;
        }
    }

    private static final class WaitOperation implements Operation {

        private int remaining;

        private WaitOperation(int duration) {
            this.remaining = duration;
        }
    }
}