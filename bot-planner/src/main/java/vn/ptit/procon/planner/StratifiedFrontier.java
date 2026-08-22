package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Bounded frontier that keeps its states grouped by {@link StrategicDiversityKey} while still
 * enforcing one global capacity limit.
 *
 * <p>Retention has two lanes. The global elite lane keeps the strongest states under the
 * unchanged policy comparator with no per-strategy cap, so a genuinely strong strategy may own
 * most of it. The portfolio reserve then preserves representation for the qualified strategies
 * and the remaining promising ones, preferring strategies with fewer retained states. Capacity
 * the reserve cannot use falls back to more states of the strategies that do have them, so no
 * frontier slot is ever left empty to satisfy strategy balancing.</p>
 *
 * <p>States live in exactly one ordered list, and buckets are derived from it, so a state can
 * never be handed out for expansion twice by moving between bucket and global structures.</p>
 *
 * @param <T> search state type
 */
final class StratifiedFrontier<T> implements SearchFrontier<T> {

    private final int maxSize;
    private final int eliteSlots;
    private final Comparator<T> preference;
    private final Function<T, StrategicDiversityKey> strategy;

    /** Retained states, kept ordered strongest first under {@link #preference}. */
    private final List<T> states = new ArrayList<>();

    /** Per-strategy metadata, ordered by the deterministic strategy key. */
    private final Map<StrategicDiversityKey, StrategyRecord> strategies = new TreeMap<>();

    private int observationOrder;
    private int eliteRetained;
    private int portfolioRetained;

