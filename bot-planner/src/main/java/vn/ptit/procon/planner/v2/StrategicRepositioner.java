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
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentFuel;
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
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.NextDayHarvestCapacityCalculator;
import vn.ptit.procon.planner.RefuelRouteFinder;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.planner.TeamNextDayHarvestCapacity;
import vn.ptit.procon.planner.WeightedRouteFinder;
import vn.ptit.procon.rules.FuelRules;
import vn.ptit.procon.rules.MovementRules;

/**
 * End-of-day strategic repositioning for PATROL and REFUEL agents during trailing wait steps.
 *
 * <p>Phase 1: Disperses clumped/idle PATROL agents to active next-day staging positions.
 * <p>Phase 2: Forward-repositions the REFUEL tanker toward fuel-starved patrols for instant
 * tank refilling or next-day rendezvous proximity.
 *
 * <p>Every candidate reposition is verified via {@link PlanValidator} and {@link DaySimulator}
 * to ensure 100% legal validity, 100% preservation of today's collections, and non-regressive
 * next-day harvest capacity.
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

        long startNanos = System.nanoTime();
        int candidatesGenerated = 0;
        int routeCalls = 0;
        int candidatesValidated = 0;
        int candidatesSimulated = 0;
        long evalMillis = 0;

        DaySimulator simulator = new DaySimulator();
        DaySimulationResult sim = simulator.simulate(state, initialPlan);
        if (!(sim instanceof ValidDaySimulationResult valid)) {
            return initialPlan;
        }

        Map<AgentId, Integer> originalCollections = valid.portionsCollectedByAgent();
        int originalTotalPortions = originalCollections.values().stream().mapToInt(Integer::intValue).sum();
        Set<BrandId> originalBrands = valid.brandsCollected();
        Map<Position, Integer> remainingStock = valid.remainingSpotStock();
        Map<AgentId, AgentState> finalAgents = new LinkedHashMap<>();
        for (AgentState a : valid.finalAgents()) {
            finalAgents.put(a.id(), a);
        }

        NextDayHarvestCapacityCalculator nextDayCalc = NextDayHarvestCapacityCalculator.forState(state);
        TeamNextDayHarvestCapacity currentTeamCapacity = nextDayCalc.evaluate(valid);

        Map<AgentId, List<AgentAction>> currentActions = new LinkedHashMap<>(initialPlan.actionsByAgent());
        WeightedRouteFinder router = new WeightedRouteFinder();
        PlanValidator validator = new PlanValidator();

        Set<Position> spotPositions = new LinkedHashSet<>();
        for (UdonSpot s : state.matchData().udonSpots()) {
            spotPositions.add(s.position());
        }

        // ==========================================
        // PHASE 1: PATROL END-OF-DAY REPOSITIONING
        // ==========================================

        // 1. Map agents by terminal position to detect clumping
        Map<Position, List<AgentState>> patrolsByPosition = new LinkedHashMap<>();
        for (AgentState a : finalAgents.values()) {
            if (a.kind() == AgentKind.PATROL) {
                patrolsByPosition.computeIfAbsent(a.position(), k -> new ArrayList<>()).add(a);
            }
        }

        // Active spots: sort by stockCapacity descending so highest-yield spots are prioritized
        List<UdonSpot> activeSpots = new ArrayList<>(state.matchData().udonSpots());
        activeSpots.sort(
                Comparator.comparingInt(UdonSpot::stockCapacity).reversed()
                        .thenComparingInt(s -> s.position().value()));

        Set<Position> assignedPositions = new LinkedHashSet<>();
        // Tanker / Refueler terminal positions are reserved to avoid conflicts during patrol moves
        for (AgentState a : finalAgents.values()) {
            if (a.kind() == AgentKind.REFUEL) {
                assignedPositions.add(a.position());
            }
        }

        // 2. Identify non-moving patrols vs candidate patrols
        List<AgentState> repositionCandidates = new ArrayList<>();
        Set<Position> stagedSpots = new LinkedHashSet<>();

        final Map<AgentId, List<AgentAction>> baseActions = currentActions;
        for (Map.Entry<Position, List<AgentState>> entry : patrolsByPosition.entrySet()) {
            Position pos = entry.getKey();
            List<AgentState> agentsAtPos = entry.getValue();
            boolean isSpot = spotPositions.contains(pos);

            if (isSpot) {
                // Keep the agent with LEAST trailing wait on this spot,
                // freeing the agent with MORE trailing wait to reposition to other spots!
                List<AgentState> sortedClumped = new ArrayList<>(agentsAtPos);
                sortedClumped.sort(Comparator.comparingInt(a -> getTrailingWait(baseActions.get(a.id()))));
                AgentState keeper = sortedClumped.get(0);
                assignedPositions.add(pos);
                stagedSpots.add(pos);

                // If clumped, remaining agents at this spot become reposition candidates to other spots!
                for (int i = 1; i < sortedClumped.size(); i++) {
                    AgentState clumped = sortedClumped.get(i);
                    int clumpedWait = getTrailingWait(currentActions.get(clumped.id()));
                    if (clumpedWait >= 1) {
                        repositionCandidates.add(clumped);
                    } else {
                        assignedPositions.add(pos);
                    }
                }
            } else {
                if (agentsAtPos.size() == 1) {
                    AgentState agent = agentsAtPos.get(0);
                    List<AgentAction> actions = currentActions.get(agent.id());
                    int trailingWait = getTrailingWait(actions);
                    if (trailingWait >= 1) {
                        repositionCandidates.add(agent);
                    } else {
                        assignedPositions.add(pos);
                    }
                } else {
                    List<AgentState> sortedClumped = new ArrayList<>(agentsAtPos);
                    sortedClumped.sort(Comparator.comparingInt(a -> getTrailingWait(baseActions.get(a.id()))));
                    AgentState keeper = sortedClumped.get(0);
                    assignedPositions.add(pos);
                    for (int i = 1; i < sortedClumped.size(); i++) {
                        AgentState clumped = sortedClumped.get(i);
                        int trailingWait = getTrailingWait(currentActions.get(clumped.id()));
                        if (trailingWait >= 1) {
                            repositionCandidates.add(clumped);
                        }
                    }
                }
            }
        }

        // Available target spots: active spots not yet reserved or staged
        List<UdonSpot> candidateSpots = activeSpots.stream()
                .filter(s -> !assignedPositions.contains(s.position()) && !stagedSpots.contains(s.position()))
                .toList();

        // 3. For each candidate patrol, find best legal repositioning toward distinct staging targets
        Set<Position> targetedSpots = new LinkedHashSet<>();

        for (AgentState currentAgent : repositionCandidates) {
            AgentId agentId = currentAgent.id();
            List<AgentAction> actions = currentActions.get(agentId);
            int trailingWait = getTrailingWait(actions);
            if (trailingWait < 1) continue;

            Position bestTarget = null;
            Position bestTargetSpot = null;
            List<AgentAction> bestActions = null;
            int bestSteps = 0;
            TeamPlan bestCandidatePlan = null;
            TeamNextDayHarvestCapacity bestCapacity = currentTeamCapacity;
            ValidDaySimulationResult bestValid = null;
            int bestPortions = originalTotalPortions;
            int bestArrivalSteps = Integer.MAX_VALUE;

            // Route to available candidate spots
            for (UdonSpot spot : candidateSpots) {
                if (targetedSpots.contains(spot.position())) continue;

                routeCalls++;
                Optional<Route> routeOpt = router.find(state, currentAgent, spot.position());
                if (routeOpt.isEmpty()) continue;
                Route route = routeOpt.get();

                List<AgentAction> candidateMoves = new ArrayList<>();
                int accSteps = 0;
                Position p = currentAgent.position();
                AgentFuel currentFuel = currentAgent.fuel();
                boolean routeViable = true;

                for (Direction dir : route.directions()) {
                    TrafficStatus traffic = state.matchData().map().terrainAt(p) == vn.ptit.procon.domain.map.Terrain.ROAD
                            ? state.roadTraffic().getOrDefault(p, TrafficStatus.CLEAR) : null;
                    Optional<MoveCost> costOpt = MovementRules.costFromSource(state.matchData().map(), p, traffic);
                    if (costOpt.isEmpty()) {
                        routeViable = false;
                        break;
                    }
                    MoveCost cost = costOpt.get();
                    if (accSteps + cost.stepCost() > trailingWait) break;
                    if (!FuelRules.canAfford(currentFuel, cost)) break;

                    Optional<Position> nextP = state.matchData().map().neighbor(p, dir);
                    if (nextP.isEmpty()) {
                        routeViable = false;
                        break;
                    }

                    accSteps += cost.stepCost();
                    currentFuel = FuelRules.remainingFuelAfterMove(currentFuel, cost);
                    candidateMoves.add(new MoveAction(dir));
                    p = nextP.get();
                }

                if (!routeViable || candidateMoves.isEmpty() || p.equals(currentAgent.position())) {
                    continue;
                }
                // Never leave an udon spot to strand on empty grass: only leave if reaching another spot!
                // Exception: if this spot is already occupied by a keeper agent (clumped), the redundant agent
                // SHOULD advance along the route toward an unvisited spot rather than remaining stacked.
                boolean isClumpedAtCurrent = assignedPositions.contains(currentAgent.position());
                if (spotPositions.contains(currentAgent.position()) && !isClumpedAtCurrent && !p.equals(spot.position())) {
                    continue;
                }
                if (assignedPositions.contains(p)) {
                    continue;
                }

                candidatesGenerated++;

                // Build transformed plan
                List<AgentAction> modified = new ArrayList<>(actions);
                modified.remove(modified.size() - 1); // remove trailing wait
                modified.addAll(candidateMoves);
                int remWait = trailingWait - accSteps;
                if (remWait > 0) {
                    modified.add(new WaitAction(remWait));
                }

                Map<AgentId, List<AgentAction>> candidateActions = new LinkedHashMap<>(currentActions);
                candidateActions.put(agentId, List.copyOf(modified));
                TeamPlan candidatePlan = new TeamPlan(candidateActions);

                candidatesValidated++;
                if (!validator.validate(state, candidatePlan).valid()) {
                    continue;
                }

                long evalStart = System.nanoTime();
                candidatesSimulated++;
                DaySimulationResult testSim = simulator.simulate(state, candidatePlan);
                evalMillis += (System.nanoTime() - evalStart) / 1_000_000;

                if (!(testSim instanceof ValidDaySimulationResult testValid)) {
                    continue;
                }

                // Step 7: TODAY PARITY GATE — collections and brands must not regress
                int candidateTotalPortions = testValid.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum();
                if (candidateTotalPortions < originalTotalPortions) {
                    continue;
                }
                if (!testValid.brandsCollected().containsAll(originalBrands)) {
                    continue;
                }

                // Step 9: Re-evaluate next-day capacity
                long evalCapStart = System.nanoTime();
                TeamNextDayHarvestCapacity candidateCapacity = nextDayCalc.evaluate(testValid);
                evalMillis += (System.nanoTime() - evalCapStart) / 1_000_000;

                // Minimum brand capacity must not regress
                if (candidateCapacity.minimumPatrolDistinctBrands() < currentTeamCapacity.minimumPatrolDistinctBrands()) {
                    continue;
                }

                if (candidateTotalPortions < bestPortions) {
                    continue;
                }

                boolean strictlyMorePortions = candidateTotalPortions > bestPortions;
                int remStepsToSpot = route.stepsUsed() - accSteps;
                boolean campsSpot = p.equals(spot.position());
                boolean bestCampsSpot = bestTargetSpot != null && bestTarget != null && bestTarget.equals(bestTargetSpot);

                // Prioritize:
                // 1. More portions today
                // 2. Direct camping on a high-capacity spot (remSteps == 0) over intermediate staging
                // 3. Shorter remaining steps to candidate spot
                if (bestActions == null
                        || strictlyMorePortions
                        || (campsSpot && !bestCampsSpot)
                        || (campsSpot == bestCampsSpot && remStepsToSpot < bestArrivalSteps)) {
                    bestActions = candidateMoves;
                    bestTarget = p;
                    bestTargetSpot = spot.position();
                    bestSteps = accSteps;
                    bestCandidatePlan = candidatePlan;
                    bestCapacity = candidateCapacity;
                    bestValid = testValid;
                    bestPortions = candidateTotalPortions;
                    bestArrivalSteps = remStepsToSpot;
                }
            }

            if (bestCandidatePlan != null && bestTarget != null && bestValid != null) {
                currentActions = new LinkedHashMap<>(bestCandidatePlan.actionsByAgent());
                currentTeamCapacity = bestCapacity;
                originalTotalPortions = bestPortions;
                originalCollections = bestValid.portionsCollectedByAgent();
                remainingStock = bestValid.remainingSpotStock();
                assignedPositions.add(bestTarget);
                if (bestTargetSpot != null) {
                    targetedSpots.add(bestTargetSpot);
                }
                System.out.printf("STRATEGIC_PATROL_REPOSITION day=%d agent=%d from=%d to=%d steps=%d remWait=%d arrivalRemSteps=%d portions=%d%n",
                        state.day().value(), agentId.value(), currentAgent.position().value(),
                        bestTarget.value(), bestSteps, trailingWait - bestSteps, bestArrivalSteps, bestPortions);
            }
        }

        // ==========================================
        // PHASE 2: REFUEL END-OF-DAY REPOSITIONING
        // ==========================================
        AgentState refuelInitial = null;
        for (AgentState a : state.agents()) {
            if (a.kind() == AgentKind.REFUEL) {
                refuelInitial = a;
                break;
            }
        }

        if (refuelInitial != null) {
            // Simulate current post-patrol plan to observe final patrol states
            DaySimulationResult currentSim = simulator.simulate(state, new TeamPlan(currentActions));
            if (currentSim instanceof ValidDaySimulationResult currentValid) {
                AgentState finalRefuel = null;
                List<AgentState> finalPatrols = new ArrayList<>();
                for (AgentState a : currentValid.finalAgents()) {
                    if (a.kind() == AgentKind.REFUEL) {
                        finalRefuel = a;
                    } else if (a.kind() == AgentKind.PATROL) {
                        finalPatrols.add(a);
                    }
                }

                if (finalRefuel != null) {
                    List<AgentAction> refuelActions = currentActions.get(finalRefuel.id());
                    int refuelTrailingWait = getTrailingWait(refuelActions);

                    if (refuelTrailingWait >= 1 && !finalPatrols.isEmpty()) {
                        RefuelRouteFinder refuelRouter = new RefuelRouteFinder();
                        int patrolCapacity = state.matchData().patrolFuelCapacity().value();

                        AgentId bestTargetPatrolId = null;
                        Position bestRefuelTarget = null;
                        List<AgentAction> bestRefuelActions = null;
                        int bestRefuelSteps = 0;
                        int bestScore = Integer.MIN_VALUE;
                        TeamPlan bestRefuelPlan = null;
                        TeamNextDayHarvestCapacity bestRefuelCapacity = currentTeamCapacity;

                        for (AgentState patrol : finalPatrols) {
                            int patrolFuel = ((FiniteFuel) patrol.fuel()).amount();
                            int fuelDeficit = patrolCapacity - patrolFuel;
                            if (fuelDeficit <= 0) continue;

                            if (patrol.position().equals(finalRefuel.position())) continue;

                            routeCalls++;
                            Optional<Route> routeOpt = refuelRouter.find(state, finalRefuel, patrol.position());
                            if (routeOpt.isEmpty()) continue;
                            Route route = routeOpt.get();

                            List<AgentAction> candidateMoves = new ArrayList<>();
                            int accSteps = 0;
                            Position p = finalRefuel.position();
                            boolean routeViable = true;

                            for (Direction dir : route.directions()) {
                                TrafficStatus traffic = state.matchData().map().terrainAt(p) == vn.ptit.procon.domain.map.Terrain.ROAD
                                        ? state.roadTraffic().getOrDefault(p, TrafficStatus.CLEAR) : null;
                                Optional<MoveCost> costOpt = MovementRules.costFromSource(state.matchData().map(), p, traffic);
                                if (costOpt.isEmpty()) {
                                    routeViable = false;
                                    break;
                                }
                                MoveCost cost = costOpt.get();
                                if (accSteps + cost.stepCost() > refuelTrailingWait) break;

                                Optional<Position> nextP = state.matchData().map().neighbor(p, dir);
                                if (nextP.isEmpty()) {
                                    routeViable = false;
                                    break;
                                }

                                accSteps += cost.stepCost();
                                candidateMoves.add(new MoveAction(dir));
                                p = nextP.get();
                            }

                            if (!routeViable || candidateMoves.isEmpty() || p.equals(finalRefuel.position())) {
                                continue;
                            }

                            candidatesGenerated++;

                            // Build transformed plan for refueler
                            List<AgentAction> modified = new ArrayList<>(refuelActions);
                            modified.remove(modified.size() - 1); // remove trailing wait
                            modified.addAll(candidateMoves);
                            int remWait = refuelTrailingWait - accSteps;
                            if (remWait > 0) {
                                modified.add(new WaitAction(remWait));
                            }

                            Map<AgentId, List<AgentAction>> candidateActions = new LinkedHashMap<>(currentActions);
                            candidateActions.put(finalRefuel.id(), List.copyOf(modified));
                            TeamPlan candidatePlan = new TeamPlan(candidateActions);

                            candidatesValidated++;
                            if (!validator.validate(state, candidatePlan).valid()) {
                                continue;
                            }

                            long evalStart = System.nanoTime();
                            candidatesSimulated++;
                            DaySimulationResult testSim = simulator.simulate(state, candidatePlan);
                            evalMillis += (System.nanoTime() - evalStart) / 1_000_000;

                            if (!(testSim instanceof ValidDaySimulationResult testValid)) {
                                continue;
                            }

                            // Parity Gate: collections and stock must not regress
                            if (!testValid.portionsCollectedByAgent().equals(originalCollections)) {
                                continue;
                            }
                            if (!testValid.remainingSpotStock().equals(remainingStock)) {
                                continue;
                            }

                            // Evaluate next-day capacity
                            long evalCapStart = System.nanoTime();
                            TeamNextDayHarvestCapacity candidateCapacity = nextDayCalc.evaluate(testValid);
                            evalMillis += (System.nanoTime() - evalCapStart) / 1_000_000;

                            if (bestRefuelCapacity.betterStructuralCapacityThan(candidateCapacity)) {
                                continue;
                            }

                            boolean reachesPatrol = p.equals(patrol.position());
                            int score;
                            if (reachesPatrol) {
                                score = 100_000 + fuelDeficit * 100 - accSteps * 2;
                            } else {
                                int remSteps = route.stepsUsed() - accSteps;
                                score = 10_000 + fuelDeficit * 50 - remSteps * 5;
                            }

                            if (score > bestScore) {
                                bestScore = score;
                                bestTargetPatrolId = patrol.id();
                                bestRefuelTarget = p;
                                bestRefuelActions = candidateMoves;
                                bestRefuelSteps = accSteps;
                                bestRefuelPlan = candidatePlan;
                                bestRefuelCapacity = candidateCapacity;
                            }
                        }

                        if (bestRefuelPlan != null && bestRefuelTarget != null) {
                            currentActions = new LinkedHashMap<>(bestRefuelPlan.actionsByAgent());
                            System.out.printf("STRATEGIC_REFUEL_REPOSITION day=%d refuelAgent=%d targetPatrol=%d from=%d to=%d steps=%d remWait=%d score=%d%n",
                                    state.day().value(), finalRefuel.id().value(), bestTargetPatrolId.value(),
                                    finalRefuel.position().value(), bestRefuelTarget.value(),
                                    bestRefuelSteps, refuelTrailingWait - bestRefuelSteps, bestScore);
                        }
                    }
                }
            }
        }

        long totalMillis = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.printf("REPOSITION_METRICS day=%d candidatesGenerated=%d routeCalls=%d validated=%d simulated=%d evalMillis=%d totalMillis=%d%n",
                state.day().value(), candidatesGenerated, routeCalls, candidatesValidated,
                candidatesSimulated, evalMillis, totalMillis);

        return new TeamPlan(currentActions);
    }

    private static int getTrailingWait(List<AgentAction> actions) {
        if (actions == null || actions.isEmpty()) return 0;
        AgentAction last = actions.get(actions.size() - 1);
        return last instanceof WaitAction wait ? wait.steps() : 0;
    }

    private record PositionAndMove(Position position, Direction direction, MoveCost cost) {}

    private static PositionAndMove findBestAdjacentUnpark(
            DayState state, Position spotPos, AgentState agent, int trailingWait,
            Set<Position> spotPositions, Set<Position> assignedPositions) {
        Position bestAdj = null;
        Direction bestDir = null;
        MoveCost bestCost = null;
        int bestReturnSteps = Integer.MAX_VALUE;
        int bestReturnFuel = Integer.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            Optional<Position> neighborOpt = state.matchData().map().neighbor(spotPos, dir);
            if (neighborOpt.isEmpty()) continue;
            Position adj = neighborOpt.get();
            if (!state.matchData().map().isTraversable(adj)) continue;
            if (spotPositions.contains(adj)) continue;
            if (assignedPositions.contains(adj)) continue;

            TrafficStatus traffic = state.matchData().map().terrainAt(spotPos) == Terrain.ROAD
                    ? state.roadTraffic().getOrDefault(spotPos, TrafficStatus.CLEAR) : null;
            Optional<MoveCost> costOpt = MovementRules.costFromSource(state.matchData().map(), spotPos, traffic);
            if (costOpt.isEmpty()) continue;
            MoveCost cost = costOpt.get();
            if (cost.stepCost() > trailingWait) continue;
            if (!FuelRules.canAfford(agent.fuel(), cost)) continue;

            TrafficStatus adjTraffic = state.matchData().map().terrainAt(adj) == Terrain.ROAD
                    ? state.roadTraffic().getOrDefault(adj, TrafficStatus.CLEAR) : null;
            MoveCost returnCost = MovementRules.costFromSource(state.matchData().map(), adj, adjTraffic).orElse(null);
            int retSteps = returnCost != null ? returnCost.stepCost() : 999;
            int retFuel = returnCost != null ? returnCost.patrolFuelCost() : 999;

            if (bestAdj == null || retSteps < bestReturnSteps
                    || (retSteps == bestReturnSteps && retFuel < bestReturnFuel)) {
                bestAdj = adj;
                bestDir = dir;
                bestCost = cost;
                bestReturnSteps = retSteps;
                bestReturnFuel = retFuel;
            }
        }
        if (bestAdj == null) return null;
        return new PositionAndMove(bestAdj, bestDir, bestCost);
    }
}
