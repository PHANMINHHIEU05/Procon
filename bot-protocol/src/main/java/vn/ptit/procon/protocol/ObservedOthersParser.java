package vn.ptit.procon.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;

/** Best-effort parser for the live-observed {@code /state.others} value shape. */
public final class ObservedOthersParser {

    public List<ObservedOtherGroup> parse(JsonNode others) {
        if (others == null || !others.isArray()) {
            return List.of();
        }

        List<ObservedOtherGroup> groups = new ArrayList<>();
        for (JsonNode groupNode : others) {
            ObservedOtherGroup group = parseGroup(groupNode);
            if (group != null) {
                groups.add(group);
            }
        }
        groups.sort(Comparator.comparingInt(ObservedOtherGroup::rawId));
        return List.copyOf(groups);
    }

    private ObservedOtherGroup parseGroup(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Integer rawId = integralInt(node.get("id"));
        JsonNode agentsNode = node.get("agents");
        if (rawId == null || agentsNode == null || !agentsNode.isArray()) {
            return null;
        }

        List<ObservedOtherAgent> agents = new ArrayList<>();
        for (JsonNode agentNode : agentsNode) {
            ObservedOtherAgent agent = parseAgent(agentNode);
            if (agent != null) {
                agents.add(agent);
            }
        }
        return new ObservedOtherGroup(rawId, agents);
    }

    private ObservedOtherAgent parseAgent(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Integer position = integralInt(node.get("pos"));
        Integer rawKind = integralInt(node.get("kind"));
        Integer fuel = integralInt(node.get("fuel"));
        if (position == null || rawKind == null || fuel == null) {
            return null;
        }
        if (position < 0) {
            return null;
        }
        return new ObservedOtherAgent(new Position(position), rawKind, fuel);
    }

    private Integer integralInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt()
                ? node.intValue()
                : null;
    }
}