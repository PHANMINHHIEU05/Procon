package vn.ptit.procon.domain.agent;

/** Stable internal bot identity for an agent. */
public record AgentId(int value) {

    public AgentId {
        if (value < 0) {
            throw new IllegalArgumentException("Agent ID must be non-negative: " + value);
        }
    }
}