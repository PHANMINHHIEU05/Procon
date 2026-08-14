package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;

/** Reusable deterministic M5-style routing for one PATROL and one day. */
final class BrandAwarePatrolPlanner {

    private final WeightedRouteFinder routeFinder;

    BrandAwarePatrolPlanner(WeightedRouteFinder routeFinder) {
        this.routeFinder = Objects.requireNonNull(routeFinder, "Route finder must not be null");
    }

    PatrolPlan plan(
            AgentState patrol,
            DayState state,
            int initialWait,
            int availableFuel,
            Map<Position, Integer> projectedStock,
            Set<BrandId> teamBrands,
            String logPrefix) {
        Objects.requireNonNull(patrol, "PATROL must not be null");
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(projectedStock, "Projected stock must not be null");
        Objects.requireNonNull(teamBrands, "Team brands must not be null");
        if (patrol.kind() != AgentKind.PATROL) {
            throw new IllegalArgumentException("Brand-aware routing requires a PATROL agent");
        }
        if (initialWait < 0 || initialWait > state.stepBudget() || availableFuel < 0) {
            throw new IllegalArgumentException("Invalid projected PATROL resources");
        }

        Position currentPosition = patrol.position();
        int remainingFuel = availableFuel;
        int remainingSteps = state.stepBudget() - initialWait;
        int collections = 0;
        List<AgentAction> actions = new ArrayList<>();
        if (initialWait > 0) {
            actions.add(new WaitAction(initialWait));
        }
        Set<Position> visitedSpots = new HashSet<>();
        Set<BrandId> patrolBrands = new LinkedHashSet<>();
        Map<Position, UdonSpot> spotsByPosition = spotsByPosition(state);

        if (projectCollection(
                currentPosition, projectedStock, visitedSpots, patrolBrands, teamBrands, spotsByPosition)) {
            collections++;
        }

        while (remainingSteps > 0) {
            AgentState projectedAgent = AgentState.patrol(
                    patrol.id(), currentPosition, remainingFuel);
            Optional<Target> selected = selectTarget(
                    projectedAgent,
                    state,
                    remainingSteps,
                    projectedStock,
                    visitedSpots,
                    patrolBrands,
                    teamBrands);
            if (selected.isEmpty()) {
                break;
            }

            Target target = selected.orElseThrow();
            actions.addAll(target.route().toMoveActions());
            remainingSteps -= target.route().stepsUsed();
            remainingFuel -= target.route().fuelUsed();

            for (Direction direction : target.route().directions()) {
                currentPosition = state.matchData().map().neighbor(currentPosition, direction).orElseThrow();
                if (projectCollection(
                        currentPosition,
                        projectedStock,
                        visitedSpots,
                        patrolBrands,
                        teamBrands,
                        spotsByPosition)) {
                    collections++;
                }
            }
            if (logPrefix != null) {
                log(logPrefix + "_TARGET",
                        "day", state.day().value(),
                        "agent", patrol.id().value(),
                        "target", target.spot().position().value(),
                        "brand", target.spot().brand().value(),
                        "steps", target.route().stepsUsed(),
                        "remainingSteps", remainingSteps,
                        "remainingFuel", remainingFuel);
            }
        }

        int usedSteps = state.stepBudget() - remainingSteps;
        return new PatrolPlan(
                ActionPlanCompleter.complete(actions, usedSteps, state.stepBudget()),
                currentPosition,
                remainingFuel,
                collections,
                patrolBrands);
    }

    private Optional<Target> selectTarget(
            AgentState projectedAgent,
            DayState state,
            int remainingSteps,
            Map<Position, Integer> projectedStock,
            Set<Position> visitedSpots,
            Set<BrandId> patrolBrands,
            Set<BrandId> teamBrands) {
        Comparator<Target> preference = Comparator
                .comparing((Target target) -> patrolBrands.contains(target.spot().brand()))
                .thenComparing(target -> teamBrands.contains(target.spot().brand()))
                .thenComparingInt(target -> target.route().stepsUsed())
                .thenComparingInt(target -> target.route().fuelUsed())
                .thenComparingInt(target -> target.spot().position().value());

        return state.matchData().udonSpots().stream()
                .filter(spot -> projectedStock.getOrDefault(spot.position(), 0) > 0)
                .filter(spot -> !visitedSpots.contains(spot.position()))
                .map(spot -> routeFinder.find(state, projectedAgent, spot.position())
                        .map(route -> new Target(spot, route)))
                .flatMap(Optional::stream)
                .filter(target -> target.route().stepsUsed() <= remainingSteps)
                .min(preference);
    }

    private boolean projectCollection(
            Position position,
            Map<Position, Integer> projectedStock,
            Set<Position> visitedSpots,
            Set<BrandId> patrolBrands,
            Set<BrandId> teamBrands,
            Map<Position, UdonSpot> spotsByPosition) {
        UdonSpot spot = spotsByPosition.get(position);
        if (spot == null || !visitedSpots.add(position)) {
            return false;
        }
        int available = projectedStock.getOrDefault(position, 0);
        if (available <= 0) {
            return false;
        }
        projectedStock.put(position, available - 1);
        patrolBrands.add(spot.brand());
        teamBrands.add(spot.brand());
        return true;
    }

    private Map<Position, UdonSpot> spotsByPosition(DayState state) {
        Map<Position, UdonSpot> result = new java.util.LinkedHashMap<>();
        for (UdonSpot spot : state.matchData().udonSpots()) {
            result.put(spot.position(), spot);
        }
        return result;
    }

    private void log(String event, Object... fields) {
        StringBuilder message = new StringBuilder(event);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            message.append(' ').append(fields[index]).append('=').append(fields[index + 1]);
        }
        System.out.println(message);
    }

    record PatrolPlan(
            List<AgentAction> actions,
            Position finalPosition,
            int remainingFuel,
            int collections,
            Set<BrandId> brands) {

        PatrolPlan {
            actions = List.copyOf(actions);
            brands = Set.copyOf(brands);
        }
    }

    private record Target(UdonSpot spot, Route route) {
    }
}
