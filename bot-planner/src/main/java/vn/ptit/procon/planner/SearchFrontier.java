package vn.ptit.procon.planner;

/**
 * Bounded frontier used by the anytime team search.
 *
 * <p>The legacy implementation is a pure quality-first bounded priority queue. The M11
 * implementation adds a bounded diversity reserve on top of the same quality ordering.</p>
 *
 * @param <T> search state type
 */
interface SearchFrontier<T> {

    /**
     * Adds a state and re-applies the retention policy.
     *
     * @return the number of states the frontier limit forced out, either zero or one
     */
    int add(T state);

    boolean isEmpty();

    int size();

    /**
     * True when a diversity expansion would actually explore a different opening strategy than
     * a quality expansion would. False when the frontier holds a single strategy, in which case
     * the caller must fall back to a quality expansion.
     */
    boolean diversityAvailable();

    /**
     * Removes and returns the next state to expand. Each state can be handed out at most once.
     *
     * @param preferDiversity select from the least-expanded strategy instead of globally best
     */
    T poll(boolean preferDiversity);
}
