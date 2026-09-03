package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;

/** A bounded team-level responsibility proposal. */
public record StrategicAllocation(List<List<Integer>> primaryTargets,
        List<List<Integer>> secondaryTargets, String supportClass, String signature) {
    public StrategicAllocation {
        primaryTargets = copy(primaryTargets);
        secondaryTargets = copy(secondaryTargets);
        supportClass = Objects.requireNonNull(supportClass);
        signature = Objects.requireNonNull(signature);
    }
    private static List<List<Integer>> copy(List<List<Integer>> value) {
        Objects.requireNonNull(value);
        return value.stream().map(List::copyOf).toList();
    }
}
