package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Quality-first bounded frontier preserving the exact pre-M11 retention behaviour: the worst
 * state under the policy comparator is evicted whenever the frontier limit is exceeded.
 *
 * @param <T> search state type
 */
final class BoundedPriorityFrontier<T> implements SearchFrontier<T> {

    private final PriorityQueue<T> queue;
    private final Comparator<T> preference;
    private final int maxSize;

    BoundedPriorityFrontier(int maxSize, Comparator<T> preference) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("Frontier limit must be positive");
        }
        this.preference = Objects.requireNonNull(preference, "Frontier preference must not be null");
        this.queue = new PriorityQueue<>(preference);
        this.maxSize = maxSize;
    }

    @Override
    public int add(T state) {
        queue.add(state);
        if (queue.size() <= maxSize) {
            return 0;
        }
        T worst = queue.stream().max(preference).orElseThrow();
        queue.remove(worst);
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public boolean diversityAvailable() {
        return false;
    }

    @Override
    public T poll(boolean preferDiversity) {
        return queue.poll();
    }
}
