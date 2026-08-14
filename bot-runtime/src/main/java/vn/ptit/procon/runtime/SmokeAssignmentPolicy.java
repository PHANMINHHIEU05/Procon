package vn.ptit.procon.runtime;

import java.util.ArrayList;
import java.util.List;
import vn.ptit.procon.domain.agent.AgentKind;

/** Deterministic smoke-only assignment; this is not a competitive planner. */
public final class SmokeAssignmentPolicy {

    public List<AgentKind> assignmentFor(int agentCount) {
        if (agentCount <= 0) {
            throw new IllegalArgumentException("Setup must contain at least one agent");
        }
        List<AgentKind> result = new ArrayList<>(agentCount);
        for (int index = 0; index < agentCount; index++) {
            result.add(agentCount > 1 && index == agentCount - 1
                    ? AgentKind.REFUEL
                    : AgentKind.PATROL);
        }
        return List.copyOf(result);
    }
}