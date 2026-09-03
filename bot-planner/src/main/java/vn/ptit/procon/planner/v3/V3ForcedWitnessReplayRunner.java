package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.rules.MovementRules;

/**
 * PART 19 of Phase 2.4.  Forces the V2 strategic skeleton through V3's own lookup and replay primitives
 * and reports where V3 stops being able to follow it.
 *
 * <p>Deliberately unreachable from {@link StrategicTeamSearch}: the runner is only ever called by the
 * Phase 2.4 audits, so a forced skeleton can never become a real V3 plan.
 */
public final class V3ForcedWitnessReplayRunner {

    public V3ForcedWitnessReplayResult replay(DayState state, V2BaselineWitness witness,
            V2StrategicWitness strategic, V3ForcedWitnessReplayResult.Mode mode) {
        DayState replayState = mode == V3ForcedWitnessReplayResult.Mode.STRICT ? state
                : withGrantedSupport(state, witness);
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder()
                .build(replayState, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        StrategicTrajectoryCache cache = new StrategicTrajectoryCache(replayState);
        Map<AgentId, List<CachedTrajectoryEffect>> routes = new LinkedHashMap<>();
        Map<AgentId, List<Route>> raw = new LinkedHashMap<>();
        List<String> resolved = new ArrayList<>();
        int attempted = 0;
        int resolvedCount = 0;
        String firstMismatch = "NONE";
        String firstReason = "NONE";
        for (V2StrategicWitness.PatrolSkeleton patrol : strategic.patrols()) {
            Position cursor = patrol.start();
            boolean broken = false;
            for (V2StrategicWitness.StrategicStep step : patrol.transitions()) {
                attempted++;
                if (broken) continue;
                Route route = step.index() == 0
                        ? graph.agentRoutes().getOrDefault(patrol.patrolId(), Map.of()).get(step.to())
                        : graph.opportunityRoutes().getOrDefault(cursor, Map.of()).get(step.to());
                if (route == null) {
                    broken = true;
                    if (firstMismatch.equals("NONE")) {
                        firstMismatch = "patrol" + patrol.patrolId().value() + "#" + step.index() + " "
                                + step.label();
                        firstReason = step.index() == 0 ? "NO_GRAPH_ENTRY_ROUTE_FOR_PATROL"
                                : "NO_RETAINED_OPPORTUNITY_ROUTE";
                    }
                    continue;
                }
                routes.computeIfAbsent(patrol.patrolId(), key -> new ArrayList<>())
                        .add(cache.effect(patrol.patrolId(), route));
                raw.computeIfAbsent(patrol.patrolId(), key -> new ArrayList<>()).add(route);
                resolved.add("p" + patrol.patrolId().value() + " " + step.label() + " steps="
                        + route.stepsUsed() + " fuel=" + route.fuelUsed());
                resolvedCount++;
                cursor = step.to();
            }
        }
        var chronology = new StrategicChronologyReplay().replay(replayState, routes);
        Optional<TeamPlan> plan = forcedPlan(replayState, raw);
        boolean validatorAccepted = false;
        int simulatorOwn = 0;
        int hybrid = 0;
        if (plan.isPresent()) {
            validatorAccepted = new PlanValidator().validate(replayState, plan.get()).valid();
            if (new DaySimulator().simulate(replayState, plan.get()) instanceof ValidDaySimulationResult valid) {
                simulatorOwn = valid.portionsCollectedByAgent().values().stream()
                        .mapToInt(Integer::intValue).sum();
            }
            hybrid = new FrozenObjectiveEvaluator(replayState).evaluate(plan.get())
                    .map(StrategicOracleEvaluation::hybridMarginScore4).orElse(0);
        }
        return new V3ForcedWitnessReplayResult(mode, attempted, resolvedCount, firstMismatch, firstReason,
                chronology.collections(), hybrid, chronology.fingerprint(), plan.isPresent(),
                validatorAccepted, simulatorOwn, resolved, notes(mode, replayState, state));
    }

    private static String notes(V3ForcedWitnessReplayResult.Mode mode, DayState replayState, DayState state) {
        if (mode == V3ForcedWitnessReplayResult.Mode.STRICT) return "UNTOUCHED_DAY_STATE";
        return "PATROL_FUEL_GRANTED " + replayState.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL)
                .map(agent -> agent.id().value() + ":" + fuelOf(agent))
                .collect(Collectors.joining(",")) + " ORIGINAL " + state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL)
                .map(agent -> agent.id().value() + ":" + fuelOf(agent)).collect(Collectors.joining(","));
    }

    private static int fuelOf(AgentState agent) {
        return agent.fuel() instanceof FiniteFuel finite ? finite.amount() : Integer.MAX_VALUE;
    }

    /**
     * SUPPORT_GRANTED: every patrol V2's tanker actually serviced starts with the post-service fuel V2
     * observed.  This does not model the tanker; it removes the tanker from the question entirely.
     */
    private static DayState withGrantedSupport(DayState state, V2BaselineWitness witness) {
        Map<AgentId, Integer> granted = new LinkedHashMap<>();
        witness.support().services().forEach(service -> granted.putIfAbsent(service.patrolId(),
                service.after()));
        List<AgentState> agents = state.agents().stream()
                .map(agent -> granted.containsKey(agent.id())
                        ? new AgentState(agent.id(), agent.kind(), agent.position(),
                                new FiniteFuel(granted.get(agent.id())))
                        : agent)
                .toList();
        return new DayState(state.matchData(), state.day(), agents, state.roadTraffic(), state.spotStock(),
                state.observedOthers());
    }

    /** Mirrors V3's own route-to-action normalization; never used by the real search. */
    private static Optional<TeamPlan> forcedPlan(DayState state, Map<AgentId, List<Route>> routes) {
        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
        Set<Position> refuelPositions = state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.REFUEL).map(AgentState::position)
                .collect(Collectors.toSet());
        for (AgentState agent : state.agents()) {
            if (agent.kind() != AgentKind.PATROL) {
                actions.put(agent.id(), List.of(new WaitAction(state.stepBudget())));
                continue;
            }
            List<AgentAction> sequence = new ArrayList<>();
            Position cursor = agent.position();
            int used = 0;
            int fuel = fuelOf(agent);
            for (Route route : routes.getOrDefault(agent.id(), List.of())) {
                for (var direction : route.directions()) {
                    Position destination = state.matchData().map().neighbor(cursor, direction).orElse(null);
                    var traffic = state.matchData().map().terrainAt(cursor) == Terrain.ROAD
                            ? state.roadTraffic().get(cursor) : null;
                    var cost = MovementRules.costFromSource(state.matchData().map(), cursor, traffic)
                            .orElse(null);
                    if (destination == null || cost == null || fuel < cost.patrolFuelCost()
                            || used + cost.stepCost() > state.stepBudget()) {
                        return Optional.empty();
                    }
                    sequence.add(new MoveAction(direction));
                    used += cost.stepCost();
                    fuel -= cost.patrolFuelCost();
                    cursor = destination;
                    if (refuelPositions.contains(cursor)) {
                        fuel = state.matchData().patrolFuelCapacity().value();
                    }
                }
            }
            if (used < state.stepBudget()) sequence.add(new WaitAction(state.stepBudget() - used));
            actions.put(agent.id(), List.copyOf(sequence));
        }
        try {
            return Optional.of(new TeamPlan(actions));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
