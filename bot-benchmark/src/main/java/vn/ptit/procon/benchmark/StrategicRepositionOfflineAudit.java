package vn.ptit.procon.benchmark;

import java.io.IOException;
import java.nio.file.Path;
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
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.NextDayHarvestCapacityCalculator;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.planner.TeamNextDayHarvestCapacity;
import vn.ptit.procon.planner.WeightedRouteFinder;
import vn.ptit.procon.planner.v2.StrategicRepositioner;

public final class StrategicRepositionOfflineAudit {

    public static void main(String[] args) throws IOException {
        String corpusPath = args.length > 0 ? args[0] : System.getProperty("user.home") + "/.procon-autotune/corpus";
        runAudit(Path.of(corpusPath));
    }

    public static void runAudit(Path corpusRoot) throws IOException {
        List<V3CorpusDay> allDays = V3CorpusDay.loadAll(corpusRoot, true);
        System.out.println("Loaded " + allDays.size() + " accepted corpus days from " + corpusRoot);

        Map<String, List<V3CorpusDay>> daysByMatch = new LinkedHashMap<>();
        for (V3CorpusDay day : allDays) {
            daysByMatch.computeIfAbsent(day.matchId(), k -> new ArrayList<>()).add(day);
        }

        DaySimulator simulator = new DaySimulator();
        WeightedRouteFinder router = new WeightedRouteFinder();

        int totalEligibleDays = 0;
        int totalAcceptedDays = 0;
        int totalPatrolsMoved = 0;
        int totalTrailingWaitSaved = 0;

        for (Map.Entry<String, List<V3CorpusDay>> entry : daysByMatch.entrySet()) {
            String matchId = entry.getKey();
            List<V3CorpusDay> matchDays = entry.getValue();
            matchDays.sort(Comparator.comparingInt(V3CorpusDay::day));

            for (int i = 0; i < matchDays.size(); i++) {
                V3CorpusDay day = matchDays.get(i);
                DayState state = day.rebuild();
                int totalDaysInMatch = state.matchData().dayStepBudgets().dayCount();
                if (day.day() >= totalDaysInMatch - 1) {
                    continue; // Skip final day
                }

                TeamPlan originalPlan = decode(state, day.capturedActions());
                DaySimulationResult origSim = simulator.simulate(state, originalPlan);
                if (!(origSim instanceof ValidDaySimulationResult validOrig)) {
                    continue;
                }

                // Check eligible patrols with trailing wait >= 3
                int eligiblePatrols = 0;
                int originalTrailingWait = 0;
                for (AgentState a : validOrig.finalAgents()) {
                    if (a.kind() == AgentKind.PATROL) {
                        List<AgentAction> actions = originalPlan.actionsFor(a.id());
                        int tw = getTrailingWait(actions);
                        originalTrailingWait += tw;
                        if (tw >= 3) {
                            eligiblePatrols++;
                        }
                    }
                }

                if (eligiblePatrols == 0) {
                    continue;
                }
                totalEligibleDays++;

                // Execute StrategicRepositioner
                TeamPlan candidatePlan = StrategicRepositioner.reposition(state, originalPlan);
                DaySimulationResult candSim = simulator.simulate(state, candidatePlan);
                if (!(candSim instanceof ValidDaySimulationResult validCand)) {
                    continue;
                }

                int movedPatrols = 0;
                int candidateTrailingWait = 0;
                for (AgentState a : validCand.finalAgents()) {
                    if (a.kind() == AgentKind.PATROL) {
                        List<AgentAction> candActions = candidatePlan.actionsFor(a.id());
                        List<AgentAction> origActions = originalPlan.actionsFor(a.id());
                        int tw = getTrailingWait(candActions);
                        candidateTrailingWait += tw;
                        if (!candActions.equals(origActions)) {
                            movedPatrols++;
                        }
                    }
                }

                int origCollections = validOrig.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum();
                int candCollections = validCand.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum();
                int todayCollectionDiff = candCollections - origCollections;

                Set<Position> cellsBefore = new LinkedHashSet<>();
                for (AgentState a : validOrig.finalAgents()) {
                    if (a.kind() == AgentKind.PATROL) cellsBefore.add(a.position());
                }
                Set<Position> cellsAfter = new LinkedHashSet<>();
                for (AgentState a : validCand.finalAgents()) {
                    if (a.kind() == AgentKind.PATROL) cellsAfter.add(a.position());
                }

                NextDayHarvestCapacityCalculator nextDayCalc = NextDayHarvestCapacityCalculator.forState(state);
                TeamNextDayHarvestCapacity capBefore = nextDayCalc.evaluate(validOrig);
                TeamNextDayHarvestCapacity capAfter = nextDayCalc.evaluate(validCand);

                // Estimate next day first useful arrival across moved patrols
                Map<Position, Integer> remStock = validOrig.remainingSpotStock();
                List<UdonSpot> activeSpots = state.matchData().udonSpots().stream()
                        .filter(s -> remStock.getOrDefault(s.position(), 0) > 0)
                        .toList();
                if (activeSpots.isEmpty()) activeSpots = state.matchData().udonSpots();

                int arrivalBeforeSum = 0;
                int arrivalAfterSum = 0;
                for (AgentState aOrig : validOrig.finalAgents()) {
                    if (aOrig.kind() == AgentKind.PATROL) {
                        AgentState aCand = validCand.finalAgents().stream()
                                .filter(a -> a.id().equals(aOrig.id())).findFirst().orElse(aOrig);

                        int minBefore = Integer.MAX_VALUE;
                        int minAfter = Integer.MAX_VALUE;
                        for (UdonSpot s : activeSpots) {
                            if (s.position().equals(aOrig.position())) minBefore = 0;
                            else {
                                Optional<Route> r = router.find(state, aOrig, s.position());
                                if (r.isPresent()) minBefore = Math.min(minBefore, r.get().stepsUsed());
                            }
                            if (s.position().equals(aCand.position())) minAfter = 0;
                            else {
                                Optional<Route> r = router.find(state, aCand, s.position());
                                if (r.isPresent()) minAfter = Math.min(minAfter, r.get().stepsUsed());
                            }
                        }
                        if (minBefore != Integer.MAX_VALUE) arrivalBeforeSum += minBefore;
                        if (minAfter != Integer.MAX_VALUE) arrivalAfterSum += minAfter;
                    }
                }

                boolean candidateAccepted = movedPatrols > 0 && todayCollectionDiff >= 0;
                if (candidateAccepted) {
                    totalAcceptedDays++;
                    totalPatrolsMoved += movedPatrols;
                    totalTrailingWaitSaved += (originalTrailingWait - candidateTrailingWait);
                }

                System.out.printf(
                        "REPOSITION_DAY_AUDIT matchId=%s day=%d eligiblePatrols=%d movedPatrols=%d "
                                + "originalTrailingWait=%d candidateTrailingWait=%d todayCollectionDiff=%d "
                                + "terminalUniqueCellsBefore=%d terminalUniqueCellsAfter=%d "
                                + "nextDayCapacityBefore=%d nextDayCapacityAfter=%d "
                                + "firstUsefulArrivalBefore=%d firstUsefulArrivalAfter=%d candidateAccepted=%b%n",
                        matchId, day.day(), eligiblePatrols, movedPatrols,
                        originalTrailingWait, candidateTrailingWait, todayCollectionDiff,
                        cellsBefore.size(), cellsAfter.size(),
                        capBefore.totalPatrolDistinctSpotCapacity(), capAfter.totalPatrolDistinctSpotCapacity(),
                        arrivalBeforeSum, arrivalAfterSum, candidateAccepted);
            }
        }

        System.out.printf("REPOSITION_OFFLINE_AUDIT_SUMMARY totalEligibleDays=%d totalAcceptedDays=%d totalPatrolsMoved=%d totalTrailingWaitSaved=%d%n",
                totalEligibleDays, totalAcceptedDays, totalPatrolsMoved, totalTrailingWaitSaved);
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

    private static int getTrailingWait(List<AgentAction> actions) {
        if (actions == null || actions.isEmpty()) return 0;
        AgentAction last = actions.get(actions.size() - 1);
        return last instanceof WaitAction wait ? wait.steps() : 0;
    }
}
