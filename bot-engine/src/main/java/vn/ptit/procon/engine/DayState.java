package vn.ptit.procon.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.UdonSpot;

/** Immutable authoritative beginning-of-day state for our team. */
public final class DayState {

    private final StaticMatchData matchData;
    private final DayIndex day;
    private final List<AgentState> agents;
    private final Map<Position, TrafficStatus> roadTraffic;
    private final Map<Position, Integer> spotStock;
    private final List<ObservedOtherGroup> observedOthers;

    public DayState(
            StaticMatchData matchData,
            DayIndex day,
            List<AgentState> agents,
            Map<Position, TrafficStatus> roadTraffic,
            Map<Position, Integer> spotStock) {
        this(matchData, day, agents, roadTraffic, spotStock, List.of());
    }

    public DayState(
            StaticMatchData matchData,
            DayIndex day,
            List<AgentState> agents,
            Map<Position, TrafficStatus> roadTraffic,
            Map<Position, Integer> spotStock,
            List<ObservedOtherGroup> observedOthers) {
        this.matchData = Objects.requireNonNull(matchData, "Static match data must not be null");
        this.day = Objects.requireNonNull(day, "Day index must not be null");
        matchData.dayStepBudgets().stepsFor(day);

        Objects.requireNonNull(agents, "Agents must not be null");
        List<AgentState> copiedAgents = new ArrayList<>(agents);
        for (AgentState agent : copiedAgents) {
            Objects.requireNonNull(agent, "Agent state must not be null");
        }
        copiedAgents.sort(Comparator.comparingInt(agent -> agent.id().value()));
        validateAgents(copiedAgents);
        this.agents = List.copyOf(copiedAgents);

        this.roadTraffic = immutableTraffic(roadTraffic);
        this.spotStock = immutableStock(spotStock);
        Objects.requireNonNull(observedOthers, "Observed other groups must not be null");
        List<ObservedOtherGroup> copiedOthers = new ArrayList<>(observedOthers);
        copiedOthers.forEach(group -> Objects.requireNonNull(
                group, "Observed other group must not be null"));
        copiedOthers.sort(Comparator.comparingInt(ObservedOtherGroup::rawId));
        this.observedOthers = List.copyOf(copiedOthers);
    }

    public StaticMatchData matchData() {
        return matchData;
    }

    public DayIndex day() {
        return day;
    }

    public List<AgentState> agents() {
        return agents;
    }

    public Map<Position, TrafficStatus> roadTraffic() {
        return roadTraffic;
    }

    public Map<Position, Integer> spotStock() {
        return spotStock;
    }

    public List<ObservedOtherGroup> observedOthers() {
        return observedOthers;
    }

    public int stepBudget() {
        return matchData.dayStepBudgets().stepsFor(day);
    }

    private void validateAgents(List<AgentState> copiedAgents) {
        Set<Integer> ids = new HashSet<>();
        for (AgentState agent : copiedAgents) {
            if (!ids.add(agent.id().value())) {
                throw new IllegalArgumentException("Duplicate agent ID: " + agent.id());
            }
            if (!matchData.map().contains(agent.position())) {
                throw new IllegalArgumentException(
                        "Agent position is outside the map: " + agent.position());
            }
            if (agent.fuel() instanceof FiniteFuel finite
                    && finite.amount() > matchData.patrolFuelCapacity().value()) {
                throw new IllegalArgumentException(
                        "PATROL fuel exceeds configured capacity for " + agent.id());
            }
        }
    }

    private Map<Position, TrafficStatus> immutableTraffic(
            Map<Position, TrafficStatus> inputTraffic) {
        Objects.requireNonNull(inputTraffic, "Road traffic must not be null");
        List<Map.Entry<Position, TrafficStatus>> entries = new ArrayList<>(inputTraffic.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.comparingInt(Position::value)));
        Map<Position, TrafficStatus> copiedTraffic = new LinkedHashMap<>();
        for (Map.Entry<Position, TrafficStatus> entry : entries) {
            Position position = Objects.requireNonNull(entry.getKey(), "Traffic position must not be null");
            TrafficStatus status = Objects.requireNonNull(
                    entry.getValue(), "Traffic status must not be null");
            if (!matchData.map().contains(position)
                    || matchData.map().terrainAt(position) != Terrain.ROAD) {
                throw new IllegalArgumentException(
                        "Traffic status must reference a valid ROAD cell: " + position);
            }
            copiedTraffic.put(position, status);
        }
        return Collections.unmodifiableMap(copiedTraffic);
    }

    private Map<Position, Integer> immutableStock(Map<Position, Integer> inputStock) {
        Objects.requireNonNull(inputStock, "Spot stock must not be null");
        Map<Position, UdonSpot> spotsByPosition = new LinkedHashMap<>();
        for (UdonSpot spot : matchData.udonSpots()) {
            if (spotsByPosition.putIfAbsent(spot.position(), spot) != null) {
                throw new IllegalArgumentException(
                        "Udon spots must have unique positions: " + spot.position());
            }
        }
        if (!inputStock.keySet().equals(spotsByPosition.keySet())) {
            throw new IllegalArgumentException(
                    "Spot stock must contain exactly the configured Udon spot positions");
        }

        List<Position> positions = new ArrayList<>(spotsByPosition.keySet());
        positions.sort(Comparator.comparingInt(Position::value));
        Map<Position, Integer> copiedStock = new LinkedHashMap<>();
        for (Position position : positions) {
            Integer stock = Objects.requireNonNull(
                    inputStock.get(position), "Spot stock value must not be null");
            if (stock < 0) {
                throw new IllegalArgumentException(
                        "Spot stock must be non-negative at " + position + ": " + stock);
            }
            if (stock > spotsByPosition.get(position).stockCapacity()) {
                throw new IllegalArgumentException(
                        "Spot stock exceeds capacity at " + position + ": " + stock);
            }
            copiedStock.put(position, stock);
        }
        return Collections.unmodifiableMap(copiedStock);
    }
}