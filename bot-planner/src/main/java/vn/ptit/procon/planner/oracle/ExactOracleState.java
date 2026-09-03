package vn.ptit.procon.planner.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/** Future-relevant immutable state; debug route history is deliberately absent. */
public record ExactOracleState(List<AgentProgress> agents, Map<Position, Integer> remainingStock,
        int ownCollections, Set<BrandId> collectedBrands, Map<Position, List<Arrival>> arrivalTimeline,
        String supportProvenance) {
    public ExactOracleState {
        agents = agents.stream().sorted(Comparator.comparingInt(value -> value.id().value())).toList();
        remainingStock = positionMap(remainingStock);
        collectedBrands = Collections.unmodifiableSet(new LinkedHashSet<>(collectedBrands));
        arrivalTimeline = timelineMap(arrivalTimeline);
        if (ownCollections < 0 || supportProvenance == null) throw new IllegalArgumentException("Invalid exact state");
    }

    /** Provenance explains where a state came from, but it is not part of future equivalence. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ExactOracleState value)) return false;
        return ownCollections == value.ownCollections
                && agents.equals(value.agents)
                && remainingStock.equals(value.remainingStock)
                && collectedBrands.equals(value.collectedBrands)
                && arrivalTimeline.equals(value.arrivalTimeline);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agents, remainingStock, ownCollections, collectedBrands, arrivalTimeline);
    }

    public record AgentProgress(AgentId id, AgentKind kind, Position position, int elapsedSteps, int fuel,
            boolean stopped, Set<Position> visited) {
        public AgentProgress {
            visited = Collections.unmodifiableSet(new LinkedHashSet<>(visited));
            if (id == null || kind == null || position == null || elapsedSteps < 0 || fuel < -1) {
                throw new IllegalArgumentException("Invalid agent progress");
            }
        }
    }

    public record Arrival(AgentId agentId, int step) {
        public Arrival {
            if (agentId == null || step < 0) throw new IllegalArgumentException("Invalid arrival");
        }
    }

    private static Map<Position, Integer> positionMap(Map<Position, Integer> input) {
        List<Map.Entry<Position, Integer>> entries = new ArrayList<>(input.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.comparingInt(Position::value)));
        Map<Position, Integer> result = new LinkedHashMap<>();
        entries.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(result);
    }

    private static Map<Position, List<Arrival>> timelineMap(Map<Position, List<Arrival>> input) {
        List<Map.Entry<Position, List<Arrival>>> entries = new ArrayList<>(input.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.comparingInt(Position::value)));
        Map<Position, List<Arrival>> result = new LinkedHashMap<>();
        entries.forEach(entry -> {
            List<Arrival> arrivals = new ArrayList<>(entry.getValue());
            arrivals.sort(Comparator.comparingInt(Arrival::step).thenComparingInt(value -> value.agentId().value()));
            result.put(entry.getKey(), List.copyOf(arrivals));
        });
        return Collections.unmodifiableMap(result);
    }
}
