package vn.ptit.procon.planner;

import java.util.Objects;

/** Immutable bounded opponent-intent forecast configuration. */
public record OpponentIntentConfig(
        int maxIntentTargetsPerAgent,
        OpponentAgentPolicy agentPolicy) {

    public static final int DEFAULT_MAX_INTENT_TARGETS_PER_AGENT = 3;

    public OpponentIntentConfig {
        if (maxIntentTargetsPerAgent <= 0 || maxIntentTargetsPerAgent > 3) {
            throw new IllegalArgumentException("Maximum intent targets per agent must be between 1 and 3");
        }
        Objects.requireNonNull(agentPolicy, "Opponent agent policy must not be null");
    }

    public static OpponentIntentConfig defaults() {
        return new OpponentIntentConfig(
                DEFAULT_MAX_INTENT_TARGETS_PER_AGENT,
                OpponentAgentPolicy.ALL_OBSERVED);
    }
}