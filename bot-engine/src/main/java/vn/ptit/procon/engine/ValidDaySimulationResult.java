package vn.ptit.procon.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/** Fully committed final state and trace for an accepted plan. */
public record ValidDaySimulationResult(
        List<AgentState> finalAgents,
        Map<Position, Integer> remainingSpotStock,
        Map<AgentId, Integer> portionsCollectedByAgent,
        Set<BrandId> brandsCollected,
        Map<Position, Integer> roadStoppedSteps,
        Map<AgentId, AgentStepUsage> stepUsage,
        List<TimelineStep> timeline,
        List<SimulationEvent> events)
        implements DaySimulationResult {

    public ValidDaySimulationResult {
        finalAgents = List.copyOf(Objects.requireNonNull(finalAgents, "Final agents must not be null"));
        remainingSpotStock = immutablePositionMap(remainingSpotStock, "Remaining spot stock");
        portionsCollectedByAgent = immutableAgentMap(
                portionsCollectedByAgent, "Collected portions");
        brandsCollected = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(brandsCollected, "Collected brands must not be null")));
        roadStoppedSteps = immutablePositionMap(roadStoppedSteps, "Road stopped steps");
        stepUsage = immutableAgentMap(stepUsage, "Step usage");
        timeline = List.copyOf(Objects.requireNonNull(timeline, "Timeline must not be null"));
        events = List.copyOf(Objects.requireNonNull(events, "Events must not be null"));
    }

    @Override
    public boolean valid() {
        return true;
    }

    private static <V> Map<Position, V> immutablePositionMap(Map<Position, V> input, String name) {
        Objects.requireNonNull(input, name + " must not be null");
        List<Map.Entry<Position, V>> entries = new ArrayList<>(input.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.comparingInt(Position::value)));
        Map<Position, V> copy = new LinkedHashMap<>();
        for (Map.Entry<Position, V> entry : entries) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), name + " key must not be null"),
                    Objects.requireNonNull(entry.getValue(), name + " value must not be null"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static <V> Map<AgentId, V> immutableAgentMap(Map<AgentId, V> input, String name) {
        Objects.requireNonNull(input, name + " must not be null");
        List<Map.Entry<AgentId, V>> entries = new ArrayList<>(input.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.comparingInt(AgentId::value)));
        Map<AgentId, V> copy = new LinkedHashMap<>();
        for (Map.Entry<AgentId, V> entry : entries) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), name + " key must not be null"),
                    Objects.requireNonNull(entry.getValue(), name + " value must not be null"));
        }
        return Collections.unmodifiableMap(copy);
    }
}