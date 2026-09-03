package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/** Deterministic ownership of a bounded set of team opportunities. */
public final class TeamOpportunityAllocation {

    public enum Seed {
        MIN_COST_OWNERSHIP,
        BALANCED_LOAD,
        DISTINCT_EARLY,
        BRAND_COVERAGE
    }

    private final Seed seed;
    private final int refinement;
    private final Map<AgentId, List<Position>> assigned;
    private final String signature;

    public TeamOpportunityAllocation(
            Seed seed, int refinement, Map<AgentId, ? extends List<Position>> assigned) {
        this.seed = Objects.requireNonNull(seed, "Allocation seed must not be null");
        if (refinement < 0 || refinement > 4) {
            throw new IllegalArgumentException("Allocation refinement must be between 0 and 4");
        }
        this.refinement = refinement;
        Objects.requireNonNull(assigned, "Assigned opportunities must not be null");
        List<AgentId> ids = new ArrayList<>(assigned.keySet());
        ids.sort(Comparator.comparingInt(AgentId::value));
        Map<AgentId, List<Position>> copied = new LinkedHashMap<>();
        for (AgentId id : ids) {
            Objects.requireNonNull(id, "Allocation agent must not be null");
            List<Position> positions = new ArrayList<>(
                    Objects.requireNonNull(assigned.get(id), "Agent allocation must not be null"));
            positions.sort(Comparator.comparingInt(Position::value));
            copied.put(id, List.copyOf(positions));
        }
        this.assigned = Map.copyOf(copied);
        this.signature = signature(copied);
    }

    public Seed seed() { return seed; }

    public int refinement() { return refinement; }

    public Map<AgentId, List<Position>> assigned() { return assigned; }

    public List<Position> assignedTo(AgentId id) {
        return assigned.getOrDefault(id, List.of());
    }

    public String signature() { return signature; }

    public int distinctAssignedOpportunities() {
        return (int) assigned.values().stream().flatMap(List::stream).distinct().count();
    }

    public int duplicateOwnedOpportunityCount() {
        Map<Position, Integer> counts = new LinkedHashMap<>();
        assigned.values().forEach(values -> values.forEach(position ->
                counts.merge(position, 1, Integer::sum)));
        return counts.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum();
    }

    private static String signature(Map<AgentId, ? extends List<Position>> assigned) {
        StringBuilder value = new StringBuilder();
        assigned.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(AgentId::value)))
                .forEach(entry -> {
                    value.append(entry.getKey().value()).append(':');
                    entry.getValue().stream().sorted(Comparator.comparingInt(Position::value))
                            .forEach(position -> value.append(position.value()).append(','));
                    value.append(';');
                });
        return value.toString();
    }
}
