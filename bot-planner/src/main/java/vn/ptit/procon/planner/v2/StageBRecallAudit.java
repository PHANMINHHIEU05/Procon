package vn.ptit.procon.planner.v2;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Benchmark/test-only comparison between production K16 and an exhaustive coupled oracle. */
public record StageBRecallAudit(
        StageBRecallAuditMode mode,
        int uniquePhysicalTerminalCount,
        int productionStageBK,
        int productionEvaluatedCount,
        int exhaustiveEvaluatedCount,
        StageBRecallTerminal productionWinner,
        StageBRecallTerminal exhaustiveWinner,
        boolean exhaustiveWinnerPresentInK16,
        boolean samePhysicalWinner,
        int hybridGapScore4,
        int ownSemiGap,
        int coupledOwnGap,
        int top1Recall,
        int top3Recall,
        int top5Recall,
        int bestCoupledStageARank,
        int medianStageARankOfTop5Coupled,
        int worstStageARankOfTop5Coupled,
        int k8BestHybrid,
        int k16BestHybrid,
        int k24BestHybrid,
        int k32BestHybrid,
        int exhaustiveBestHybrid,
        int winnerFirstAppearsAt,
        Map<Integer, Integer> productionFamilyCounts,
        Map<Integer, Integer> exhaustiveBestHybridByFamily,
        Map<Integer, Integer> productionBestHybridByFamily,
        Map<Integer, Boolean> familyBestPresentInProduction,
        int productionUniqueRootFamilies,
        int productionUniqueServiceCounts,
        int productionUniqueFirstTargetAssignments,
        int productionUniqueEndPositionVectors,
        int productionUniquePhysicalSignatures,
        int productionDuplicateOrNearDuplicateCount,
        long exhaustiveEvaluationMillis,
        boolean exhaustiveComplete,
        List<StageBRecallTerminal> terminals,
        List<StageBRecallTerminal> exhaustiveOrder) {

    public StageBRecallAudit {
        Objects.requireNonNull(mode, "Audit mode must not be null");
        Objects.requireNonNull(productionWinner, "Production winner must not be null");
        Objects.requireNonNull(exhaustiveWinner, "Exhaustive winner must not be null");
        productionFamilyCounts = Map.copyOf(productionFamilyCounts);
        exhaustiveBestHybridByFamily = Map.copyOf(exhaustiveBestHybridByFamily);
        productionBestHybridByFamily = Map.copyOf(productionBestHybridByFamily);
        familyBestPresentInProduction = Map.copyOf(familyBestPresentInProduction);
        terminals = List.copyOf(terminals);
        exhaustiveOrder = List.copyOf(exhaustiveOrder);
    }

    public static StageBRecallAudit empty(StageBRecallAuditMode mode) {
        StageBRecallTerminal unavailable = new StageBRecallTerminal(0, "UNAVAILABLE", 0, 0,
                -1, -1, 0, 0, -1, -1, -1, -1, -1, -1, -1, "UNAVAILABLE", "UNAVAILABLE",
                null, null, null, null, null, null, false);
        return new StageBRecallAudit(mode, 0, 0, 0, 0, unavailable, unavailable,
                false, false, 0, 0, 0, 0, 0, 0, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 0, 0, 0, true, List.of(), List.of());
    }
}
