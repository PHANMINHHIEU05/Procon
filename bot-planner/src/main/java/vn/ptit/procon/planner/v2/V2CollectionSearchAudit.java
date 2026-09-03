package vn.ptit.procon.planner.v2;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Collection-lifecycle evidence. Empty outside the explicit offline TRACE profile. */
public record V2CollectionSearchAudit(
        V2CollectionAuditMode mode,
        List<V2CollectionStateTrace> states,
        int maxSecuredCollectionsSeen,
        String highWaterStateId,
        int highWaterDepth,
        String highWaterRootFamily,
        List<Integer> highWaterAgentPositions,
        List<Integer> highWaterElapsedSteps,
        int highWaterRemainingFuel,
        String highWaterRemainingStock,
        boolean highWaterCanStillCompleteLegally,
        boolean highWaterEventuallyMaterialized,
        Integer highWaterEventualTerminalSemiCollections,
        String highWaterRejectionOrLossReason,
        int totalReachableTargetCandidates,
        int candidatesBeforeTruncation,
        int candidatesAfterConfiguredTopK,
        int targetTruncationCount,
        int stopStatesExpanded,
        int stopOnlyExpansionCount,
        int expansionsAfterPotentialZero,
        int collectionsGainedAfterPotentialZero,
        int higherCollectionStateLostToDedup,
        int securedCollectionConservationViolations,
        int bestTerminalOwnSemiCollections,
        String bestTerminalOwnSignature,
        int selectedTerminalSemiCollections,
        String selectedTerminalSignature) {

    public V2CollectionSearchAudit {
        mode = mode == null ? V2CollectionAuditMode.OFF : mode;
        states = List.copyOf(states);
        highWaterStateId = highWaterStateId == null ? "NONE" : highWaterStateId;
        highWaterRootFamily = highWaterRootFamily == null ? "NONE" : highWaterRootFamily;
        highWaterAgentPositions = List.copyOf(highWaterAgentPositions);
        highWaterElapsedSteps = List.copyOf(highWaterElapsedSteps);
        highWaterRemainingStock = highWaterRemainingStock == null ? "{}" : highWaterRemainingStock;
        highWaterRejectionOrLossReason = highWaterRejectionOrLossReason == null ? "NONE"
                : highWaterRejectionOrLossReason;
        bestTerminalOwnSignature = bestTerminalOwnSignature == null ? "NONE" : bestTerminalOwnSignature;
        selectedTerminalSignature = selectedTerminalSignature == null ? "NONE" : selectedTerminalSignature;
    }

    public static V2CollectionSearchAudit empty() {
        return new V2CollectionSearchAudit(V2CollectionAuditMode.OFF, List.of(), 0, "NONE", 0, "NONE", List.of(), List.of(),
                0, "{}", false, false, null, "NONE", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "NONE", 0, "NONE");
    }

    /** Compare the selected trace's decisions with the configured menu recorded by another run. */
    public double winningTargetRecallAgainst(V2CollectionSearchAudit production) {
        Map<String, V2CollectionStateTrace> reference = production.states.stream()
                .collect(Collectors.toMap(V2CollectionStateTrace::exactKey, Function.identity(),
                        (left, right) -> left));
        Map<String, V2CollectionStateTrace> selectedById = states.stream()
                .collect(Collectors.toMap(V2CollectionStateTrace::stateId, Function.identity(),
                        (left, right) -> left));
        V2CollectionStateTrace current = states.stream()
                .filter(value -> value.terminalSignature().equals(selectedTerminalSignature))
                .findFirst().orElse(null);
        int total = 0;
        int present = 0;
        while (current != null && !current.parentStateId().isEmpty()
                && !current.parentStateId().equals("ROOT")) {
            total++;
            V2CollectionStateTrace parent = selectedById.get(current.parentStateId());
            if (parent != null && productionMenuContains(reference.get(parent.exactKey()),
                    current.agentExpanded(), current.agentPositions(), production)) present++;
            current = parent;
        }
        return total == 0 ? 1.0 : ((double) present / total);
    }

    private static boolean productionMenuContains(V2CollectionStateTrace parent, int agent,
            List<Integer> childPositions, V2CollectionSearchAudit ignored) {
        if (parent == null || agent < 0 || agent >= childPositions.size()) return false;
        String prefix = agent + ":";
        return java.util.Arrays.stream(parent.targetMenu().split("\\|"))
                .filter(value -> value.startsWith(prefix)).findFirst()
                .map(value -> java.util.Arrays.asList(value.substring(prefix.length()).split(","))
                        .contains(String.valueOf(childPositions.get(agent))))
                .orElse(false);
    }
}
