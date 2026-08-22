package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Bounded frontier with an elite reserve and a subordinate strategic diversity reserve.
 *
 * <p>Retention keeps the globally strongest states under the unchanged policy comparator in the
 * elite lane without any strategic cap, then offers the remaining capacity to strategically
 * different branches by bounded deterministic round-robin over {@link StrategicDiversityKey}
 * buckets. Capacity the diversity reserve cannot use returns to the quality lane, so frontier
 * capacity is never left unused to satisfy the diversity policy.</p>
 *
 * <p>Expansion scheduling is deterministic: a diversity expansion takes the best state of the
 * least-expanded strategy, breaking ties by the policy comparator and then by key order.</p>
 *
 * @param <T> search state type
 */
final class DiverseFrontier<T> implements SearchFrontier<T> {

    private final int maxSize;
    private final int eliteSlots;
    private final int maxStatesPerStrategy;
    private final Comparator<T> preference;
    private final Function<T, StrategicDiversityKey> strategy;

    /** Retained states, kept ordered strongest first under {@link #preference}. */
    private final List<T> states = new ArrayList<>();

    /** Expansion count per strategy, bounded by the number of distinct openings. */
    private final Map<StrategicDiversityKey, Integer> expansionsByStrategy = new LinkedHashMap<>();

    /** Distinct strategies the frontier has ever retained, bounded by distinct openings. */
    private final Set<StrategicDiversityKey> strategiesSeen = new LinkedHashSet<>();

    private int eliteRetained;
    private int diverseRetained;

    DiverseFrontier(
            int maxSize,
            int eliteSlots,
            int maxStatesPerStrategy,
            Comparator<T> preference,
            Function<T, StrategicDiversityKey> strategy) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("Frontier limit must be positive");
        }
        if (eliteSlots <= 0 || eliteSlots > maxSize) {
            throw new IllegalArgumentException("Frontier elite reserve must be within the frontier limit");
        }
        if (maxStatesPerStrategy <= 0) {
            throw new IllegalArgumentException("Diversity states per strategy must be positive");
        }
        this.maxSize = maxSize;
        this.eliteSlots = eliteSlots;
        this.maxStatesPerStrategy = maxStatesPerStrategy;
        this.preference = Objects.requireNonNull(preference, "Frontier preference must not be null");
        this.strategy = Objects.requireNonNull(strategy, "Strategy key function must not be null");
    }

    @Override
    public int add(T state) {
        insertOrdered(state);
        strategiesSeen.add(strategy.apply(state));
        if (states.size() <= maxSize) {
            eliteRetained = Math.min(eliteSlots, states.size());
            diverseRetained = states.size() - eliteRetained;
            return 0;
        }
        return applyRetention();
    }

    @Override
    public boolean isEmpty() {
        return states.isEmpty();
    }

    @Override
    public int size() {
        return states.size();
    }

    @Override
    public boolean diversityAvailable() {
        if (states.size() < 2) {
            return false;
        }
        StrategicDiversityKey best = strategy.apply(states.get(0));
        return !strategy.apply(leastExpandedState()).equals(best);
    }

    @Override
    public T poll(boolean preferDiversity) {
        if (states.isEmpty()) {
            return null;
        }
        T chosen = preferDiversity ? leastExpandedState() : states.get(0);
        removeIdentity(chosen);
        expansionsByStrategy.merge(strategy.apply(chosen), 1, Integer::sum);
        return chosen;
    }

    int uniqueStrategyKeysExpanded() {
        return expansionsByStrategy.size();
    }

    int maxStrategyExpansionCount() {
        return expansionsByStrategy.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /**
     * Read-only depth diagnostic: strategies expanded at least {@code threshold} times. Used to
     * compare M11 strategy depth against the M11 correction; it never influences scheduling.
     */
    int strategiesWithAtLeastExpansions(int threshold) {
        return (int) expansionsByStrategy.values().stream()
                .filter(count -> count >= threshold)
                .count();
    }

    int strategyBucketsSeen() {
        return strategiesSeen.size();
    }

    int eliteRetained() {
        return eliteRetained;
    }

    int diverseRetained() {
        return diverseRetained;
    }

    /**
     * Best state of the strategy with the fewest expansions so far, breaking ties by the policy
     * comparator and then by deterministic key order.
     */
    private T leastExpandedState() {
        Map<StrategicDiversityKey, T> bestByStrategy = new LinkedHashMap<>();
        for (T state : states) {
            bestByStrategy.putIfAbsent(strategy.apply(state), state);
        }
        return bestByStrategy.entrySet().stream()
                .min(Comparator
                        .<Map.Entry<StrategicDiversityKey, T>>comparingInt(
                                entry -> expansionsByStrategy.getOrDefault(entry.getKey(), 0))
                        .thenComparing(Map.Entry::getValue, preference)
                        .thenComparing(Map.Entry::getKey))
                .orElseThrow()
                .getValue();
    }

    /** Re-applies the elite-plus-diversity retention policy and drops the single excess state. */
    private int applyRetention() {
        int eliteCount = Math.min(eliteSlots, states.size());
        Set<T> keep = identitySet();
        for (int index = 0; index < eliteCount; index++) {
            keep.add(states.get(index));
        }
        Map<StrategicDiversityKey, Integer> eliteByStrategy = new HashMap<>();
        for (int index = 0; index < eliteCount; index++) {
            eliteByStrategy.merge(strategy.apply(states.get(index)), 1, Integer::sum);
        }

        Map<StrategicDiversityKey, List<T>> buckets = new LinkedHashMap<>();
        for (int index = eliteCount; index < states.size(); index++) {
            T state = states.get(index);
            buckets.computeIfAbsent(strategy.apply(state), key -> new ArrayList<>()).add(state);
        }
        List<StrategicDiversityKey> bucketOrder = buckets.keySet().stream()
                .sorted(Comparator
                        .<StrategicDiversityKey>comparingInt(key -> eliteByStrategy.getOrDefault(key, 0))
                        .thenComparing(key -> buckets.get(key).get(0), preference)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        Map<StrategicDiversityKey, Integer> taken = new HashMap<>();
        int diverseCount = 0;
        boolean progressed = true;
        while (keep.size() < maxSize && progressed) {
            progressed = false;
            for (StrategicDiversityKey key : bucketOrder) {
                if (keep.size() >= maxSize) {
                    break;
                }
                int used = taken.getOrDefault(key, 0);
                List<T> bucket = buckets.get(key);
                if (used >= maxStatesPerStrategy || used >= bucket.size()) {
                    continue;
                }
                keep.add(bucket.get(used));
                taken.put(key, used + 1);
                diverseCount++;
                progressed = true;
            }
        }
        // Unused diversity reserve returns to the quality lane rather than staying empty.
        int spillover = 0;
        for (T state : states) {
            if (keep.size() >= maxSize) {
                break;
            }
            if (keep.add(state)) {
                spillover++;
            }
        }

        eliteRetained = eliteCount + spillover;
        diverseRetained = diverseCount;
        int evicted = 0;
        for (int index = states.size() - 1; index >= 0; index--) {
            if (!keep.contains(states.get(index))) {
                states.remove(index);
                evicted++;
            }
        }
        return evicted;
    }

    private void insertOrdered(T state) {
        int index = 0;
        while (index < states.size() && preference.compare(state, states.get(index)) >= 0) {
            index++;
        }
        states.add(index, state);
    }

    private void removeIdentity(T state) {
        for (int index = 0; index < states.size(); index++) {
            if (states.get(index) == state) {
                states.remove(index);
                return;
            }
        }
    }

    private Set<T> identitySet() {
        return java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }
}
