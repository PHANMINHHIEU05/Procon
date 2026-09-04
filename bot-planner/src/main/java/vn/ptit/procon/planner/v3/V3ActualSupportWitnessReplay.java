package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.Route;

/**
 * PART 24: replays the V2 strategic skeleton with the REAL selected R3 tanker, the REAL timing and NO
 * upfront fuel.
 *
 * <p>This runner exists to answer one question before any search tuning is allowed: can V3's own
 * primitives — the retained edge caches, {@link CachedTrajectoryEffect}, {@link
 * SupportAwareTrajectoryScheduler}, {@link StrategicChronologyReplay} and
 * {@link V3SupportPlanMaterializer} — reproduce what V2 already achieved WITH the tanker actually driving?
 * If it cannot, the support semantics are incomplete and no amount of search work can fix that.
 *
 * <p>The support root is looked up generically, by the signature the V2 witness reports, so no fixture's
 * tanker tour is ever hardcoded (PART 22).
 */
public final class V3ActualSupportWitnessReplay {

    public V3ActualSupportWitnessResult replay(DayState state, V2BaselineWitness witness,
            V2StrategicWitness strategic, V3SupportRootUniverse universe) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(witness, "V2 witness must not be null");
        Objects.requireNonNull(strategic, "Strategic witness must not be null");
        Objects.requireNonNull(universe, "Support universe must not be null");
        int attempted = strategic.patrols().stream().mapToInt(patrol -> patrol.transitions().size()).sum();
        V3SupportRootContext root = universe.bySignature(strategic.supportRootSignature());
        if (root == null || !root.present()) {
            return missing(attempted, strategic.supportRootSignature(),
                    root == null ? "SUPPORT_ROOT_NOT_IN_R3_UNIVERSE" : "SUPPORT_ROOT_IS_NOT_MOBILE");
        }
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder()
                .build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        SupportAwareTrajectoryScheduler scheduler = SupportAwareTrajectoryScheduler.of(state, root.trajectory());
        Map<AgentId, List<Route>> routesByPatrol = new LinkedHashMap<>();
        int resolved = 0;
        String firstMismatch = "NONE";
        String firstReason = "NONE";
        for (V2StrategicWitness.PatrolSkeleton patrol : strategic.patrols()) {
            List<Route> routes = new ArrayList<>();
            Position cursor = patrol.start();
            for (V2StrategicWitness.StrategicStep step : patrol.transitions()) {
                // PART 9: the first hop reads the capacity-lifted geometry cache. Nothing about that lookup
                // asserts the hop is legal — the scheduler and the chronology below decide that.
                Route route = step.index() == 0
                        ? graph.entryRoutes(true).getOrDefault(patrol.patrolId(), Map.of()).get(step.to())
                        : graph.opportunityRoutes().getOrDefault(cursor, Map.of()).get(step.to());
                if (route == null) {
                    if (firstMismatch.equals("NONE")) {
                        firstMismatch = "patrol" + patrol.patrolId().value() + "#" + step.index() + " "
                                + step.label();
                        firstReason = step.index() == 0 ? "NO_GRAPH_ENTRY_ROUTE_FOR_PATROL"
                                : "NO_RETAINED_OPPORTUNITY_ROUTE";
                    }
                    break;
                }
                routes.add(route);
                resolved++;
                cursor = step.to();
            }
            routesByPatrol.put(patrol.patrolId(), List.copyOf(routes));
        }
        Map<AgentId, List<StrategicChronologyReplay.ScheduledLeg>> schedules = new LinkedHashMap<>();
        Map<AgentId, Integer> waits = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            if (agent.kind() != AgentKind.PATROL) continue;
            List<Route> routes = routesByPatrol.getOrDefault(agent.id(), List.of());
            List<StrategicChronologyReplay.ScheduledLeg> scheduled =
                    V3SupportPlanMaterializer.schedule(state, scheduler, agent, routes);
            if (scheduled == null) {
                if (firstMismatch.equals("NONE")) {
                    firstMismatch = "patrol" + agent.id().value();
                    firstReason = "SCHEDULE_INFEASIBLE_UNDER_SUPPORT_ROOT";
                }
                continue;
            }
            schedules.put(agent.id(), scheduled);
            waits.put(agent.id(), scheduled.stream()
                    .mapToInt(StrategicChronologyReplay.ScheduledLeg::leadInWaitSteps).sum());
        }
        StrategicChronologyReplay chronology = new StrategicChronologyReplay();
        StrategicChronologyReplay.ChronologyResult replay = chronology.replaySupported(state, schedules,
                root.trajectory());
        Optional<TeamPlan> plan = V3SupportPlanMaterializer.materialize(state, root, scheduler, routesByPatrol);
        boolean validatorAccepted = false;
        boolean simulatorValid = false;
        int simulatorOwn = 0;
        int hybrid = 0;
        V3MobileSupportParityAudit parity = V3MobileSupportParityAudit.unavailable("NO_PLAN");
        if (plan.isPresent()) {
            validatorAccepted = new PlanValidator().validate(state, plan.get()).valid();
            if (new DaySimulator().simulate(state, plan.get()) instanceof ValidDaySimulationResult valid) {
                simulatorValid = true;
                simulatorOwn = valid.portionsCollectedByAgent().values().stream()
                        .mapToInt(Integer::intValue).sum();
                parity = V3MobileSupportParityAudit.of(state, root.trajectory(), replay, valid);
            } else {
                parity = V3MobileSupportParityAudit.unavailable("SIMULATION_REJECTED");
            }
            hybrid = new FrozenObjectiveEvaluator(state).evaluate(plan.get())
                    .map(StrategicOracleEvaluation::hybridMarginScore4).orElse(0);
        }
        String notes = "UNTOUCHED_DAY_STATE NO_FUEL_GRANTED refuelActions=" + root.refuelActions().size()
                + " actionSteps=" + root.trajectory().actionSteps() + " insertedWaits=" + waits
                + " chronologyFuelFeasible=" + replay.fuelFeasible() + "/" + replay.infeasibleReason()
                + " witnessOwn=" + witness.ownSemiCollections();
        return new V3ActualSupportWitnessResult(root.supportRootId(), root.signature(), attempted, resolved,
                firstMismatch, firstReason, plan.isPresent(), validatorAccepted, simulatorValid, simulatorOwn,
                hybrid, replay.collections(), replay.fingerprint(), waits,
                V3SupportEventTable.of(root, replay), parity, notes);
    }

    private static V3ActualSupportWitnessResult missing(int attempted, String signature, String reason) {
        return new V3ActualSupportWitnessResult(V3SupportRootContext.NO_REFUEL, signature, attempted, 0,
                signature, reason, false, false, false, 0, 0, 0, "", Map.of(),
                V3SupportEventTable.of(V3SupportRootContext.noRefuel(),
                        new StrategicChronologyReplay.ChronologyResult(Map.of(), Map.of(), java.util.Set.of(),
                                Map.of(), List.of(), Map.of(), Map.of(), Map.of(), "")),
                V3MobileSupportParityAudit.unavailable(reason), "SUPPORT_ROOT_UNAVAILABLE");
    }
}
