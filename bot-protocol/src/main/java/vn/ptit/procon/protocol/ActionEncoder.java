package vn.ptit.procon.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.engine.TeamPlan;

/** Encodes protocol-independent plans as the official ordered integer arrays. */
public final class ActionEncoder {

    public List<List<Integer>> encode(TeamPlan plan, int agentCount) {
        Objects.requireNonNull(plan, "Team plan must not be null");
        if (agentCount < 0) {
            throw new IllegalArgumentException("Agent count must be non-negative: " + agentCount);
        }
        if (plan.actionsByAgent().size() != agentCount) {
            throw new IllegalArgumentException(
                    "Team plan must contain exactly " + agentCount + " agent plans");
        }

        List<List<Integer>> payload = new ArrayList<>(agentCount);
        for (int index = 0; index < agentCount; index++) {
            List<AgentAction> actions = plan.actionsFor(new AgentId(index));
            if (actions == null) {
                throw new IllegalArgumentException("Missing plan for protocol agent index " + index);
            }
            payload.add(actions.stream().map(this::encodeAction).toList());
        }
        return List.copyOf(payload);
    }

    private int encodeAction(AgentAction action) {
        return switch (Objects.requireNonNull(action, "Action must not be null")) {
            case MoveAction move -> move.direction().code();
            case WaitAction wait -> Math.negateExact(wait.steps());
        };
    }
}