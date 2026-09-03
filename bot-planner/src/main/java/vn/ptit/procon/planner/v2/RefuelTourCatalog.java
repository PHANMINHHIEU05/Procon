package vn.ptit.procon.planner.v2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.RefuelRouteFinder;
import vn.ptit.procon.planner.Route;

/** Daily immutable route input for bounded R3 tour construction. */
final class RefuelTourCatalog {
    record PatrolMeeting(AgentId patrolId, Position position, Route route) { }

    private final Map<AgentId, List<PatrolMeeting>> meetings;
    private final Map<String, Route> refuelRoutes;
    private final int pathfindingExecutions;

    private RefuelTourCatalog(Map<AgentId, List<PatrolMeeting>> meetings, Map<String, Route> refuelRoutes,
            int pathfindingExecutions) {
        this.meetings = Map.copyOf(meetings);
        this.refuelRoutes = Map.copyOf(refuelRoutes);
        this.pathfindingExecutions = pathfindingExecutions;
    }

    static RefuelTourCatalog forState(DayState state, RefuelRouteFinder finder) {
        JointRouteCatalog patrolCatalog = JointRouteCatalog.forState(state);
        Map<AgentId, List<PatrolMeeting>> meetings = new LinkedHashMap<>();
        for (AgentState patrol : state.agents()) {
            if (patrol.kind() != AgentKind.PATROL) continue;
            List<PatrolMeeting> values = new ArrayList<>();
            values.add(new PatrolMeeting(patrol.id(), patrol.position(), emptyRoute(patrol.position())));
            state.matchData().udonSpots().stream()
                    .sorted(Comparator.comparingInt(UdonSpot::stockCapacity).reversed()
                            .thenComparingInt(spot -> spot.position().value()))
                    .map(spot -> new PatrolMeeting(patrol.id(), spot.position(), patrolCatalog
                            .routes(patrol.position(), spot.position()).stream()
                            .map(JointRouteCatalog.CatalogRoute::route).findFirst().orElse(null)))
                    .filter(value -> value.route() != null)
                    .filter(value -> value.route().stepsUsed() < state.stepBudget())
                    .limit(2).forEach(values::add);
            meetings.put(patrol.id(), List.copyOf(values));
        }
        Map<String, Route> routes = new LinkedHashMap<>();
        int executions = patrolCatalog.pathfindingExecutions();
        List<Position> positions = meetings.values().stream().flatMap(List::stream)
                .map(PatrolMeeting::position).distinct().sorted(Comparator.comparingInt(Position::value)).toList();
        for (AgentState refuel : state.agents()) if (refuel.kind() == AgentKind.REFUEL) {
            List<Position> sources = new ArrayList<>(); sources.add(refuel.position()); sources.addAll(positions);
            for (Position source : sources.stream().distinct().toList()) for (Position target : positions) {
                if (source.equals(target)) {
                    routes.put(key(refuel.id(), source, target), emptyRoute(source));
                    continue;
                }
                AgentState from = AgentState.refuel(refuel.id(), source);
                Optional<Route> route = finder.find(state, from, target); executions++;
                route.ifPresent(value -> routes.put(key(refuel.id(), source, target), value));
            }
        }
        return new RefuelTourCatalog(meetings, routes, executions);
    }

    List<PatrolMeeting> meetings(AgentId patrol) { return meetings.getOrDefault(patrol, List.of()); }
    Route refuelRoute(AgentId refuel, Position from, Position to) { return refuelRoutes.get(key(refuel, from, to)); }
    int pathfindingExecutions() { return pathfindingExecutions; }

    private static String key(AgentId refuel, Position from, Position to) {
        return refuel.value() + ":" + from.value() + ">" + to.value();
    }

    private static Route emptyRoute(Position position) { return new Route(position, position, List.of(), 0, 0); }
}
