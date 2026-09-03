package vn.ptit.procon.planner.v2;

/** One unique physical terminal captured at the Stage-A/Stage-B boundary. */
public record StageBRecallTerminal(
        int terminalId,
        String physicalSignature,
        int rootFamily,
        int supportServiceCount,
        int ownSemiBrands,
        int ownSemiCollections,
        int strategicDecisionCount,
        int movementCommandCount,
        int stageARank,
        int stageABrands,
        int stageACollections,
        int stageARawCollections,
        int stageAActivePatrols,
        int stageARemainingFuel,
        int stageAMovementSteps,
        String firstTargetAssignment,
        String endPositionVector,
        Integer coupledOwnCollections,
        Integer baselineOpponentCollections,
        Integer coupledOpponentCollections,
        Integer hybridOwnScore4,
        Integer hybridOpponentScore4,
        Integer hybridMarginScore4,
        boolean productionStageBSelected) {
}
