package vn.ptit.procon.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.agent.AgentId;

/** Immutable protocol-independent action sequences keyed by internal agent ID. */
public final class TeamPlan {

    private final Map<AgentId, List<AgentAction>> actionsByAgent;

    public TeamPlan(Map<AgentId, ? extends List<? extends AgentAction>> actionsByAgent) {
        Objects.requireNonNull(actionsByAgent, "Agent plans must not be null");
        List<AgentId> ids = new ArrayList<>(actionsByAgent.keySet());
        ids.sort(Comparator.comparingInt(AgentId::value));

        Map<AgentId, List<AgentAction>> copiedPlans = new LinkedHashMap<>();
        for (AgentId id : ids) {
            Objects.requireNonNull(id, "Plan agent ID must not be null");
            List<? extends AgentAction> sequence = Objects.requireNonNull(
                    actionsByAgent.get(id), "Agent action sequence must not be null");
            copiedPlans.put(id, List.copyOf(sequence));
        }
        this.actionsByAgent = Collections.unmodifiableMap(copiedPlans);
    }

    public Map<AgentId, List<AgentAction>> actionsByAgent() {
        return actionsByAgent;
    }

    public List<AgentAction> actionsFor(AgentId agentId) {
        return actionsByAgent.get(agentId);
    }
}