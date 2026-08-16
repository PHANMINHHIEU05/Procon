package vn.ptit.procon.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.protocol.ObservedOthersParser;

/** Bounded diagnostics for live-observed values without assigning protocol semantics. */
final class OthersValueObserver {

    private static final int MAX_LOGGED_GROUPS = 3;
    private static final int MAX_LOGGED_AGENTS_PER_GROUP = 4;
    private static final int MAX_LOGGED_KIND_VALUES = 16;

    private final boolean enabled;
    private final String matchId;
    private final ObservedOthersParser parser;
    private Map<Integer, Integer> previousAgentCounts = Map.of();
    private Map<Integer, Set<Integer>> previousPositions = Map.of();
    private boolean hasPrevious;

    OthersValueObserver(boolean enabled, String matchId) {
        this(enabled, matchId, new ObservedOthersParser());
    }

    OthersValueObserver(boolean enabled, String matchId, ObservedOthersParser parser) {
        this.enabled = enabled;
        this.matchId = matchId;
        this.parser = parser;
    }

    void observe(
            int day,
            JsonNode others,
            int mapSize,
            int ownPatrolFuelCapacity,
            Consumer<String> logger) {
        if (!enabled) {
            return;
        }

        List<ObservedOtherGroup> groups = parser.parse(others);
        Map<Integer, Integer> agentCounts = agentCounts(groups);
        Map<Integer, Set<Integer>> positions = positions(groups);
        logValues(day, groups, mapSize, ownPatrolFuelCapacity, logger);
        logKindValues(day, groups, logger);
        if (hasPrevious) {
            logStability(day, groups, agentCounts, logger);
            logPositionChanges(day, positions, logger);
        }
        previousAgentCounts = agentCounts;
        previousPositions = positions;
        hasPrevious = true;
    }

    private void logValues(
            int day,
            List<ObservedOtherGroup> groups,
            int mapSize,
            int ownPatrolFuelCapacity,
            Consumer<String> logger) {
        for (int groupIndex = 0; groupIndex < Math.min(groups.size(), MAX_LOGGED_GROUPS); groupIndex++) {
            ObservedOtherGroup group = groups.get(groupIndex);
            List<String> values = new ArrayList<>();
            List<ObservedOtherAgent> agents = group.agents();
            for (int agentIndex = 0;
                    agentIndex < Math.min(agents.size(), MAX_LOGGED_AGENTS_PER_GROUP);
                    agentIndex++) {
                ObservedOtherAgent agent = agents.get(agentIndex);
                boolean positionValid = agent.position().value() < mapSize;
                boolean fuelInOwnRange = agent.fuel() >= 0 && agent.fuel() <= ownPatrolFuelCapacity;
                values.add("{index=" + agentIndex
                        + ",pos=" + agent.position().value()
                        + ",rawKind=" + agent.rawKind()
                        + ",fuel=" + agent.fuel()
                        + ",positionValid=" + positionValid
                        + ",withinOwnPatrolFuelRange=" + fuelInOwnRange + "}");
            }
            logger.accept("OTHERS_VALUES matchId=" + matchId
                    + " day=" + day
                    + " group=" + groupIndex
                    + " rawId=" + group.rawId()
                    + " agents=" + agents.size()
                    + " values=[" + String.join(",", values) + "]");
        }
    }

    private void logKindValues(
            int day, List<ObservedOtherGroup> groups, Consumer<String> logger) {
        Set<Integer> kinds = groups.stream()
                .flatMap(group -> group.agents().stream())
                .map(ObservedOtherAgent::rawKind)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        List<Integer> boundedKinds = kinds.stream().limit(MAX_LOGGED_KIND_VALUES).toList();
        logger.accept("OTHERS_KIND_VALUES matchId=" + matchId
                + " day=" + day
                + " values=" + boundedKinds
                + " truncated=" + (kinds.size() > boundedKinds.size()));
    }

    private void logStability(
            int day,
            List<ObservedOtherGroup> groups,
            Map<Integer, Integer> agentCounts,
            Consumer<String> logger) {
        Set<Integer> rawIds = groups.stream()
                .map(ObservedOtherGroup::rawId)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        logger.accept("OTHERS_STABILITY matchId=" + matchId
                + " day=" + day
                + " outerCount=" + groups.size()
                + " sameRawIdsAsPrevious=" + rawIds.equals(previousAgentCounts.keySet())
                + " sameAgentCountsByRawId=" + agentCounts.equals(previousAgentCounts));
    }

    private void logPositionChanges(
            int day,
            Map<Integer, Set<Integer>> positions,
            Consumer<String> logger) {
        Set<Integer> rawIds = new LinkedHashSet<>();
        rawIds.addAll(previousPositions.keySet());
        rawIds.addAll(positions.keySet());
        List<Integer> orderedIds = rawIds.stream().sorted().limit(MAX_LOGGED_GROUPS).toList();
        for (Integer rawId : orderedIds) {
            Set<Integer> fullPrevious = previousPositions.getOrDefault(rawId, Set.of());
            Set<Integer> fullCurrent = positions.getOrDefault(rawId, Set.of());
            List<Integer> previous = sorted(fullPrevious);
            List<Integer> current = sorted(fullCurrent);
            logger.accept("OTHERS_POSITION_CHANGE matchId=" + matchId
                    + " day=" + day
                    + " rawId=" + rawId
                    + " previousPositions=" + previous
                    + " currentPositions=" + current
                    + " changed=" + !fullPrevious.equals(fullCurrent));
        }
    }

    private Map<Integer, Integer> agentCounts(List<ObservedOtherGroup> groups) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (ObservedOtherGroup group : groups) {
            counts.merge(group.rawId(), group.agents().size(), Integer::sum);
        }
        return Map.copyOf(counts);
    }

    private Map<Integer, Set<Integer>> positions(List<ObservedOtherGroup> groups) {
        Map<Integer, Set<Integer>> positions = new LinkedHashMap<>();
        for (ObservedOtherGroup group : groups) {
            positions.computeIfAbsent(group.rawId(), ignored -> new LinkedHashSet<>())
                    .addAll(group.agents().stream().map(agent -> agent.position().value()).toList());
        }
        Map<Integer, Set<Integer>> copied = new LinkedHashMap<>();
        positions.forEach((rawId, values) -> copied.put(rawId, Set.copyOf(values)));
        return Map.copyOf(copied);
    }

    private List<Integer> sorted(Set<Integer> values) {
        return values.stream()
                .sorted(Comparator.naturalOrder())
                .limit(MAX_LOGGED_AGENTS_PER_GROUP)
                .toList();
    }
}