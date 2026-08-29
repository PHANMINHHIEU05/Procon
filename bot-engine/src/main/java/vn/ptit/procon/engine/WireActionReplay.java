package vn.ptit.procon.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.domain.agent.AgentFuel;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.agent.UnlimitedFuel;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.rules.FuelRules;
import vn.ptit.procon.rules.MovementRules;

/**
 * Pure, independent wire-action replayer.
 *
 * <p>Starting strictly from the authoritative {@link DayState}, map, and current traffic, this
 * component decodes and steps through the exact integer arrays produced by the encoder without
 * reading simulation results or planner metadata.</p>
 */
public final class WireActionReplay {

    public record ReplayedCommand(
            int commandIndex,
            int wireValue,
            Position sourcePosition,
            Position destinationPosition,
            Terrain sourceTerrain,
            TrafficStatus sourceTraffic,
            int startStep,
            int duration,
            int endStep) {}

    public record AgentReplayResult(
            AgentId agentId,
            AgentKind agentKind,
            Position startPosition,
            Position finalPosition,
            AgentFuel initialFuel,
            AgentFuel finalFuel,
            int totalDuration,
            List<ReplayedCommand> commands,
            boolean valid,
            String rejectionReason) {}

    public record TeamReplayResult(
            List<AgentReplayResult> agentResults,
            boolean allValid,
            String firstRejection) {

        public AgentReplayResult resultFor(AgentId agentId) {
            return agentResults.stream()
                    .filter(r -> r.agentId().equals(agentId))
                    .findFirst()
                    .orElse(null);
        }
    }

    private WireActionReplay() {}

    public static TeamReplayResult replayTeam(
            DayState state,
            List<List<Integer>> encodedActions,
            int dayBudget) {
        Objects.requireNonNull(state, "DayState must not be null");
        Objects.requireNonNull(encodedActions, "encodedActions must not be null");

        TeamExecution execution = new TeamExecution(state, encodedActions, dayBudget);
        return execution.run();
    }

    private static AgentReplayResult invalidAgentResult(
            RuntimeAgent agent,
            int totalDuration,
            String rejectionReason) {
        return new AgentReplayResult(
                agent.id,
                agent.kind,
                agent.startPosition,
                agent.position,
                agent.initialFuel,
                agent.fuel,
                totalDuration,
                List.copyOf(agent.commands),
                false,
                rejectionReason);
    }

    private static final class TeamExecution {

        private final DayState state;
        private final HexMap map;
        private final int dayBudget;
        private final List<RuntimeAgent> agents = new ArrayList<>();

        private TeamExecution(
                DayState state,
                List<List<Integer>> encodedActions,
                int dayBudget) {
            this.state = state;
            this.map = state.matchData().map();
            this.dayBudget = dayBudget;
            for (int i = 0; i < state.agents().size(); i++) {
                AgentState agent = state.agents().get(i);
                List<Integer> actions = i < encodedActions.size() ? encodedActions.get(i) : List.of();
                agents.add(new RuntimeAgent(agent, actions));
            }
        }

        private TeamReplayResult run() {
            for (int step = 1; step <= dayBudget; step++) {
                int elapsedSteps = step - 1;
                for (RuntimeAgent agent : agents) {
                    String failure = prepareCommand(agent, elapsedSteps);
                    if (failure != null) {
                        return invalidTeam(agent, elapsedSteps, failure);
                    }
                }

                List<RuntimeAgent> arrivals = new ArrayList<>();
                Map<AgentId, AgentActivity> activities = new LinkedHashMap<>();
                for (RuntimeAgent agent : agents) {
                    advanceOneStep(agent, step, arrivals, activities);
                }
                applyRefueling(activities, arrivals);
            }

            for (RuntimeAgent agent : agents) {
                if (agent.commandIndex < agent.encodedActions.size() || agent.operation != null) {
                    return invalidTeam(agent, dayBudget,
                            "Agent has actions remaining after the day step budget");
                }
            }

            List<AgentReplayResult> results = new ArrayList<>(agents.size());
            for (RuntimeAgent agent : agents) {
                results.add(validAgentResult(agent));
            }
            return new TeamReplayResult(List.copyOf(results), true, null);
        }

