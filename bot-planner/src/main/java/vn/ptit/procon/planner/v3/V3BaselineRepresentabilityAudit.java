package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.Route;

/**
 * Answers PART 3/4/5 of Phase 2.4: is the frozen V2 strategy inside the V3 plan space, and if not,
 * where does the representation stop.
 *
 * <p>Every predicate below is evaluated with V3's own primitives — the retained graph, the cached
 * opportunity routes and V3's static refuel semantics — so a negative verdict is a statement about
 * V3's representation, not about search effort.
 */
public final class V3BaselineRepresentabilityAudit {

    public V3BaselineRepresentability audit(DayState state, V2BaselineWitness witness,
            V2StrategicWitness strategic, StrategicOpportunityGraph graph,
            List<StrategicTerminalSnapshot> terminals) {
        Objects.requireNonNull(state);
        Set<Position> v3RefuelPositions = state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.REFUEL).map(AgentState::position)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Position> opportunityPositions = graph.opportunities().stream()
                .map(StrategicOpportunity::position).collect(Collectors.toCollection(LinkedHashSet::new));
        List<V3BaselineRepresentability.TransitionAudit> audits = new ArrayList<>();
        for (V2StrategicWitness.PatrolSkeleton patrol : strategic.patrols()) {
            audits.addAll(auditPatrol(state, patrol, graph, opportunityPositions, v3RefuelPositions));
        }
        int represented = (int) audits.stream()
                .filter(V3BaselineRepresentability.TransitionAudit::represented).count();
        var firstMissing = audits.stream()
                .filter(audit -> !audit.represented()).findFirst();
        return new V3BaselineRepresentability(audits, audits.size(), represented, audits.size() - represented,
                audits.isEmpty() ? 1.0 : (double) represented / audits.size(), represented == audits.size(),
                firstMissing.map(V3BaselineRepresentability.TransitionAudit::label).orElse("NONE"),
                firstMissing.map(V3BaselineRepresentability.TransitionAudit::reason)
                        .orElse(V3RepresentabilityReason.REPRESENTED),
                supportRoot(state, witness, terminals, v3RefuelPositions));
    }

    private static List<V3BaselineRepresentability.TransitionAudit> auditPatrol(DayState state,
            V2StrategicWitness.PatrolSkeleton patrol, StrategicOpportunityGraph graph,
            Set<Position> opportunityPositions, Set<Position> v3RefuelPositions) {
        List<V3BaselineRepresentability.TransitionAudit> result = new ArrayList<>();
        int fuel = patrol.startFuel();
        int elapsed = 0;
        for (V2StrategicWitness.StrategicStep step : patrol.transitions()) {
            boolean first = step.index() == 0;
            Route cached = first
                    ? graph.agentRoutes().getOrDefault(patrol.patrolId(), Map.of()).get(step.to())
                    : graph.opportunityRoutes().getOrDefault(step.from(), Map.of()).get(step.to());
            boolean edgePresent = first ? cached != null
                    : graph.outgoing().getOrDefault(step.from(), List.of()).stream()
                            .anyMatch(edge -> edge.to().position().equals(step.to()));
            boolean nodeSource = first || opportunityPositions.contains(step.from());
            boolean nodeDestination = opportunityPositions.contains(step.to());
            boolean trajectoryMatch = cached != null && spotsOf(state, cached).equals(step.traversedSpots());
            boolean supportCompatible = !step.supportDependent()
                    || v3RefuelPositions.containsAll(step.requiredRefuelPositions());
            boolean stateCompatible = false;
            if (cached != null) {
                stateCompatible = elapsed + cached.stepsUsed() <= state.stepBudget()
                        && fuelFeasible(state, fuel, cached, v3RefuelPositions);
                if (stateCompatible) {
                    fuel = fuelAfter(state, fuel, cached, v3RefuelPositions);
                    elapsed += cached.stepsUsed();
                }
            }
            result.add(new V3BaselineRepresentability.TransitionAudit(patrol.patrolId(), step.index(),
                    step.from(), step.to(), nodeSource, nodeDestination, edgePresent, cached != null,
                    trajectoryMatch, supportCompatible, stateCompatible,
                    reason(nodeDestination, cached != null, edgePresent, supportCompatible, stateCompatible,
                            trajectoryMatch)));
        }
        return result;
    }

    private static V3RepresentabilityReason reason(boolean nodeDestination, boolean cached, boolean edgePresent,
            boolean supportCompatible, boolean stateCompatible, boolean trajectoryMatch) {
        if (!nodeDestination) return V3RepresentabilityReason.NODE_MISSING;
        if (!cached) {
            return supportCompatible ? V3RepresentabilityReason.EDGE_MISSING
                    : V3RepresentabilityReason.SUPPORT_ROOT_MISSING;
        }
        if (!edgePresent) return V3RepresentabilityReason.EDGE_PORTFOLIO_PRUNED;
        if (!supportCompatible) return V3RepresentabilityReason.SUPPORT_ROOT_MISSING;
        if (!stateCompatible) return V3RepresentabilityReason.POST_SUPPORT_STATE_MISMATCH;
        if (!trajectoryMatch) return V3RepresentabilityReason.TRAJECTORY_ROUTE_MISMATCH;
        return V3RepresentabilityReason.REPRESENTED;
    }

    private static List<Position> spotsOf(DayState state, Route route) {
        Set<Position> spots = state.matchData().udonSpots().stream()
                .map(vn.ptit.procon.domain.udon.UdonSpot::position).collect(Collectors.toSet());
        return CachedTrajectoryEffect.from(state, new vn.ptit.procon.domain.agent.AgentId(0), route)
                .traversedPositions().stream().skip(1).filter(spots::contains).toList();
    }

    private static boolean fuelFeasible(DayState state, int fuel, Route route, Set<Position> refuelPositions) {
        int current = fuel;
        for (CachedTrajectoryEffect.Segment segment
                : CachedTrajectoryEffect.from(state, new vn.ptit.procon.domain.agent.AgentId(0), route).segments()) {
            current -= segment.fuelAfter() - segment.fuelBefore();
            if (current < 0) return false;
            if (refuelPositions.contains(segment.destination())) {
                current = state.matchData().patrolFuelCapacity().value();
            }
        }
        return true;
    }

    private static int fuelAfter(DayState state, int fuel, Route route, Set<Position> refuelPositions) {
        int current = fuel;
        for (CachedTrajectoryEffect.Segment segment
                : CachedTrajectoryEffect.from(state, new vn.ptit.procon.domain.agent.AgentId(0), route).segments()) {
            current -= segment.fuelAfter() - segment.fuelBefore();
            if (refuelPositions.contains(segment.destination())) {
                current = state.matchData().patrolFuelCapacity().value();
            }
        }
        return Math.max(0, current);
    }

    private static V3BaselineRepresentability.SupportRootReplay supportRoot(DayState state,
            V2BaselineWitness witness, List<StrategicTerminalSnapshot> terminals,
            Set<Position> v3RefuelPositions) {
        V2BaselineWitness.SupportWitness support = witness.support();
        Set<String> supportStates = terminals.stream().map(terminal -> terminal.state().supportState())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (supportStates.isEmpty()) supportStates.add("NO_REFUEL");
        long movingSupportAgents = state.agents().stream().filter(agent -> agent.kind() == AgentKind.REFUEL)
                .filter(agent -> terminals.stream().anyMatch(terminal -> terminal.plan()
                        .actionsByAgent().getOrDefault(agent.id(), List.of()).stream()
                        .anyMatch(MoveAction.class::isInstance)))
                .count();
        boolean rootGenerated = movingSupportAgents > 0
                || supportStates.stream().anyMatch(value -> !value.equals("NO_REFUEL"));
        int boundary = support.services().stream().mapToInt(V2BaselineWitness.ServiceEvent::step).min().orElse(0);
        boolean positionMatch = true;
        boolean fuelMatch = true;
        boolean timelineMatch = true;
        for (V2BaselineWitness.ServiceEvent service : support.services()) {
            V2BaselineWitness.PatrolWitness patrol = witness.patrol(service.patrolId());
            positionMatch &= service.position().equals(patrol.start());
            fuelMatch &= service.after() == patrol.startFuel();
            timelineMatch &= service.step() == 0;
        }
        Map<Position, Integer> v2StockAtBoundary = new LinkedHashMap<>(state.spotStock());
        Set<BrandId> v2BrandsAtBoundary = new LinkedHashSet<>();
        for (V2BaselineWitness.PatrolWitness patrol : witness.patrols()) {
            for (V2BaselineWitness.Claim claim : patrol.claims()) {
                if (claim.step() > boundary) continue;
                v2StockAtBoundary.merge(claim.position(), -1, Integer::sum);
                v2BrandsAtBoundary.add(claim.brand());
            }
        }
        var root = new StrategicChronologyReplay().replay(state, Map.of());
        return new V3BaselineRepresentability.SupportRootReplay(support.rootSignature(), support.serviceCount(),
                support.supportedPatrols().stream().map(vn.ptit.procon.domain.agent.AgentId::value).toList(),
                support.refuelPositions().stream().map(Position::value).toList(),
                v3RefuelPositions.stream().map(Position::value).sorted().toList(), rootGenerated, rootGenerated,
                positionMatch, fuelMatch, timelineMatch, v2StockAtBoundary.equals(root.remainingStock()),
                v2BrandsAtBoundary.equals(root.brands()), boundary, String.join("|", supportStates),
                supportStates.size(), (int) movingSupportAgents);
    }
}