    StratifiedFrontier(
            int maxSize,
            int eliteSlots,
            Comparator<T> preference,
            Function<T, StrategicDiversityKey> strategy) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("Frontier limit must be positive");
        }
        if (eliteSlots <= 0 || eliteSlots > maxSize) {
            throw new IllegalArgumentException("Global elite reserve must be within the frontier limit");
        }
        this.maxSize = maxSize;
        this.eliteSlots = eliteSlots;
        this.preference = Objects.requireNonNull(preference, "Frontier preference must not be null");
        this.strategy = Objects.requireNonNull(strategy, "Strategy key function must not be null");
    }

    @Override
    public int add(T state) {
        StrategicDiversityKey key = strategy.apply(state);
        StrategyRecord record = strategies.computeIfAbsent(
                key, ignored -> new StrategyRecord(observationOrder++));
        record.generatedStateCount++;
        insertOrdered(state);
        if (states.size() <= maxSize) {
            eliteRetained = Math.min(eliteSlots, states.size());
            portfolioRetained = states.size() - eliteRetained;
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
        return !strategy.apply(leastExpandedState()).equals(strategy.apply(states.get(0)));
    }

    @Override
    public T poll(boolean preferDiversity) {
        if (states.isEmpty()) {
            return null;
        }
        return expand(preferDiversity ? leastExpandedState() : states.get(0));
    }

    /** Unchanged policy comparator this frontier orders states by. */
    Comparator<T> preference() {
        return preference;
    }

    /** Strategy key of one state, using the same derivation the buckets use. */
    StrategicDiversityKey strategyOf(T state) {
        return strategy.apply(state);
    }

    /** Expands the strongest available state of one strategy, or nothing when it has none. */
    T pollStrategy(StrategicDiversityKey key) {
        T head = head(key);
        return head == null ? null : expand(head);
    }

    /** Strongest available state of one strategy without expanding it. */
    T head(StrategicDiversityKey key) {
        Objects.requireNonNull(key, "Strategy key must not be null");
        for (T state : states) {
            if (strategy.apply(state).equals(key)) {
                return state;
            }
        }
        return null;
    }

    boolean hasStates(StrategicDiversityKey key) {
        return head(key) != null;
    }

    /** Strategy owning the globally strongest available state, or null when the frontier is empty. */
    StrategicDiversityKey bestStrategy() {
        return states.isEmpty() ? null : strategy.apply(states.get(0));
    }

    /**
     * Strategies that still have expandable states, ranked by their best available state under
     * the unchanged policy comparator and then by the deterministic strategy key. No new numeric
     * strategy score is introduced: the ranking is the existing frontier ordering.
     */
    List<StrategicDiversityKey> rankedStrategies() {
        Map<StrategicDiversityKey, T> heads = new TreeMap<>();
        for (T state : states) {
            heads.putIfAbsent(strategy.apply(state), state);
        }
        List<StrategicDiversityKey> ranked = new ArrayList<>(heads.keySet());
        ranked.sort(Comparator
                .comparing((StrategicDiversityKey key) -> heads.get(key), preference)
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(ranked);
    }

    /** Marks the qualified portfolio, which the frontier reserve then keeps represented. */
    void markQualified(Collection<StrategicDiversityKey> qualified) {
        Objects.requireNonNull(qualified, "Qualified strategies must not be null");
        strategies.values().forEach(record -> record.qualified = false);
        for (StrategicDiversityKey key : qualified) {
            strategies.computeIfAbsent(key, ignored -> new StrategyRecord(observationOrder++))
                    .qualified = true;
        }
    }

    int expansionCount(StrategicDiversityKey key) {
        StrategyRecord record = strategies.get(key);
        return record == null ? 0 : record.expansionCount;
    }

    int generatedStateCount(StrategicDiversityKey key) {
        StrategyRecord record = strategies.get(key);
        return record == null ? 0 : record.generatedStateCount;
    }

    int firstSeenOrder(StrategicDiversityKey key) {
        StrategyRecord record = strategies.get(key);
        return record == null ? -1 : record.firstSeenOrder;
    }

    /** Position of a strategy's best available state in the global frontier order, or -1. */
    int bestFrontierOrdinal(StrategicDiversityKey key) {
        for (int index = 0; index < states.size(); index++) {
            if (strategy.apply(states.get(index)).equals(key)) {
                return index;
            }
        }
        return -1;
    }

    /** Expansion count per strategy that was expanded at least once, in deterministic key order. */
    Map<StrategicDiversityKey, Integer> expansionCountsByStrategy() {
        Map<StrategicDiversityKey, Integer> counts = new LinkedHashMap<>();
        strategies.forEach((key, record) -> {
            if (record.expansionCount > 0) {
                counts.put(key, record.expansionCount);
            }
        });
        return Collections.unmodifiableMap(counts);
    }

    int strategiesExpanded() {
        return (int) strategies.values().stream().filter(record -> record.expansionCount > 0).count();
    }

    int strategyBucketsSeen() {
        return strategies.size();
    }

    int maxStrategyExpansionCount() {
        return strategies.values().stream().mapToInt(record -> record.expansionCount).max().orElse(0);
    }

    int eliteRetained() {
        return eliteRetained;
    }

    int portfolioRetained() {
        return portfolioRetained;
    }

    private T expand(T state) {
        removeIdentity(state);
        strategies.computeIfAbsent(
                strategy.apply(state), ignored -> new StrategyRecord(observationOrder++))
                .expansionCount++;
        return state;
    }

    /**
     * Best state of the strategy with the fewest expansions so far, breaking ties by the policy
     * comparator and then by deterministic key order.
     */
    private T leastExpandedState() {
        Map<StrategicDiversityKey, T> heads = new TreeMap<>();
        for (T state : states) {
            heads.putIfAbsent(strategy.apply(state), state);
        }
        return heads.entrySet().stream()
                .min(Comparator
                        .<Map.Entry<StrategicDiversityKey, T>>comparingInt(
                                entry -> expansionCount(entry.getKey()))
                        .thenComparing(Map.Entry::getValue, preference)
                        .thenComparing(Map.Entry::getKey))
                .orElseThrow()
                .getValue();
    }

    /**
     * Re-applies the elite-plus-portfolio retention policy and drops the single excess state.
     *
     * @return the number of states the frontier limit forced out
     */
    private int applyRetention() {
        int eliteCount = Math.min(eliteSlots, states.size());
        Set<T> keep = identitySet();
        Map<StrategicDiversityKey, Integer> retainedByStrategy = new TreeMap<>();
        for (int index = 0; index < eliteCount; index++) {
            T state = states.get(index);
            keep.add(state);
            retainedByStrategy.merge(strategy.apply(state), 1, Integer::sum);
        }

        Map<StrategicDiversityKey, List<T>> available = new TreeMap<>();
        for (int index = eliteCount; index < states.size(); index++) {
            T state = states.get(index);
            available.computeIfAbsent(strategy.apply(state), key -> new ArrayList<>()).add(state);
        }
        Map<StrategicDiversityKey, Integer> taken = new TreeMap<>();
        // Reserve pass one: give every qualified strategy that is not represented yet one state.
        for (StrategicDiversityKey key : rankByHead(available)) {
            if (keep.size() >= maxSize) {
                break;
            }
            StrategyRecord record = strategies.get(key);
            if (record == null || !record.qualified
                    || retainedByStrategy.getOrDefault(key, 0) > 0) {
                continue;
            }
            takeReserveState(keep, available, taken, retainedByStrategy, key);
        }
        // Reserve pass two: fewer retained states first, then stronger head, then key order.
        boolean progressed = true;
        while (keep.size() < maxSize && progressed) {
            progressed = false;
            StrategicDiversityKey chosen = null;
            for (StrategicDiversityKey key : rankByHead(available)) {
                if (taken.getOrDefault(key, 0) >= available.get(key).size()) {
                    continue;
                }
                if (chosen == null
                        || retainedByStrategy.getOrDefault(key, 0)
                                < retainedByStrategy.getOrDefault(chosen, 0)) {
                    chosen = key;
                }
            }
            if (chosen != null) {
                takeReserveState(keep, available, taken, retainedByStrategy, chosen);
                progressed = true;
            }
        }

        int portfolioCount = keep.size() - eliteCount;
        eliteRetained = eliteCount;
        portfolioRetained = portfolioCount;
        int evicted = 0;
        for (int index = states.size() - 1; index >= 0; index--) {
            if (!keep.contains(states.get(index))) {
                states.remove(index);
                evicted++;
            }
        }
        return evicted;
    }

    private void takeReserveState(
            Set<T> keep,
            Map<StrategicDiversityKey, List<T>> available,
            Map<StrategicDiversityKey, Integer> taken,
            Map<StrategicDiversityKey, Integer> retainedByStrategy,
            StrategicDiversityKey key) {
        int used = taken.getOrDefault(key, 0);
        keep.add(available.get(key).get(used));
        taken.put(key, used + 1);
        retainedByStrategy.merge(key, 1, Integer::sum);
    }

    /** Strategy order by strongest still-unkept state, then deterministic key. */
    private List<StrategicDiversityKey> rankByHead(Map<StrategicDiversityKey, List<T>> available) {
        List<StrategicDiversityKey> ranked = new ArrayList<>(available.keySet());
        ranked.sort(Comparator
                .comparing((StrategicDiversityKey key) -> available.get(key).get(0), preference)
                .thenComparing(Comparator.naturalOrder()));
        return ranked;
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
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Bounded per-strategy metadata, alive only for one planning call. */
    private static final class StrategyRecord {

        private final int firstSeenOrder;
        private int generatedStateCount;
        private int expansionCount;
        private boolean qualified;

        private StrategyRecord(int firstSeenOrder) {
            this.firstSeenOrder = firstSeenOrder;
        }
    }
}
