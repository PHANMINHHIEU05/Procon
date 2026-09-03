package vn.ptit.procon.planner.v2;

/** Fully evaluated terminal values used by the benchmark-only objective audit. */
public record TerminalObjectiveRow(
        int stageARank,
        String physicalSignature,
        int rootFamily,
        int supportServiceCount,
        int ownSemiBrands,
        int ownSemiCollections,
        int coupledOwnCollections,
        int baselineOpponentCollections,
        int coupledOpponentCollections,
        int currentHybridMarginScore4,
        int coupledMargin,
        boolean productionSelected) {
}
