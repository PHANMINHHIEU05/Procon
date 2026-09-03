package vn.ptit.procon.planner.v2;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure, deterministic calculations for the terminal-objective audit. */
final class TerminalObjectiveAuditSupport {
    private TerminalObjectiveAuditSupport() { }

    static TerminalObjectiveAudit build(StageBRecallAuditMode mode, List<StageBRecallTerminal> stageAOrder,
            List<StageBRecallTerminal> exhaustiveOrder, StageBRecallTerminal production,
            StageBRecallTerminal exhaustive) {
        if (mode == StageBRecallAuditMode.OFF || exhaustiveOrder.isEmpty()) return TerminalObjectiveAudit.empty(mode);
        List<StageBRecallTerminal> terminals = exhaustiveOrder.stream()
                .filter(value -> value.coupledOwnCollections() != null)
                .toList();
        List<StageBRecallTerminal> pareto = terminals.stream().filter(candidate -> terminals.stream()
                .noneMatch(other -> other != candidate && dominates(other, candidate))).toList();
        int productionRank = indexOf(pareto, production.physicalSignature());
        StageBRecallTerminal coupledOracle = terminals.stream().max(Comparator
                .comparingInt(TerminalObjectiveAuditSupport::coupledMargin)
                .thenComparingInt(StageBRecallTerminal::coupledOwnCollections)
                .thenComparing(StageBRecallTerminal::physicalSignature, Comparator.reverseOrder())).orElse(exhaustive);
        Map<String, String> weightWinners = new LinkedHashMap<>();
        for (int ownWeight = 1; ownWeight <= 5; ownWeight++) {
            for (int opponentWeight = 1; opponentWeight <= 5; opponentWeight++) {
                StageBRecallTerminal winner = terminals.stream().max(weightComparator(ownWeight, opponentWeight)).orElse(exhaustive);
                weightWinners.put(ownWeight + "_1__" + opponentWeight + "_1", winner.physicalSignature());
            }
        }
        Map<String, String> alternatives = new LinkedHashMap<>();
        alternatives.put("CURRENT_3_1", exhaustive.physicalSignature());
        alternatives.put("SEMI_2_1", winnerFor(weights(2, 1), terminals));
        alternatives.put("SEMI_4_1", winnerFor(weights(4, 1), terminals));
        alternatives.put("PURE_COUPLED", winnerFor((value -> coupledMargin(value)), terminals));
        alternatives.put("ROBUST_MIN", terminals.stream().max(Comparator
                .comparingInt((StageBRecallTerminal value) -> Math.min(value.ownSemiCollections(), value.coupledOwnCollections())
                        - Math.max(value.baselineOpponentCollections(), value.coupledOpponentCollections()))
                .thenComparing(StageBRecallTerminal::physicalSignature, Comparator.reverseOrder())).orElse(exhaustive).physicalSignature());
        long stable = weightWinners.values().stream().filter(production.physicalSignature()::equals).count();
        Map<String, Long> frequencies = weightWinners.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(value -> value, LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        String frequent = frequencies.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).map(Map.Entry::getKey).orElse("UNAVAILABLE");
        List<TerminalObjectiveRow> rows = terminals.stream().map(value -> new TerminalObjectiveRow(
                value.stageARank(), value.physicalSignature(), value.rootFamily(), value.supportServiceCount(),
                value.ownSemiBrands(), value.ownSemiCollections(), value.coupledOwnCollections(),
                value.baselineOpponentCollections(), value.coupledOpponentCollections(), value.hybridMarginScore4(),
                coupledMargin(value), value.productionStageBSelected())).toList();
        int productionCoupledMargin = coupledMargin(production);
        int oracleMargin = coupledMargin(coupledOracle);
        int productionMarginPrediction = production.hybridMarginScore4();
        return new TerminalObjectiveAudit(mode, terminals.size(), pareto.size(), productionRank >= 0,
                productionRank < 0, production.physicalSignature(), exhaustive.physicalSignature(),
                coupledOracle.physicalSignature(), productionRank < 0 ? -1 : productionRank + 1,
                production.ownSemiBrands(), exhaustive.ownSemiBrands(), production.ownSemiCollections(),
                production.coupledOwnCollections(), production.baselineOpponentCollections(),
                production.coupledOpponentCollections(), productionCoupledMargin, oracleMargin,
                oracleMargin - productionCoupledMargin,
                production.ownSemiCollections() - production.coupledOwnCollections(),
                production.baselineOpponentCollections() - production.coupledOpponentCollections(),
                productionMarginPrediction - productionCoupledMargin, weightWinners.size(), stable / 25.0,
                (int) frequencies.size(), frequent, weightWinners, alternatives, rows);
    }

    private static boolean dominates(StageBRecallTerminal left, StageBRecallTerminal right) {
        boolean noWorse = left.ownSemiCollections() >= right.ownSemiCollections()
                && left.coupledOwnCollections() >= right.coupledOwnCollections()
                && left.baselineOpponentCollections() <= right.baselineOpponentCollections()
                && left.coupledOpponentCollections() <= right.coupledOpponentCollections();
        boolean strictly = left.ownSemiCollections() > right.ownSemiCollections()
                || left.coupledOwnCollections() > right.coupledOwnCollections()
                || left.baselineOpponentCollections() < right.baselineOpponentCollections()
                || left.coupledOpponentCollections() < right.coupledOpponentCollections();
        return noWorse && strictly;
    }

    private static int indexOf(List<StageBRecallTerminal> values, String signature) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).physicalSignature().equals(signature)) return index;
        }
        return -1;
    }

    private static Comparator<StageBRecallTerminal> weightComparator(int ownWeight, int opponentWeight) {
        return Comparator.comparingInt((StageBRecallTerminal value) ->
                ownWeight * value.ownSemiCollections() + value.coupledOwnCollections()
                        - opponentWeight * value.baselineOpponentCollections() - value.coupledOpponentCollections())
                .thenComparing(StageBRecallTerminal::physicalSignature, Comparator.reverseOrder());
    }

    private static java.util.function.ToIntFunction<StageBRecallTerminal> weights(int ownWeight, int opponentWeight) {
        return value -> ownWeight * value.ownSemiCollections() + value.coupledOwnCollections()
                - opponentWeight * value.baselineOpponentCollections() - value.coupledOpponentCollections();
    }

    private static String winnerFor(java.util.function.ToIntFunction<StageBRecallTerminal> score,
            List<StageBRecallTerminal> terminals) {
        return terminals.stream().max(Comparator.comparingInt(score).thenComparing(
                StageBRecallTerminal::physicalSignature, Comparator.reverseOrder())).orElseThrow().physicalSignature();
    }

    private static int coupledMargin(StageBRecallTerminal value) {
        return value.coupledOwnCollections() - value.coupledOpponentCollections();
    }
}
