package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;

/** Generates a small, deliberately overlapping allocation portfolio. */
public final class StrategicAllocationGenerator {
    public List<StrategicAllocation> generate(DayState state, StrategicOpportunityGraph graph,
            StrategicSearchConfig config) {
        List<AgentState> patrols = state.agents().stream().filter(a -> a.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(a -> a.id().value())).toList();
        List<StrategicOpportunity> ranked = graph.opportunities().stream()
                .filter(o -> o.currentStock() > 0)
                .sorted(Comparator.comparingInt(StrategicOpportunity::currentStock).reversed()
                        .thenComparingInt(StrategicOpportunity::opponentPressure)
                        .thenComparingInt(o -> o.position().value())).toList();
        List<StrategicAllocation> result = new ArrayList<>();
        add(result, patrols, ranked, "SPLIT", 0);
        add(result, patrols, ranked, "SHARED", 1);
        add(result, patrols, ranked, "CROSS_REGION", 2);
        add(result, patrols, ranked, "SECONDARY", 3);
        for (int offset = 0; result.size() < config.maxAllocationCandidates() && offset < ranked.size(); offset++) {
            List<List<Integer>> primary = new ArrayList<>();
            List<List<Integer>> secondary = new ArrayList<>();
            for (int i = 0; i < patrols.size(); i++) {
                List<Integer> p = ranked.stream().skip((long) (offset + i) % Math.max(1, ranked.size()))
                        .limit(Math.min(2, ranked.size())).map(o -> o.position().value()).toList();
                primary.add(p); secondary.add(List.of());
            }
            addUnique(result, new StrategicAllocation(primary, secondary, "ROTATION", signature(primary, secondary)));
        }
        if (result.isEmpty()) result.add(new StrategicAllocation(List.of(), List.of(), "NO_TARGETS", "EMPTY"));
        return List.copyOf(result.stream().limit(config.maxAllocationCandidates()).toList());
    }

    private static void add(List<StrategicAllocation> result, List<AgentState> patrols,
            List<StrategicOpportunity> ranked, String kind, int variant) {
        List<List<Integer>> primary = new ArrayList<>();
        List<List<Integer>> secondary = new ArrayList<>();
        for (int i = 0; i < patrols.size(); i++) {
            List<Integer> values = new ArrayList<>();
            for (int j = 0; j < ranked.size(); j++) {
                StrategicOpportunity o = ranked.get(j);
                if (variant == 0 && j % Math.max(1, patrols.size()) == i) values.add(o.position().value());
                if (variant == 1 && j < 2) values.add(o.position().value());
                if (variant == 2 && (j + i) % 3 == 0) values.add(o.position().value());
                if (variant == 3 && j % Math.max(1, patrols.size()) == i) values.add(o.position().value());
                if (values.size() >= 3) break;
            }
            primary.add(List.copyOf(values));
            secondary.add(variant == 3 && ranked.size() > 3
                    ? ranked.subList(Math.min(3, ranked.size()), Math.min(5, ranked.size())).stream().map(o -> o.position().value()).toList()
                    : List.of());
        }
        addUnique(result, new StrategicAllocation(primary, secondary, kind, signature(primary, secondary)));
    }

    private static void addUnique(List<StrategicAllocation> result, StrategicAllocation value) {
        if (result.stream().noneMatch(a -> a.signature().equals(value.signature()))) result.add(value);
    }

    private static String signature(List<List<Integer>> primary, List<List<Integer>> secondary) {
        return primary + ";" + secondary;
    }
}