        private TeamReplayResult invalidTeam(
                RuntimeAgent failedAgent,
                int totalDuration,
                String rejectionReason) {
            List<AgentReplayResult> results = new ArrayList<>(agents.size());
            boolean first = true;
            String firstRejection = null;
            for (RuntimeAgent agent : agents) {
                if (agent == failedAgent && first) {
                    results.add(invalidAgentResult(agent, totalDuration, rejectionReason));
                    firstRejection = "agent " + agents.indexOf(agent) + ": " + rejectionReason;
                    first = false;
                } else {
                    results.add(new AgentReplayResult(
                            agent.id,
                            agent.kind,
                            agent.startPosition,
                            agent.position,
                            agent.initialFuel,
                            agent.fuel,
                            agent.elapsedSteps,
                            List.copyOf(agent.commands),
                            true,
                            null));
                }
            }
            return new TeamReplayResult(List.copyOf(results), false, firstRejection);
        }

        private AgentReplayResult validAgentResult(RuntimeAgent agent) {
            return new AgentReplayResult(
                    agent.id,
                    agent.kind,
                    agent.startPosition,
                    agent.position,
                    agent.initialFuel,
                    agent.fuel,
                    agent.elapsedSteps,
                    List.copyOf(agent.commands),
                    true,
                    null);
        }

        private String prepareCommand(RuntimeAgent agent, int elapsedSteps) {
            if (agent.operation != null) {
                return null;
            }
            if (agent.commandIndex >= agent.encodedActions.size()) {
                return "Agent explicit actions consume " + elapsedSteps
                        + " of " + dayBudget + " required day steps";
            }
            if (elapsedSteps >= dayBudget) {
                return "Command " + agent.commandIndex + " starts at step " + elapsedSteps
                        + " >= dayBudget " + dayBudget + " (surplus command)";
            }

            int wireVal = agent.encodedActions.get(agent.commandIndex);
            if (wireVal < 0) {
                int duration = -wireVal;
                int endStep = elapsedSteps + duration;
                if (endStep > dayBudget) {
                    return "Wait command " + agent.commandIndex + " (duration=" + duration
                            + ") ends at step " + endStep + " > dayBudget " + dayBudget;
                }
                Terrain terrain = map.terrainAt(agent.position);
                TrafficStatus traffic = terrain == Terrain.ROAD
                        ? state.roadTraffic().get(agent.position) : null;
                agent.operation = new WaitOperation(duration);
                agent.commands.add(new ReplayedCommand(
                        agent.commandIndex,
                        wireVal,
                        agent.position,
                        null,
                        terrain,
                        traffic,
                        elapsedSteps,
                        duration,
                        endStep));
                return null;
            }

            Direction dir;
            try {
                dir = Direction.fromCode(wireVal);
            } catch (IllegalArgumentException e) {
                return "Invalid direction code " + wireVal + " at command " + agent.commandIndex;
            }

            Optional<Position> neighbor = map.neighbor(agent.position, dir);
            if (neighbor.isEmpty()) {
                return "Move " + agent.commandIndex + " leaves the map from "
                        + agent.position + " dir " + dir;
            }
            Position dest = neighbor.orElseThrow();
            if (map.terrainAt(dest) == Terrain.POND) {
                return "Move " + agent.commandIndex + " destination is POND: " + dest;
            }

            Terrain sourceTerrain = map.terrainAt(agent.position);
            TrafficStatus sourceTraffic = sourceTerrain == Terrain.ROAD
                    ? state.roadTraffic().get(agent.position) : null;
            if (sourceTerrain == Terrain.ROAD && sourceTraffic == null) {
                return "Missing authoritative traffic at ROAD " + agent.position;
            }

            Optional<MoveCost> moveCostOpt = MovementRules.costFromSource(sourceTerrain, sourceTraffic);
            if (moveCostOpt.isEmpty()) {
                return "Impassable source at " + agent.position;
            }
            MoveCost cost = moveCostOpt.orElseThrow();
            int duration = cost.stepCost();
            int endStep = elapsedSteps + duration;
            if (endStep > dayBudget) {
                return "Move " + agent.commandIndex + " duration " + duration
                        + " ends at step " + endStep + " > dayBudget " + dayBudget;
            }
            if (!FuelRules.canAfford(agent.fuel, cost)) {
                return "Agent cannot afford movement from " + agent.position + " (cost=" + cost + ")";
            }

            agent.fuel = FuelRules.remainingFuelAfterMove(agent.fuel, cost);
            agent.operation = new MoveOperation(agent.position, dest, duration);
            agent.commands.add(new ReplayedCommand(
                    agent.commandIndex,
                    wireVal,
                    agent.position,
                    dest,
                    sourceTerrain,
                    sourceTraffic,
                    elapsedSteps,
                    duration,
                    endStep));
            return null;
        }

