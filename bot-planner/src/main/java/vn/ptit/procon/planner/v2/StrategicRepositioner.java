package vn.ptit.procon.planner.v2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.planner.WeightedRouteFinder;

/**
 * Disperses clumped/idle patrols and forward-positions tankers during leftover end-of-day wait steps.
 *
 * <p>Only executes on non-final days. Every candidate reposition is verified via {@link PlanValidator}
 * and {@link DaySimulator} to ensure 100% collision-free validity and zero loss in day collections.
 */
public final class StrategicRepositioner {

    private StrategicRepositioner() { }

    public static TeamPlan reposition(DayState state, TeamPlan initialPlan) {
        boolean enabled = Boolean.parseBoolean(System.getProperty("procon.repositioning",
                System.getenv("PROCON_REPOSITIONING") != null ? System.getenv("PROCON_REPOSITIONING") : "true"));
        if (!enabled) return initialPlan;

        if (state.day().value() >= state.matchData().dayStepBudgets().dayCount() - 1) {
            return initialPlan;
        }

        DaySimulator simulator = new DaySimulator();
        DaySimulationResult sim = simulator.simulate(state, initialPlan);
        if (!(sim instanceof ValidDaySimulationResult valid)) {
            return initialPlan;
        }

        Map<AgentId, Integer> originalCollections = valid.portionsCollectedByAgent();
        Map<AgentId, AgentState> finalAgents = new LinkedHashMap<>();
        for (AgentState a : valid.finalAgents()) {
            finalAgents.put(a.id(), a);
        }

        Map<AgentId, List<AgentAction>> currentActions = new LinkedHashMap<>(initialPlan.actionsByAgent());
        WeightedRouteFinder router = new WeightedRouteFinder();
        vn.ptit.procon.planner.RefuelRouteFinder refuelRouter = new vn.ptit.procon.planner.RefuelRouteFinder();
        PlanValidator validator = new PlanValidator();

        Set<Position> assignedPositions = new LinkedHashSet<>();
        // Detect clumping: find which positions are occupied by more than 1 agent
        Map<Position, List<AgentId>> agentsByPosition = new LinkedHashMap<>();
        for (AgentState a : finalAgents.values()) {
            agentsByPosition.computeIfAbsent(a.position(), k -> new ArrayList<>()).add(a.id());
        }

        // For positions with only 1 agent, reserve them
        for (Map.Entry<Position, List<AgentId>> entry : agentsByPosition.entrySet()) {
            if (entry.getValue().size() == 1) {
                assignedPositions.add(entry.getKey());
            }
        }

        // Priority candidate list: clumped agents first, then other patrols, then refueler LAST
        List<AgentId> candidateOrder = new ArrayList<>();
        // 1. Clumped agents beyond the first one at each clumped cell
        for (Map.Entry<Position, List<AgentId>> entry : agentsByPosition.entrySet()) {
            if (entry.getValue().size() > 1) {
                // First agent keeps the position
                assignedPositions.add(entry.getKey());
                for (int i = 1; i < entry.getValue().size(); i++) {
                    candidateOrder.add(entry.getValue().get(i));
                }
            }
        }
        // 2. Other patrols (reposition towards active udon spots)
        for (AgentState a : finalAgents.values()) {
            if (a.kind() == AgentKind.PATROL && !candidateOrder.contains(a.id())) {
                candidateOrder.add(a.id());
            }
        }
        // 3. Refueler LAST (so it can see final patrol positions and their remaining fuel)
        for (AgentState a : finalAgents.values()) {
            if (a.kind() == AgentKind.REFUEL && !candidateOrder.contains(a.id())) {
                candidateOrder.add(a.id());
            }
        }

        Map<Position, Integer> remainingStock = valid.remainingSpotStock();
        List<UdonSpot> targetSpots = new ArrayList<>(state.matchData().udonSpots());
        targetSpots.sort(
                Comparator.<UdonSpot>comparingInt(s -> remainingStock.getOrDefault(s.position(), 0) > 0 ? 1 : 0).reversed()
                        .thenComparing(Comparator.comparingInt(UdonSpot::stockCapacity).reversed())
                        .thenComparingInt(s -> s.position().value()));

        int patrolFuelCapacity = state.matchData().patrolFuelCapacity().value();
        int safeReserve = Math.min(35, patrolFuelCapacity / 3);

        for (AgentId agentId : candidateOrder) {
            AgentState currentAgent = finalAgents.get(agentId);
            if (currentAgent == null) continue;

            List<AgentAction> actions = currentActions.get(agentId);
            if (actions == null || actions.isEmpty()) continue;
            AgentAction lastAction = actions.get(actions.size() - 1);
            if (!(lastAction instanceof WaitAction wait)) continue;
            int trailingWait = wait.steps();
            if (trailingWait < 1) continue;

            Position bestTarget = null;
            List<AgentAction> bestActions = null;
            int bestSteps = 0;
            int bestScore = Integer.MIN_VALUE;

            if (currentAgent.kind() == AgentKind.REFUEL) {
                List<AgentState> patrols = finalAgents.values().stream()
                        .filter(a -> a.kind() == AgentKind.PATROL)
                        .toList();
                for (AgentState patrol : patrols) {
                    Position targetPos = patrol.position();
                    int fuelAmount = patrol.fuel() instanceof FiniteFuel finite ? finite.amount() : patrolFuelCapacity;
                    int fuelDeficit = Math.max(0, patrolFuelCapacity - fuelAmount);

                    if (targetPos.equals(currentAgent.position())) {
                        continue;
                    }

                    Optional<Route> routeOpt = refuelRouter.find(state, currentAgent, targetPos);
                    if (routeOpt.isEmpty()) continue;
                    Route route = routeOpt.get();

                    List<AgentAction> candidateMoves;
                    int candidateSteps;
                    Position candidateEnd;

                    if (route.stepsUsed() <= trailingWait) {
                        candidateMoves = new ArrayList<>(route.toMoveActions());
                        candidateSteps = route.stepsUsed();
                        candidateEnd = targetPos;
                    } else {
                        Position p = currentAgent.position();
                        int accSteps = 0;
                        List<AgentAction> prefix = new ArrayList<>();
                        for (vn.ptit.procon.domain.map.Direction dir : route.directions()) {
                            vn.ptit.procon.domain.traffic.TrafficStatus traffic = state.matchData().map().terrainAt(p) == vn.ptit.procon.domain.map.Terrain.ROAD
                                    ? state.roadTraffic().get(p) : null;
                            Optional<vn.ptit.procon.domain.movement.MoveCost> costOpt =
                                    vn.ptit.procon.rules.MovementRules.costFromSource(state.matchData().map(), p, traffic);
                            if (costOpt.isEmpty()) break;
                            vn.ptit.procon.domain.movement.MoveCost cost = costOpt.get();
                            if (accSteps + cost.stepCost() > trailingWait) break;
                            Optional<Position> nextP = state.matchData().map().neighbor(p, dir);
                            if (nextP.isEmpty()) break;
                            accSteps += cost.stepCost();
                            prefix.add(new vn.ptit.procon.domain.action.MoveAction(dir));
                            p = nextP.get();
                        }
                        if (prefix.isEmpty()) continue;
                        candidateMoves = prefix;
                        candidateSteps = accSteps;
                        candidateEnd = p;
                    }

                    if (candidateSteps == 0 || candidateEnd.equals(currentAgent.position())) continue;
                    if (!candidateEnd.equals(targetPos) && assignedPositions.contains(candidateEnd)) continue;

                    int score;
                    if (candidateEnd.equals(targetPos)) {
                        score = 10_000 + fuelDeficit * 100 - candidateSteps * 2;
                    } else {
                        int progress = (int) ((double) candidateSteps / Math.max(1, route.stepsUsed()) * fuelDeficit * 50);
                        int remainingSteps = route.stepsUsed() - candidateSteps;
                        score = 1_000 + progress - remainingSteps * 5;
                    }

                    if (score > bestScore) {
                        bestScore = score;
                        bestTarget = candidateEnd;
                        bestActions = candidateMoves;
                        bestSteps = candidateSteps;
                    }
                }
            } else {
                for (UdonSpot spot : targetSpots) {
                    if (assignedPositions.contains(spot.position()) && !spot.position().equals(currentAgent.position())) {
                        continue;
                    }

                    Optional<Route> routeOpt = router.find(state, currentAgent, spot.position());
                    if (routeOpt.isEmpty()) continue;
                    Route route = routeOpt.get();

                    List<AgentAction> candidateMoves;
                    int candidateSteps;
                    int candidateFuel;
                    Position candidateEnd;

                    if (route.stepsUsed() <= trailingWait) {
                        candidateMoves = new ArrayList<>(route.toMoveActions());
                        candidateSteps = route.stepsUsed();
                        candidateFuel = route.fuelUsed();
                        candidateEnd = spot.position();
                    } else {
                        Position p = currentAgent.position();
                        int accSteps = 0;
                        int accFuel = 0;
                        List<AgentAction> prefix = new ArrayList<>();
                        for (vn.ptit.procon.domain.map.Direction dir : route.directions()) {
                            vn.ptit.procon.domain.traffic.TrafficStatus traffic = state.matchData().map().terrainAt(p) == vn.ptit.procon.domain.map.Terrain.ROAD
                                    ? state.roadTraffic().get(p) : null;
                            Optional<vn.ptit.procon.domain.movement.MoveCost> costOpt =
                                    vn.ptit.procon.rules.MovementRules.costFromSource(state.matchData().map(), p, traffic);
                            if (costOpt.isEmpty()) break;
                            vn.ptit.procon.domain.movement.MoveCost cost = costOpt.get();
                            if (accSteps + cost.stepCost() > trailingWait) break;
                            Optional<Position> nextP = state.matchData().map().neighbor(p, dir);
                            if (nextP.isEmpty()) break;
                            accSteps += cost.stepCost();
                            accFuel += cost.patrolFuelCost();
                            prefix.add(new vn.ptit.procon.domain.action.MoveAction(dir));
                            p = nextP.get();
                        }
                        if (prefix.isEmpty()) continue;
                        candidateMoves = prefix;
                        candidateSteps = accSteps;
                        candidateFuel = accFuel;
                        candidateEnd = p;
                    }

                    if (candidateSteps == 0 || candidateEnd.equals(currentAgent.position())) continue;
                    if (assignedPositions.contains(candidateEnd)) continue;

                    FiniteFuel fuel = (FiniteFuel) currentAgent.fuel();
                    if (fuel.amount() - candidateFuel < safeReserve) continue;

                    int spotRemaining = remainingStock.getOrDefault(spot.position(), 0);
                    int score = (candidateEnd.equals(spot.position()) ? 1000 : 0)
                            + spotRemaining * 20 + spot.stockCapacity() * 10 - candidateSteps;
                    if (score > bestScore) {
                        bestScore = score;
                        bestTarget = candidateEnd;
                        bestActions = candidateMoves;
                        bestSteps = candidateSteps;
                    }
                }
            }

            if (bestActions != null && bestTarget != null) {
                List<AgentAction> modified = new ArrayList<>(actions);
                modified.remove(modified.size() - 1);
                modified.addAll(bestActions);
                int remWait = trailingWait - bestSteps;
                if (remWait > 0) {
                    modified.add(new WaitAction(remWait));
                }

                Map<AgentId, List<AgentAction>> candidateActions = new LinkedHashMap<>(currentActions);
                candidateActions.put(agentId, List.copyOf(modified));
                TeamPlan candidatePlan = new TeamPlan(candidateActions);

                if (validator.validate(state, candidatePlan).valid()) {
                    DaySimulationResult testSim = simulator.simulate(state, candidatePlan);
                    if (testSim instanceof ValidDaySimulationResult testValid) {
                        if (testValid.portionsCollectedByAgent().equals(originalCollections)) {
                            currentActions = candidateActions;
                            finalAgents.clear();
                            for (AgentState a : testValid.finalAgents()) {
                                finalAgents.put(a.id(), a);
                            }
                            if (currentAgent.kind() != AgentKind.REFUEL) {
                                assignedPositions.add(bestTarget);
                            }
                            System.out.println("STRATEGIC_REPOSITION day=" + state.day().value() + " agent=" + agentId.value()
                                    + " (" + currentAgent.kind() + ") from=" + currentAgent.position().value()
                                    + " to=" + bestTarget.value() + " steps=" + bestSteps + " remWait=" + remWait);
                        }
                    }
                }
            }
        }

        return new TeamPlan(currentActions);
    }
}
