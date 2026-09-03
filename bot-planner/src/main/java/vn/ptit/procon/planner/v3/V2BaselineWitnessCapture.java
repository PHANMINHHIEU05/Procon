package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.MoveCompletedEvent;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.SimulationEvent;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.rules.MovementRules;

/**
 * Captures the authoritative {@link V2BaselineWitness} for a day.
 *
 * <p>The frozen planner, validator, simulator and objective evaluator are the only sources of
 * truth here.  Nothing in this class reasons about what V3 could or could not do.
 */
public final class V2BaselineWitnessCapture {

    /** Runs the frozen V2/R3 planner and captures its selected plan. */
    public V2BaselineWitness capture(DayState state) {
        return capture(state, new JointTeamBeamR3Planner().planWithStats(state).plan());
    }

    /** Captures an already selected frozen plan; the plan is never modified. */
    public V2BaselineWitness capture(DayState state, TeamPlan plan) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(plan);
        boolean validatorAccepted = new PlanValidator().validate(state, plan).valid();
        DaySimulationResult simulation = new DaySimulator().simulate(state, plan);
        if (!(simulation instanceof ValidDaySimulationResult valid)) {
            throw new IllegalStateException("Frozen V2 plan did not simulate: witness capture is impossible");
        }
        Map<AgentId, List<MoveCompletedEvent>> moves = new LinkedHashMap<>();
        Map<AgentId, List<UdonCollectedEvent>> collections = new LinkedHashMap<>();
        List<RefueledEvent> refuels = new ArrayList<>();
        for (SimulationEvent event : valid.events()) {
            if (event instanceof MoveCompletedEvent move) {
                moves.computeIfAbsent(move.agentId(), id -> new ArrayList<>()).add(move);
            } else if (event instanceof UdonCollectedEvent collected) {
                collections.computeIfAbsent(collected.agentId(), id -> new ArrayList<>()).add(collected);
            } else if (event instanceof RefueledEvent refueled) {
                refuels.add(refueled);
            }
        }
        moves.values().forEach(list -> list.sort(Comparator.comparingInt(MoveCompletedEvent::step)));
        collections.values().forEach(list -> list.sort(Comparator.comparingInt(UdonCollectedEvent::step)));
        refuels.sort(Comparator.comparingInt(RefueledEvent::step)
                .thenComparingInt(event -> event.patrolId().value()));
        Set<Position> spotPositions = state.matchData().udonSpots().stream().map(vn.ptit.procon.domain.udon.UdonSpot::position)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<AgentId, AgentState> finalAgents = new LinkedHashMap<>();
        valid.finalAgents().forEach(agent -> finalAgents.put(agent.id(), agent));