        private void advanceOneStep(
                RuntimeAgent agent,
                int step,
                List<RuntimeAgent> arrivals,
                Map<AgentId, AgentActivity> activities) {
            agent.elapsedSteps = step;
            if (agent.operation instanceof WaitOperation wait) {
                wait.remaining--;
                activities.put(agent.id, AgentActivity.WAITING);
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
                arrivals.add(agent);
                completeOperation(agent);
            }
        }

        private void completeOperation(RuntimeAgent agent) {
            agent.operation = null;
            agent.commandIndex++;
        }

        private void applyRefueling(
                Map<AgentId, AgentActivity> activities,
                List<RuntimeAgent> arrivals) {
            Map<Position, List<AgentId>> refuelIdsByPosition = new HashMap<>();
            for (RuntimeAgent agent : agents) {
                if (agent.kind == AgentKind.REFUEL
                        && genuinelyOccupiesEndOfStepCell(agent, activities, arrivals)) {
                    refuelIdsByPosition
                            .computeIfAbsent(agent.position, ignored -> new ArrayList<>())
                            .add(agent.id);
                }
            }
            int capacity = state.matchData().patrolFuelCapacity().value();
            for (RuntimeAgent agent : agents) {
                if (agent.kind != AgentKind.PATROL
                        || !genuinelyOccupiesEndOfStepCell(agent, activities, arrivals)) {
                    continue;
                }
                List<AgentId> refuelIds = refuelIdsByPosition.get(agent.position);
                if (refuelIds == null || !(agent.fuel instanceof FiniteFuel fuel)) {
                    continue;
                }
                if (fuel.amount() < capacity) {
                    agent.fuel = new FiniteFuel(capacity);
                }
            }
        }

        private boolean genuinelyOccupiesEndOfStepCell(
                RuntimeAgent agent,
                Map<AgentId, AgentActivity> activities,
                List<RuntimeAgent> arrivals) {
            return activities.get(agent.id) == AgentActivity.WAITING
                    || arrivals.contains(agent);
        }
    }

