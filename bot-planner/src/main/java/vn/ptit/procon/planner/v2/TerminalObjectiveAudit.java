package vn.ptit.procon.planner.v2;

import java.util.List;
import java.util.Map;

/** Audit-only decomposition of the frozen terminal objective. */
public record TerminalObjectiveAudit(
        StageBRecallAuditMode mode,
        int terminalCount,
        int paretoCount,
        boolean productionWinnerOnParetoFrontier,
        boolean productionWinnerDominated,
        String productionWinnerSignature,
        String exhaustiveWinnerSignature,
        String coupledOracleSignature,
        int productionWinnerParetoRank,
        int productionWinnerOwnSemiBrands,
        int exhaustiveWinnerOwnSemiBrands,
        int productionOwnSemiCollections,
        int productionCoupledOwnCollections,
        int productionBaselineOpponentCollections,
        int productionCoupledOpponentCollections,
        int productionCoupledMargin,
        int coupledOracleMargin,
        int selectionRegret,
        int ownCalibrationError,
        int opponentCalibrationError,
        int marginCalibrationError,
        int weightConfigurationCount,
        double productionWinnerStability,
        int distinctWeightWinners,
        String mostFrequentWeightWinner,
        Map<String, String> weightWinnerSignatures,
        Map<String, String> alternativeObjectiveWinners,
        List<TerminalObjectiveRow> terminals) {

    public TerminalObjectiveAudit {
        if (mode == null) throw new IllegalArgumentException("Audit mode must not be null");
        if (terminalCount < 0 || paretoCount < 0 || weightConfigurationCount < 0) {
            throw new IllegalArgumentException("Audit counts must not be negative");
        }
        if (productionWinnerSignature == null || exhaustiveWinnerSignature == null || coupledOracleSignature == null) {
            throw new IllegalArgumentException("Audit signatures must not be null");
        }
        weightWinnerSignatures = Map.copyOf(weightWinnerSignatures);
        alternativeObjectiveWinners = Map.copyOf(alternativeObjectiveWinners);
        terminals = List.copyOf(terminals);
    }

    public static TerminalObjectiveAudit empty(StageBRecallAuditMode mode) {
        return new TerminalObjectiveAudit(mode, 0, 0, false, false, "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE",
                -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0.0, 0, "UNAVAILABLE",
                Map.of(), Map.of(), List.of());
    }
}
