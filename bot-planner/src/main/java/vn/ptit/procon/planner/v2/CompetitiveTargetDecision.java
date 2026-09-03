package vn.ptit.procon.planner.v2;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;

/** One benchmark/test-visible target-menu decision. */
public record CompetitiveTargetDecision(
        AgentId agent,
        int candidateCountBeforeCap,
        java.util.List<CompetitiveTargetChoice> selected) {
    public CompetitiveTargetDecision {
        Objects.requireNonNull(agent, "Decision agent must not be null");
        selected = java.util.List.copyOf(Objects.requireNonNull(selected, "Selected targets must not be null"));
        if (candidateCountBeforeCap < selected.size()) {
            throw new IllegalArgumentException("Target decision count cannot be below selected count");
        }
    }
}
