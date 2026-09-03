package vn.ptit.procon.planner.v2;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small deterministic calculations shared by the planner's recall sidecar. */
final class StageBRecallAuditSupport {
    static final Comparator<StageBRecallTerminal> EVALUATION_ORDER = (left, right) -> {
        int compared = Integer.compare(value(right.hybridMarginScore4()), value(left.hybridMarginScore4()));
        if (compared != 0) return compared;
        compared = Integer.compare(value(right.hybridOwnScore4()), value(left.hybridOwnScore4()));
        if (compared != 0) return compared;
        compared = Integer.compare(value(left.hybridOpponentScore4()), value(right.hybridOpponentScore4()));
        if (compared != 0) return compared;
        return left.physicalSignature().compareTo(right.physicalSignature());
    };

    private StageBRecallAuditSupport() { }

    static StageBRecallAudit build(StageBRecallAuditMode mode, List<StageBRecallTerminal> terminals,
            List<StageBRecallTerminal> oracleOrder,
            StageBRecallTerminal productionWinner, StageBRecallTerminal exhaustiveWinner,
            int productionEvaluated, int productionK, int exhaustiveEvaluated, int hybridGap,
            int semiGap, int coupledGap, int top1, int top3, int top5, boolean complete, long millis) {
        List<StageBRecallTerminal> oracle = List.copyOf(oracleOrder);
        Map<Integer, Integer> familyCounts = new LinkedHashMap<>();
        for (StageBRecallTerminal terminal : terminals) {
            if (terminal.productionStageBSelected()) familyCounts.merge(terminal.rootFamily(), 1, Integer::sum);
        }
        Map<Integer, Integer> exhaustiveFamily = bestByFamily(oracle);
        Map<Integer, Integer> productionFamily = bestByFamily(terminals.stream()
                .filter(StageBRecallTerminal::productionStageBSelected).toList());
        Map<Integer, Boolean> familyPresent = new LinkedHashMap<>();
        for (Integer family : exhaustiveFamily.keySet()) {
            familyPresent.put(family, productionFamily.containsKey(family));
        }
        List<StageBRecallTerminal> topFive = oracle.stream().limit(5).toList();
        List<Integer> topRanks = topFive.stream().map(StageBRecallTerminal::stageARank).sorted().toList();
        int median = topRanks.isEmpty() ? -1 : topRanks.get(topRanks.size() / 2);
        int winnerFirst = exhaustiveWinner.stageARank();
        int productionUniqueFamilies = (int) terminals.stream()
                .filter(StageBRecallTerminal::productionStageBSelected)
                .map(StageBRecallTerminal::rootFamily).distinct().count();
        int productionUniqueServices = (int) terminals.stream()
                .filter(StageBRecallTerminal::productionStageBSelected)
                .map(StageBRecallTerminal::supportServiceCount).distinct().count();
        int productionUniquePhysical = (int) terminals.stream()
                .filter(StageBRecallTerminal::productionStageBSelected)
                .map(StageBRecallTerminal::physicalSignature).distinct().count();
        int productionUniqueFirstTargets = (int) terminals.stream()
                .filter(StageBRecallTerminal::productionStageBSelected)
                .map(StageBRecallTerminal::firstTargetAssignment).distinct().count();
        int productionUniqueEndPositions = (int) terminals.stream()
                .filter(StageBRecallTerminal::productionStageBSelected)
                .map(StageBRecallTerminal::endPositionVector).distinct().count();
        int nearDuplicates = terminals.stream()
                .filter(StageBRecallTerminal::productionStageBSelected)
                .map(value -> value.firstTargetAssignment() + "|" + value.endPositionVector())
                .collect(java.util.stream.Collectors.groupingBy(value -> value, LinkedHashMap::new,
                        java.util.stream.Collectors.counting())).values().stream()
                .mapToInt(value -> (int) Math.max(0, value - 1)).sum();
        return new StageBRecallAudit(mode, terminals.size(), productionK, productionEvaluated,
                exhaustiveEvaluated, productionWinner, exhaustiveWinner,
                terminals.stream().anyMatch(value -> value.productionStageBSelected()
                        && value.physicalSignature().equals(exhaustiveWinner.physicalSignature())),
                exhaustiveWinner.physicalSignature().equals(productionWinner.physicalSignature()), hybridGap,
                semiGap, coupledGap, top1, top3, top5,
                topFive.stream().mapToInt(StageBRecallTerminal::stageARank).min().orElse(-1), median,
                topFive.stream().mapToInt(StageBRecallTerminal::stageARank).max().orElse(-1),
                bestAt(oracle, 8), bestAt(oracle, 16), bestAt(oracle, 24), bestAt(oracle, 32),
                bestAt(oracle, oracle.size()), winnerFirst, familyCounts, exhaustiveFamily, productionFamily,
                familyPresent,
                productionUniqueFamilies, productionUniqueServices, productionUniqueFirstTargets,
                productionUniqueEndPositions, productionUniquePhysical, nearDuplicates, millis, complete, terminals,
                oracle);
    }

    private static Map<Integer, Integer> bestByFamily(List<StageBRecallTerminal> terminals) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (StageBRecallTerminal terminal : terminals) {
            if (terminal.hybridMarginScore4() == null) continue;
            result.merge(terminal.rootFamily(), terminal.hybridMarginScore4(), Math::max);
        }
        return result;
    }

    private static int bestAt(List<StageBRecallTerminal> oracle, int limit) {
        return oracle.stream().limit(limit).map(StageBRecallTerminal::hybridMarginScore4)
                .filter(value -> value != null).mapToInt(Integer::intValue).max().orElse(-1);
    }

    private static int value(Integer value) { return value == null ? Integer.MIN_VALUE : value; }
}