        List<V2BaselineWitness.PatrolWitness> patrols = new ArrayList<>();
        for (AgentState agent : state.agents()) {
            if (agent.kind() != AgentKind.PATROL) continue;
            patrols.add(patrolWitness(state, agent, moves.getOrDefault(agent.id(), List.of()),
                    collections.getOrDefault(agent.id(), List.of()), refuels, spotPositions,
                    finalAgents.get(agent.id())));
        }
        StrategicOracleEvaluation evaluation = new FrozenObjectiveEvaluator(state).evaluate(plan)
                .orElseThrow(() -> new IllegalStateException("Frozen V2 plan is not evaluable"));
        return new V2BaselineWitness(plan, validatorAccepted, true, patrols,
                supportWitness(state, moves, refuels), evaluation.ownSemiCollections(), evaluation.ownSemiBrands(),
                evaluation.coupledOwnCollections(), evaluation.hybridMarginScore4(),
                evaluation.physicalSignature(), valid.remainingSpotStock());
    }

    private static V2BaselineWitness.PatrolWitness patrolWitness(DayState state, AgentState agent,
            List<MoveCompletedEvent> moves, List<UdonCollectedEvent> collected, List<RefueledEvent> refuels,
            Set<Position> spotPositions, AgentState finalState) {
        List<Position> route = new ArrayList<>();
        route.add(agent.position());
        moves.forEach(move -> route.add(move.destination()));
        Map<Integer, Integer> claimStepIndex = new LinkedHashMap<>();
        for (int i = 0; i < collected.size(); i++) claimStepIndex.put(collected.get(i).step(), i);
        List<V2BaselineWitness.Encounter> encounters = new ArrayList<>();
        if (spotPositions.contains(agent.position())) {
            encounters.add(new V2BaselineWitness.Encounter(0, agent.position(), claimStepIndex.containsKey(0)));
        }
        for (MoveCompletedEvent move : moves) {
            if (!spotPositions.contains(move.destination())) continue;
            encounters.add(new V2BaselineWitness.Encounter(move.step(), move.destination(),
                    claimStepIndex.containsKey(move.step())));
        }
        List<V2BaselineWitness.Claim> claims = collected.stream()
                .map(event -> new V2BaselineWitness.Claim(event.step(), event.position(), event.brand(),
                        event.remainingStock())).toList();
        List<BrandId> brands = claims.stream().map(V2BaselineWitness.Claim::brand).distinct()
                .sorted(Comparator.comparing(BrandId::value)).toList();
        return new V2BaselineWitness.PatrolWitness(agent.id(), agent.position(),
                ((FiniteFuel) agent.fuel()).amount(), route, encounters, claims, brands,
                fuelChronology(state, agent, moves, refuels), finalState.position(),
                finalState.fuel() instanceof FiniteFuel finite ? finite.amount() : Integer.MAX_VALUE,
                moves.isEmpty() ? 0 : moves.get(moves.size() - 1).step());
    }

    private static List<V2BaselineWitness.FuelPoint> fuelChronology(DayState state, AgentState agent,
            List<MoveCompletedEvent> moves, List<RefueledEvent> refuels) {
        List<V2BaselineWitness.FuelPoint> points = new ArrayList<>();
        Map<Integer, RefueledEvent> byStep = new LinkedHashMap<>();
        refuels.stream().filter(event -> event.patrolId().equals(agent.id()))
                .forEach(event -> byStep.put(event.step(), event));
        int fuel = ((FiniteFuel) agent.fuel()).amount();
        if (byStep.containsKey(0)) {
            RefueledEvent event = byStep.remove(0);
            points.add(new V2BaselineWitness.FuelPoint(0, event.position(), event.before(), event.after(), "REFUEL"));
            fuel = event.after();
        }
        for (MoveCompletedEvent move : moves) {
            int cost = MovementRules.costFromSource(state.matchData().map(), move.source(),
                            state.roadTraffic().get(move.source()))
                    .orElseThrow(() -> new IllegalStateException("Traced move left an impassable cell"))
                    .patrolFuelCost();
            points.add(new V2BaselineWitness.FuelPoint(move.step(), move.destination(), fuel, fuel - cost, "MOVE"));
            fuel -= cost;
            RefueledEvent event = byStep.remove(move.step());
            if (event == null) continue;
            points.add(new V2BaselineWitness.FuelPoint(event.step(), event.position(), event.before(),
                    event.after(), "REFUEL"));
            fuel = event.after();
        }
        byStep.values().forEach(event -> points.add(new V2BaselineWitness.FuelPoint(event.step(),
                event.position(), event.before(), event.after(), "REFUEL")));
        points.sort(Comparator.comparingInt(V2BaselineWitness.FuelPoint::step)
                .thenComparing(point -> point.cause().equals("MOVE") ? 0 : 1));
        return List.copyOf(points);
    }

    private static V2BaselineWitness.SupportWitness supportWitness(DayState state,
            Map<AgentId, List<MoveCompletedEvent>> moves, List<RefueledEvent> refuels) {
        List<AgentId> refuelAgents = state.agents().stream().filter(agent -> agent.kind() == AgentKind.REFUEL)
                .map(AgentState::id).toList();
        List<Position> starts = state.agents().stream().filter(agent -> agent.kind() == AgentKind.REFUEL)
                .map(AgentState::position).toList();
        Map<AgentId, List<Position>> routes = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            if (agent.kind() != AgentKind.REFUEL) continue;
            List<Position> route = new ArrayList<>();
            route.add(agent.position());
            moves.getOrDefault(agent.id(), List.of()).forEach(move -> route.add(move.destination()));
            routes.put(agent.id(), List.copyOf(route));
        }
        List<V2BaselineWitness.ServiceEvent> services = new ArrayList<>();
        List<V2BaselineWitness.ServiceEvent> topUps = new ArrayList<>();
        Set<AgentId> served = new LinkedHashSet<>();
        for (RefueledEvent event : refuels) {
            V2BaselineWitness.ServiceEvent value = new V2BaselineWitness.ServiceEvent(event.step(),
                    event.patrolId(), event.position(), event.before(), event.after(), event.refuelAgents());
            if (served.add(event.patrolId())) services.add(value); else topUps.add(value);
        }
        List<AgentId> supported = services.stream().map(V2BaselineWitness.ServiceEvent::patrolId)
                .sorted(Comparator.comparingInt(AgentId::value)).toList();
        String signature = refuelAgents.isEmpty() ? "NO_REFUEL_AGENT"
                : refuelAgents.get(0).value() + ":" + services.stream()
                        .map(service -> service.patrolId().value() + "@" + service.position().value()
                                + "/" + service.step())
                        .reduce((left, right) -> left + "," + right).orElse("NONE");
        return new V2BaselineWitness.SupportWitness(refuelAgents, starts, routes, services, topUps,
                supported, signature);
    }
}
