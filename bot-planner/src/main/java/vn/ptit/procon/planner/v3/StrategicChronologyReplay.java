package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.engine.DayState;

/** Settles all cached arrivals in simulator order, independent of expansion order. */
public final class StrategicChronologyReplay {
    private int replays;
    private int eventsProcessed;

    public ChronologyResult replay(DayState state, Map<AgentId, List<CachedTrajectoryEffect>> routes) {
        Objects.requireNonNull(state); Objects.requireNonNull(routes);
        replays++;
        Map<Position, Integer> stock = new LinkedHashMap<>(state.spotStock());
        Map<AgentId, Set<Position>> visited = new LinkedHashMap<>();
        Map<AgentId, Integer> portions = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            visited.put(agent.id(), new LinkedHashSet<>());
            portions.put(agent.id(), 0);
        }
        List<Arrival> arrivals = new ArrayList<>();
        for (AgentState agent : state.agents()) {
            if (agent.kind() == AgentKind.PATROL) arrivals.add(new Arrival(0, agent.id(), agent.position()));
            int offset = 0;
            for (CachedTrajectoryEffect effect : routes.getOrDefault(agent.id(), List.of())) {
                for (CachedTrajectoryEffect.Encounter encounter : effect.encounters()) {
                    arrivals.add(new Arrival(offset + encounter.relativeStep(), agent.id(), encounter.position()));
                }
                offset += effect.stepsUsed();
            }
        }
        arrivals.sort(Comparator.comparingInt(Arrival::step).thenComparingInt(a -> a.agentId().value()));
        eventsProcessed += arrivals.size();
        Set<BrandId> brands = new LinkedHashSet<>();
        List<Claim> claims = new ArrayList<>();
        for (Arrival arrival : arrivals) {
            if (!visited.getOrDefault(arrival.agentId(), Set.of()).add(arrival.position())) continue;
            var spot = state.matchData().udonSpots().stream()
                    .filter(candidate -> candidate.position().equals(arrival.position())).findFirst().orElse(null);
            if (spot == null || stock.getOrDefault(arrival.position(), 0) <= 0) continue;
            int remaining = stock.get(arrival.position()) - 1;
            stock.put(arrival.position(), remaining);
            portions.computeIfPresent(arrival.agentId(), (id, count) -> count + 1);
            brands.add(spot.brand());
            claims.add(new Claim(arrival.step(), arrival.agentId(), arrival.position(), spot.brand(), remaining));
        }
        Map<AgentId, Position> finalPositions = new LinkedHashMap<>();
        Map<AgentId, Integer> finalElapsed = new LinkedHashMap<>();
        Map<AgentId, Integer> finalFuel = new LinkedHashMap<>();
        Set<Position> stationaryRefuelPositions = state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.REFUEL
                        && routes.getOrDefault(agent.id(), List.of()).isEmpty())
                .map(AgentState::position).collect(Collectors.toSet());
        for (AgentState agent : state.agents()) {
            Position position = agent.position(); int elapsed = 0;
            int currentFuel = agent.fuel() instanceof FiniteFuel finite ? finite.amount() : Integer.MAX_VALUE;
            if (agent.kind() == AgentKind.PATROL && routes.getOrDefault(agent.id(), List.of()).isEmpty()
                    && stationaryRefuelPositions.contains(agent.position()) && state.stepBudget() > 0) {
                currentFuel = state.matchData().patrolFuelCapacity().value();
            }
            for (CachedTrajectoryEffect effect : routes.getOrDefault(agent.id(), List.of())) {
                for (CachedTrajectoryEffect.Segment segment : effect.segments()) {
                    if (currentFuel != Integer.MAX_VALUE) {
                        currentFuel -= segment.fuelAfter() - segment.fuelBefore();
                        if (stationaryRefuelPositions.contains(segment.destination())) {
                            currentFuel = state.matchData().patrolFuelCapacity().value();
                        }
                    }
                }
                position = effect.goal(); elapsed += effect.stepsUsed();
            }
            finalPositions.put(agent.id(), position); finalElapsed.put(agent.id(), elapsed);
            finalFuel.put(agent.id(), agent.fuel() instanceof FiniteFuel
                    ? Math.max(0, currentFuel) : Integer.MAX_VALUE);
        }
        String fingerprint = claims.stream().map(c -> c.step() + ":" + c.agentId().value() + ":" + c.position().value())
                .collect(Collectors.joining(","));
        return new ChronologyResult(stock, portions, brands, visited, claims, finalPositions, finalElapsed, finalFuel, fingerprint);
    }

    public int replays() { return replays; }
    public int eventsProcessed() { return eventsProcessed; }

    public record Arrival(int step, AgentId agentId, Position position) {
        public Arrival { Objects.requireNonNull(agentId); Objects.requireNonNull(position); }
    }
    public record Claim(int step, AgentId agentId, Position position, BrandId brand, int remainingStock) { }
    public record ChronologyResult(Map<Position, Integer> remainingStock, Map<AgentId, Integer> portions,
            Set<BrandId> brands, Map<AgentId, Set<Position>> visited, List<Claim> claims,
            Map<AgentId, Position> finalPositions, Map<AgentId, Integer> finalElapsed,
            Map<AgentId, Integer> finalFuel, String fingerprint) {
        public ChronologyResult {
            remainingStock = Map.copyOf(remainingStock); portions = Map.copyOf(portions); brands = Set.copyOf(brands);
            visited = visited.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
            claims = List.copyOf(claims); finalPositions = Map.copyOf(finalPositions); finalElapsed = Map.copyOf(finalElapsed);
            finalFuel = Map.copyOf(finalFuel); fingerprint = Objects.requireNonNull(fingerprint);
        }
        public int collections() { return portions.values().stream().mapToInt(Integer::intValue).sum(); }
    }
}
