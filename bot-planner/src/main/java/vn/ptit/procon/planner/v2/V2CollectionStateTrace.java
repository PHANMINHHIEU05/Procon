package vn.ptit.procon.planner.v2;

import java.util.List;

/** Immutable offline trace row for one strategically important search state. */
public record V2CollectionStateTrace(
        String stateId,
        String parentStateId,
        int depth,
        String rootFamily,
        int agentExpanded,
        int securedCollections,
        int securedBrands,
        int optimisticPotentialCollections,
        int remainingStepCapacity,
        int remainingFuelCapacity,
        int nextTargetsConsidered,
        int generatedChildren,
        boolean admitted,
        String rejectedReason,
        String dedupedIntoStateId,
        boolean prunedByBeam,
        boolean materializedTerminal,
        Integer terminalOwnSemiCollections,
        Integer terminalHybridMarginScore4,
        List<Integer> agentPositions,
        String remainingStock,
        String exactKey,
        String targetMenu,
        String terminalSignature) {

    public V2CollectionStateTrace {
        stateId = stateId == null ? "UNKNOWN" : stateId;
        parentStateId = parentStateId == null ? "ROOT" : parentStateId;
        rejectedReason = rejectedReason == null ? "NONE" : rejectedReason;
        agentPositions = List.copyOf(agentPositions);
        remainingStock = remainingStock == null ? "{}" : remainingStock;
        exactKey = exactKey == null ? "" : exactKey;
        targetMenu = targetMenu == null ? "" : targetMenu;
        terminalSignature = terminalSignature == null ? "" : terminalSignature;
    }
}
