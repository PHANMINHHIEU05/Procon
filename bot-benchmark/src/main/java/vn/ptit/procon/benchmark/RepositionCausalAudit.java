package vn.ptit.procon.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.planner.WeightedRouteFinder;

public final class RepositionCausalAudit {

    public static void main(String[] args) throws IOException {
        String corpusPath = args.length > 0 ? args[0] : System.getProperty("user.home") + "/.procon-autotune/corpus";
        runAudit(Path.of(corpusPath));
    }

    public static void runAudit(Path corpusRoot) throws IOException {
        List<V3CorpusDay> allDays = V3CorpusDay.loadAll(corpusRoot, true);
        Map<String, List<V3CorpusDay>> daysByMatch = new LinkedHashMap<>();
        for (V3CorpusDay day : allDays) {
            daysByMatch.computeIfAbsent(day.matchId(), k -> new ArrayList<>()).add(day);
        }

        System.out.println("==================================================");
        System.out.println("REPOSITION_CAUSAL_AUDIT START");
        System.out.println("Corpus root: " + corpusRoot + " | Matches: " + daysByMatch.size() + " | Days: " + allDays.size());
        System.out.println("==================================================");

        DaySimulator simulator = new DaySimulator();
        WeightedRouteFinder router = new WeightedRouteFinder();

        int totalEligibleDays = 0;
        int totalPatrolsEvaluated = 0;
        int totalClumpedPatrols = 0;
        int totalPatrolsWithTrailingWait3 = 0;

        List<Integer> clumpedNextDayFirstArrivals = new ArrayList<>();
        List<Integer> nonClumpedNextDayFirstArrivals = new ArrayList<>();

        for (Map.Entry<String, List<V3CorpusDay>> entry : daysByMatch.entrySet()) {
            String matchId = entry.getKey();
            List<V3CorpusDay> matchDays = entry.getValue();
            matchDays.sort(Comparator.comparingInt(V3CorpusDay::day));

            for (int i = 0; i < matchDays.size() - 1; i++) {
                V3CorpusDay day = matchDays.get(i);
                V3CorpusDay nextDay = matchDays.get(i + 1);

                DayState state = day.rebuild();
                DayState nextState = nextDay.rebuild();
                HexMap map = state.matchData().map();

                TeamPlan plan = decode(state, day.capturedActions());
                DaySimulationResult sim = simulator.simulate(state, plan);
                if (!(sim instanceof ValidDaySimulationResult valid)) {
                    continue;
                }

                TeamPlan nextPlan = decode(nextState, nextDay.capturedActions());
                DaySimulationResult nextSim = simulator.simulate(nextState, nextPlan);
                ValidDaySimulationResult nextValid = (nextSim instanceof ValidDaySimulationResult nv) ? nv : null;

                Map<AgentId, Integer> nextDayFirstCollection = new HashMap<>();
                if (nextValid != null) {
                    for (var event : nextValid.events()) {
                        if (event instanceof UdonCollectedEvent collected) {
                            nextDayFirstCollection.putIfAbsent(collected.agentId(), collected.step());
                        }
                    }
                }

                // Patrol agents
                List<AgentState> finalPatrols = valid.finalAgents().stream()
                        .filter(a -> a.kind() == AgentKind.PATROL)
                        .toList();
                int patrolCount = finalPatrols.size();
                if (patrolCount == 0) continue;

                totalEligibleDays++;

                // Clump detection
                Map<Position, List<AgentId>> patrolsByPosition = new LinkedHashMap<>();
                for (AgentState p : finalPatrols) {
                    patrolsByPosition.computeIfAbsent(p.position(), k -> new ArrayList<>()).add(p.id());
                }

                int terminalUniqueCells = patrolsByPosition.size();
                int terminalClumpCount = 0;
                int largestTerminalClump = 1;
                for (List<AgentId> ids : patrolsByPosition.values()) {
                    if (ids.size() > 1) {
                        terminalClumpCount++;
                        if (ids.size() > largestTerminalClump) {
                            largestTerminalClump = ids.size();
                        }
                    }
                }

                // Trailing waits and unused fuel
                int patrolsWithTrailingWait3 = 0;
                int trailingWaitTotal = 0;
                Map<AgentId, Integer> trailingWaitByPatrol = new LinkedHashMap<>();
                Map<AgentId, Integer> unusedFuelByPatrol = new LinkedHashMap<>();

                for (AgentState p : finalPatrols) {
                    List<AgentAction> actions = plan.actionsFor(p.id());
                    int tw = 0;
                    if (!actions.isEmpty() && actions.get(actions.size() - 1) instanceof WaitAction wait) {
                        tw = wait.steps();
                    }
                    trailingWaitByPatrol.put(p.id(), tw);
                    trailingWaitTotal += tw;
                    if (tw >= 3) {
                        patrolsWithTrailingWait3++;
                        totalPatrolsWithTrailingWait3++;
                    }
                    int fuel = p.fuel() instanceof FiniteFuel f ? f.amount() : 0;
                    unusedFuelByPatrol.put(p.id(), fuel);
                }

                // Mean pairwise terminal distance
                double sumDistance = 0.0;
                int pairCount = 0;
                for (int p1 = 0; p1 < finalPatrols.size(); p1++) {
                    for (int p2 = p1 + 1; p2 < finalPatrols.size(); p2++) {
                        Position pos1 = finalPatrols.get(p1).position();
                        Position pos2 = finalPatrols.get(p2).position();
                        sumDistance += hexDistance(map, pos1, pos2);
                        pairCount++;
                    }
                }
                double meanPairwiseDistance = pairCount > 0 ? sumDistance / pairCount : 0.0;

                // Nearest useful next-day opportunity distance
                Map<Position, Integer> remainingStock = valid.remainingSpotStock();
                List<UdonSpot> activeSpots = state.matchData().udonSpots().stream()
                        .filter(s -> remainingStock.getOrDefault(s.position(), 0) > 0)
                        .toList();

                Map<AgentId, Integer> nearestOppDist = new LinkedHashMap<>();
                Map<AgentId, Integer> estNextDayArrival = new LinkedHashMap<>();

                for (AgentState p : finalPatrols) {
                    int minDist = Integer.MAX_VALUE;
                    int minArrivalSteps = Integer.MAX_VALUE;
                    for (UdonSpot spot : activeSpots) {
                        int dist = hexDistance(map, p.position(), spot.position());
                        if (dist < minDist) {
                            minDist = dist;
                        }
                        Optional<Route> route = router.find(state, p, spot.position());
                        if (route.isPresent() && route.get().stepsUsed() < minArrivalSteps) {
                            minArrivalSteps = route.get().stepsUsed();
                        }
                    }
                    nearestOppDist.put(p.id(), minDist == Integer.MAX_VALUE ? -1 : minDist);
                    estNextDayArrival.put(p.id(), minArrivalSteps == Integer.MAX_VALUE ? -1 : minArrivalSteps);
                }

                Map<AgentId, Integer> arrivalBeforeMap = new LinkedHashMap<>();
                Map<AgentId, Integer> arrivalAfterMap = new LinkedHashMap<>();
                Map<AgentId, Integer> arrivalGainMap = new LinkedHashMap<>();

                for (AgentState p : finalPatrols) {
                    int tw = trailingWaitByPatrol.getOrDefault(p.id(), 0);
                    int before = estNextDayArrival.getOrDefault(p.id(), -1);
                    arrivalBeforeMap.put(p.id(), before);

                    if (tw >= 3 && before > 0) {
                        // Find best target spot
                        UdonSpot bestSpot = null;
                        int minSteps = Integer.MAX_VALUE;
                        Route bestRoute = null;
                        for (UdonSpot spot : activeSpots) {
                            Optional<Route> route = router.find(state, p, spot.position());
                            if (route.isPresent() && route.get().stepsUsed() < minSteps) {
                                minSteps = route.get().stepsUsed();
                                bestSpot = spot;
                                bestRoute = route.get();
                            }
                        }
                        if (bestRoute != null) {
                            int advanceSteps = Math.min(tw, bestRoute.stepsUsed());
                            int after = bestRoute.stepsUsed() - advanceSteps;
                            int gain = before - after;
                            arrivalAfterMap.put(p.id(), after);
                            arrivalGainMap.put(p.id(), gain);
                        } else {
                            arrivalAfterMap.put(p.id(), before);
                            arrivalGainMap.put(p.id(), 0);
                        }
                    } else {
                        arrivalAfterMap.put(p.id(), before);
                        arrivalGainMap.put(p.id(), 0);
                    }
                }

                // Clumping impact per patrol
                for (AgentState p : finalPatrols) {
                    totalPatrolsEvaluated++;
                    boolean isClumped = patrolsByPosition.get(p.position()).size() > 1;
                    if (isClumped) {
                        totalClumpedPatrols++;
                    }
                    Integer actualNextDayArrival = nextDayFirstCollection.get(p.id());
                    if (actualNextDayArrival != null) {
                        if (isClumped) {
                            clumpedNextDayFirstArrivals.add(actualNextDayArrival);
                        } else {
                            nonClumpedNextDayFirstArrivals.add(actualNextDayArrival);
                        }
                    }
                }

                // Print day audit line
                System.out.printf(Locale.ROOT,
                        "REPOSITION_CAUSAL_AUDIT matchId=%s day=%d patrolCount=%d patrolsWithTrailingWait>=3=%d "
                                + "trailingWaitTotal=%d terminalUniqueCells=%d terminalClumpCount=%d largestTerminalClump=%d "
                                + "meanPairwiseDistance=%.2f trailingWaitByPatrol=%s unusedFuelByPatrol=%s "
                                + "nearestOppDist=%s estNextDayArrival=%s arrivalGain=%s nextDayActualFirstCollection=%s%n",
                        matchId, day.day(), patrolCount, patrolsWithTrailingWait3,
                        trailingWaitTotal, terminalUniqueCells, terminalClumpCount, largestTerminalClump,
                        meanPairwiseDistance, trailingWaitByPatrol, unusedFuelByPatrol,
                        nearestOppDist, estNextDayArrival, arrivalGainMap, nextDayFirstCollection);
            }
        }

        System.out.println("==================================================");
        System.out.println("REPOSITION_CAUSAL_AUDIT SUMMARY");
        System.out.println("Total eligible non-final days: " + totalEligibleDays);
        System.out.println("Total patrols evaluated: " + totalPatrolsEvaluated);
        System.out.println("Patrols with trailing wait >= 3: " + totalPatrolsWithTrailingWait3);
        System.out.println("Total clumped patrols: " + totalClumpedPatrols);

        double avgClumpedArrival = clumpedNextDayFirstArrivals.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double avgNonClumpedArrival = nonClumpedNextDayFirstArrivals.stream().mapToInt(Integer::intValue).average().orElse(0.0);

        System.out.printf(Locale.ROOT,
                "Next-day actual first collection step: Clumped avg=%.2f (n=%d) vs Non-Clumped avg=%.2f (n=%d)%n",
                avgClumpedArrival, clumpedNextDayFirstArrivals.size(),
                avgNonClumpedArrival, nonClumpedNextDayFirstArrivals.size());
        System.out.println("==================================================");
    }

