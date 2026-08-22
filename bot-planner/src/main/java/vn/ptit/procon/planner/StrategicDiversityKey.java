package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable description of a search branch's opening strategy.
 *
 * <p>For every PATROL agent the key records the first committed non-start Udon target in
 * the branch, or {@link #NO_TARGET} while that agent has not been committed yet. Openings
 * are ordered by {@code AgentId}, so two branches that assigned the same first targets
 * compare and hash identically regardless of object identity, set iteration order, or any
 * diagnostic counter.</p>
 *
 * <p>This is a search-diversity bucket, not a state identity. Exact duplicate elimination
 * remains the responsibility of the engine's own state key.</p>
 */
record StrategicDiversityKey(List<AgentOpening> openings)
        implements Comparable<StrategicDiversityKey> {

    /** Sentinel for a PATROL agent that has not committed to a first target yet. */
    static final int NO_TARGET = -1;

    private static final Comparator<AgentOpening> AGENT_ORDER =
            Comparator.comparingInt(AgentOpening::patrolAgentId);

    StrategicDiversityKey {
        Objects.requireNonNull(openings, "Strategic openings must not be null");
        openings = List.copyOf(openings);
        for (int index = 1; index < openings.size(); index++) {
            if (openings.get(index - 1).patrolAgentId() >= openings.get(index).patrolAgentId()) {
                throw new IllegalArgumentException(
                        "Strategic openings must be ordered by unique PATROL AgentId");
            }
        }
    }

    /** Builds a key from openings in any order, normalising to ascending {@code AgentId}. */
    static StrategicDiversityKey of(List<AgentOpening> openings) {
        List<AgentOpening> ordered = new ArrayList<>(
                Objects.requireNonNull(openings, "Strategic openings must not be null"));
        ordered.sort(AGENT_ORDER);
        return new StrategicDiversityKey(ordered);
    }

    /** True while no PATROL agent has committed to a first target in this branch. */
    boolean uncommitted() {
        return openings.stream().allMatch(opening -> opening.firstTargetPosition() == NO_TARGET);
    }

    int committedAgents() {
        return (int) openings.stream()
                .filter(opening -> opening.firstTargetPosition() != NO_TARGET)
                .count();
    }

    @Override
    public int compareTo(StrategicDiversityKey other) {
        int shared = Math.min(openings.size(), other.openings.size());
        for (int index = 0; index < shared; index++) {
            int comparison = openings.get(index).compareTo(other.openings.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(openings.size(), other.openings.size());
    }

    /** One PATROL agent's committed opening target, or {@link #NO_TARGET}. */
    record AgentOpening(int patrolAgentId, int firstTargetPosition)
            implements Comparable<AgentOpening> {

        AgentOpening {
            if (patrolAgentId < 0) {
                throw new IllegalArgumentException("PATROL agent ID must be non-negative");
            }
            if (firstTargetPosition < NO_TARGET) {
                throw new IllegalArgumentException("First target position must be a position or NO_TARGET");
            }
        }

        static AgentOpening none(int patrolAgentId) {
            return new AgentOpening(patrolAgentId, NO_TARGET);
        }

        @Override
        public int compareTo(AgentOpening other) {
            int byAgent = Integer.compare(patrolAgentId, other.patrolAgentId);
            return byAgent != 0
                    ? byAgent
                    : Integer.compare(firstTargetPosition, other.firstTargetPosition);
        }
    }
}
