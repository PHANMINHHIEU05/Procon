package vn.ptit.procon.simulation;

import vn.ptit.procon.model.Model;
import vn.ptit.procon.model.Model.AgentKind;
import vn.ptit.procon.model.Model.AgentState;
import vn.ptit.procon.model.Model.DayState;
import vn.ptit.procon.model.Model.MapData;
import vn.ptit.procon.model.Model.Setup;
import vn.ptit.procon.model.Model.Spot;
import vn.ptit.procon.rules.HexRules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ExactSimulator {
    public SimulationResult simulate(Setup setup, DayState state, int[][] actions) {
        if (actions.length != state.agents().size()) throw new IllegalArgumentException("Agent count mismatch");
        MapData map = setup.map();
        var traffic = HexRules.traffic(map.width() * map.height(), state.traffic());
        RuntimeAgent[] agents = new RuntimeAgent[actions.length];
        for (int i = 0; i < agents.length; i++) agents[i] = new RuntimeAgent(state.agents().get(i), actions[i]);
        int[] claims = new int[setup.spots().size()];
        boolean[][] visited = new boolean[actions.length][setup.spots().size()];
        Set<String> brands = new HashSet<>();
        int portions = 0;
        int dayBudget = setup.daySteps()[state.day()];
        for (int step = 1; step <= dayBudget; step++) {
            for (RuntimeAgent agent : agents) prepare(agent, map, traffic, dayBudget, step - 1);
            boolean[] waiting = new boolean[agents.length];
            boolean[] arrivals = new boolean[agents.length];
            for (int i = 0; i < agents.length; i++) {
                RuntimeAgent agent = agents[i];
                agent.elapsed = step;
                if (agent.operation instanceof WaitOperation wait) {
                    waiting[i] = true;
                    if (--wait.remaining == 0) {
                        agent.operation = null;
                        agent.commandIndex++;
                    }
                } else if (agent.operation instanceof MoveOperation move) {
                    if (--move.remaining == 0) {
                        agent.position = move.destination;
                        agent.operation = null;
                        agent.commandIndex++;
                        arrivals[i] = true;
                    }
                }
                if (arrivals[i] && agent.kind == AgentKind.PATROL) {
                    int spotId = spotAt(setup, agent.position);
                    if (spotId >= 0 && !visited[i][spotId] && claims[spotId] < setup.spots().get(spotId).stock()) {
                        Spot spot = setup.spots().get(spotId);
                        visited[i][spotId] = true;
                        claims[spotId]++;
                        portions++;
                        brands.add(spot.brand());
                    }
                }
            }
            refuel(agents, waiting, arrivals, setup.fuelLimit());
        }
        int[] positions = new int[agents.length], fuel = new int[agents.length], elapsed = new int[agents.length];
        for (int i = 0; i < agents.length; i++) {
            RuntimeAgent agent = agents[i];
            if (agent.operation != null || agent.commandIndex < agent.actions.length) {
                throw new IllegalArgumentException("Actions remain after day budget for agent " + i);
            }
            positions[i] = agent.position;
            fuel[i] = agent.fuel;
            elapsed[i] = agent.elapsed;
        }
        return new SimulationResult(positions, fuel, portions, brands, elapsed, claims, visited);
    }

    private void prepare(RuntimeAgent agent, MapData map, Model.Traffic[] traffic, int budget, int elapsed) {
        if (agent.operation != null) return;
        if (agent.commandIndex >= agent.actions.length) {
            agent.operation = new WaitOperation(budget - elapsed);
            return;
        }
        int encoded = agent.actions[agent.commandIndex];
        if (encoded < 0) {
            int wait = Math.negateExact(encoded);
            if (wait <= 0 || elapsed + wait > budget) throw new IllegalArgumentException("Invalid WAIT");
            agent.operation = new WaitOperation(wait);
            return;
        }
        if (encoded > 5) throw new IllegalArgumentException("Invalid direction");
        int next = HexRules.neighbors(agent.position, map).get(encoded);
        if (next < 0 || !map.terrain(next).traversable()) throw new IllegalArgumentException("Invalid move");
        Model.MoveCost cost = HexRules.moveCost(map, agent.position, traffic);
        if (cost == null || elapsed + cost.steps() > budget) throw new IllegalArgumentException("Invalid movement timing");
        if (agent.kind == AgentKind.PATROL) {
            if (agent.fuel < cost.fuel()) throw new IllegalArgumentException("Fuel overflow");
            agent.fuel -= cost.fuel();
        }
        agent.operation = new MoveOperation(next, cost.steps());
    }

    private void refuel(RuntimeAgent[] agents, boolean[] waiting, boolean[] arrivals, int fuelLimit) {
        boolean[] eligibleRefuel = new boolean[agents.length];
        for (int i = 0; i < agents.length; i++) {
            eligibleRefuel[i] = agents[i].kind == AgentKind.REFUEL && (waiting[i] || arrivals[i]);
        }
        for (int i = 0; i < agents.length; i++) {
            if (agents[i].kind != AgentKind.PATROL || !(waiting[i] || arrivals[i])) continue;
            for (int j = 0; j < agents.length; j++) {
                if (eligibleRefuel[j] && agents[i].position == agents[j].position) {
                    agents[i].fuel = fuelLimit;
                    break;
                }
            }
        }
    }

    private int spotAt(Setup setup, int position) {
        for (int i = 0; i < setup.spots().size(); i++) if (setup.spots().get(i).position() == position) return i;
        return -1;
    }

    private static final class RuntimeAgent {
        private final AgentKind kind;
        private final int[] actions;
        private int commandIndex;
        private int position;
        private int fuel;
        private int elapsed;
        private Operation operation;

        private RuntimeAgent(AgentState initial, int[] actions) {
            this.kind = initial.kind();
            this.position = initial.position();
            this.fuel = initial.fuel();
            this.actions = actions == null ? new int[0] : actions.clone();
        }
    }

    private sealed interface Operation permits MoveOperation, WaitOperation {}
    private static final class MoveOperation implements Operation {
        private final int destination;
        private int remaining;
        private MoveOperation(int destination, int remaining) { this.destination = destination; this.remaining = remaining; }
    }
    private static final class WaitOperation implements Operation {
        private int remaining;
        private WaitOperation(int remaining) { this.remaining = remaining; }
    }

    public record SimulationResult(int[] positions, int[] fuel, int portions,
                                   Set<String> brands, int[] elapsed, int[] claimsBySpot,
                                   boolean[][] visitedSpots) {
        public SimulationResult {
            positions = positions.clone();
            fuel = fuel.clone();
            elapsed = elapsed.clone();
            claimsBySpot = claimsBySpot.clone();
            visitedSpots = java.util.Arrays.stream(visitedSpots).map(boolean[]::clone).toArray(boolean[][]::new);
            brands = Set.copyOf(brands);
        }
    }
}