    public static AgentReplayResult replayAgent(
            DayState state,
            AgentState agent,
            List<Integer> encodedActions,
            int dayBudget) {
        Objects.requireNonNull(state, "DayState must not be null");
        Objects.requireNonNull(agent, "AgentState must not be null");
        Objects.requireNonNull(encodedActions, "encodedActions must not be null");

        HexMap map = state.matchData().map();
        Position currentPos = agent.position();
        AgentFuel currentFuel = agent.fuel();
        int currentStep = 0;
        List<ReplayedCommand> commands = new ArrayList<>(encodedActions.size());

        for (int idx = 0; idx < encodedActions.size(); idx++) {
            int wireVal = encodedActions.get(idx);
            int startStep = currentStep;

            if (startStep >= dayBudget) {
                return new AgentReplayResult(
                        agent.id(), agent.kind(), agent.position(), currentPos,
                        agent.fuel(), currentFuel, currentStep, List.copyOf(commands), false,
                        "Command " + idx + " (wire=" + wireVal + ") starts at step " + startStep
                                + " >= dayBudget " + dayBudget + " (surplus command)");
            }

            if (wireVal < 0) {
                // WAIT command
                int duration = -wireVal;
                int endStep = startStep + duration;
                if (endStep > dayBudget) {
                    return new AgentReplayResult(
                            agent.id(), agent.kind(), agent.position(), currentPos,
                            agent.fuel(), currentFuel, currentStep, List.copyOf(commands), false,
                            "Wait command " + idx + " (duration=" + duration + ") ends at step "
                                    + endStep + " > dayBudget " + dayBudget);
                }
                Terrain terrain = map.terrainAt(currentPos);
                TrafficStatus traffic = terrain == Terrain.ROAD
                        ? state.roadTraffic().get(currentPos) : null;
                commands.add(new ReplayedCommand(
                        idx, wireVal, currentPos, null, terrain, traffic, startStep, duration, endStep));
                currentStep = endStep;
            } else {
                // MOVE command
                Direction dir;
                try {
                    dir = Direction.fromCode(wireVal);
                } catch (IllegalArgumentException e) {
                    return new AgentReplayResult(
                            agent.id(), agent.kind(), agent.position(), currentPos,
                            agent.fuel(), currentFuel, currentStep, List.copyOf(commands), false,
                            "Invalid direction code " + wireVal + " at command " + idx);
                }

                Optional<Position> neighbor = map.neighbor(currentPos, dir);
                if (neighbor.isEmpty()) {
                    return new AgentReplayResult(
                            agent.id(), agent.kind(), agent.position(), currentPos,
                            agent.fuel(), currentFuel, currentStep, List.copyOf(commands), false,
                            "Move " + idx + " leaves the map from " + currentPos + " dir " + dir);
                }
                Position dest = neighbor.orElseThrow();
                if (map.terrainAt(dest) == Terrain.POND) {
                    return new AgentReplayResult(
                            agent.id(), agent.kind(), agent.position(), currentPos,
                            agent.fuel(), currentFuel, currentStep, List.copyOf(commands), false,
                            "Move " + idx + " destination is POND: " + dest);
                }

                Terrain sourceTerrain = map.terrainAt(currentPos);
                TrafficStatus sourceTraffic = sourceTerrain == Terrain.ROAD
                        ? state.roadTraffic().get(currentPos) : null;
                if (sourceTerrain == Terrain.ROAD && sourceTraffic == null) {
                    return new AgentReplayResult(
                            agent.id(), agent.kind(), agent.position(), currentPos,
                            agent.fuel(), currentFuel, currentStep, List.copyOf(commands), false,
                            "Missing authoritative traffic at ROAD " + currentPos);
                }

                Optional<MoveCost> moveCostOpt = MovementRules.costFromSource(sourceTerrain, sourceTraffic);
                if (moveCostOpt.isEmpty()) {
                    return new AgentReplayResult(
                            agent.id(), agent.kind(), agent.position(), currentPos,
                            agent.fuel(), currentFuel, currentStep, List.copyOf(commands), false,
                            "Impassable source at " + currentPos);
                }
                MoveCost cost = moveCostOpt.orElseThrow();
                int duration = cost.stepCost();
                int endStep = startStep + duration;

                if (endStep > dayBudget) {
                    return new AgentReplayResult(
                            agent.id(), agent.kind(), agent.position(), currentPos,
                            agent.fuel(), currentFuel, currentStep, List.copyOf(commands), false,
                            "Move " + idx + " duration " + duration + " ends at step "
                                    + endStep + " > dayBudget " + dayBudget);
                }

                if (!FuelRules.canAfford(currentFuel, cost)) {
                    return new AgentReplayResult(
                            agent.id(), agent.kind(), agent.position(), currentPos,
                            agent.fuel(), currentFuel, currentStep, List.copyOf(commands), false,
                            "Agent cannot afford movement from " + currentPos + " (cost=" + cost + ")");
                }

                currentFuel = FuelRules.remainingFuelAfterMove(currentFuel, cost);
                commands.add(new ReplayedCommand(
                        idx, wireVal, currentPos, dest, sourceTerrain, sourceTraffic, startStep, duration, endStep));
                currentPos = dest;
                currentStep = endStep;
            }
        }

        return new AgentReplayResult(
                agent.id(), agent.kind(), agent.position(), currentPos,
                agent.fuel(), currentFuel, currentStep, List.copyOf(commands), true, null);
    }

    private static final class RuntimeAgent {

        private final AgentId id;
        private final AgentKind kind;
        private final Position startPosition;
        private final AgentFuel initialFuel;
        private final List<Integer> encodedActions;
        private final List<ReplayedCommand> commands = new ArrayList<>();
        private Position position;
        private AgentFuel fuel;
        private int commandIndex;
        private Operation operation;
        private int elapsedSteps;

        private RuntimeAgent(AgentState initial, List<Integer> encodedActions) {
            this.id = initial.id();
            this.kind = initial.kind();
            this.startPosition = initial.position();
            this.initialFuel = initial.fuel();
            this.position = initial.position();
            this.fuel = initial.fuel();
            this.encodedActions = List.copyOf(encodedActions);
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
