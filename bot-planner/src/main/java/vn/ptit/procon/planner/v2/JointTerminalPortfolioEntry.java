package vn.ptit.procon.planner.v2;

/** A compact audit row for one of the selected full terminal evaluations. */
public record JointTerminalPortfolioEntry(
        int rank,
        int ownSemiBrands,
        int ownSemiCollections,
        int coupledOwnCollections,
        int baselineOpponentCollections,
        int coupledOpponentCollections,
        int hybridMarginScore4,
        int strategicDecisionCount,
        int movementCommandCount,
        String signature) {
}