    private static TeamPlan decode(DayState state, List<List<Integer>> encodedActions) {
        Map<AgentId, List<AgentAction>> actionsByAgent = new LinkedHashMap<>();
        for (int i = 0; i < state.agents().size(); i++) {
            AgentId id = state.agents().get(i).id();
            List<Integer> encoded = i < encodedActions.size() ? encodedActions.get(i) : List.of();
            List<AgentAction> actions = new ArrayList<>();
            for (int code : encoded) {
                if (code < 0) {
                    actions.add(new WaitAction(-code));
                } else {
                    actions.add(new MoveAction(Direction.fromCode(code)));
                }
            }
            actionsByAgent.put(id, List.copyOf(actions));
        }
        return new TeamPlan(actionsByAgent);
    }

    private static int hexDistance(HexMap map, Position p1, Position p2) {
        if (p1.equals(p2)) return 0;
        int r1 = map.rowOf(p1);
        int c1 = map.columnOf(p1);
        int r2 = map.rowOf(p2);
        int c2 = map.columnOf(p2);

        // Convert even-r (r, c) to cube (x, y, z)
        int x1 = c1 - (r1 + (r1 & 1)) / 2;
        int z1 = r1;
        int y1 = -x1 - z1;

        int x2 = c2 - (r2 + (r2 & 1)) / 2;
        int z2 = r2;
        int y2 = -x2 - z2;

        return (Math.abs(x1 - x2) + Math.abs(y1 - y2) + Math.abs(z1 - z2)) / 2;
    }
}
