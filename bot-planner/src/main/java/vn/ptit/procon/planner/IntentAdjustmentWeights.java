package vn.ptit.procon.planner;

/** Immutable integer heuristic units for M10 collection classifications. */
public record IntentAdjustmentWeights(
        int likelyAvailableWeight,
        int contestedLaterWeight,
        int contestedTieWeight,
        int likelyClaimedFirstWeight,
        int unforecastedWeight) {

    public static final int DEFAULT_LIKELY_AVAILABLE_WEIGHT = 4;
    public static final int DEFAULT_CONTESTED_LATER_WEIGHT = 4;
    public static final int DEFAULT_CONTESTED_TIE_WEIGHT = 2;
    public static final int DEFAULT_LIKELY_CLAIMED_FIRST_WEIGHT = 0;
    public static final int DEFAULT_UNFORECASTED_WEIGHT = 4;

    public IntentAdjustmentWeights {
        if (likelyAvailableWeight < 0 || contestedLaterWeight < 0 || contestedTieWeight < 0
                || likelyClaimedFirstWeight < 0 || unforecastedWeight < 0) {
            throw new IllegalArgumentException("Intent adjustment weights must be non-negative");
        }
        if (likelyAvailableWeight == 0 && contestedLaterWeight == 0 && contestedTieWeight == 0
                && likelyClaimedFirstWeight == 0 && unforecastedWeight == 0) {
            throw new IllegalArgumentException("At least one intent adjustment weight must be positive");
        }
    }

    public static IntentAdjustmentWeights defaults() {
        return new IntentAdjustmentWeights(4, 4, 2, 0, 4);
    }

    public int weightFor(IntentCollectionClassification classification) {
        return switch (classification) {
            case LIKELY_AVAILABLE -> likelyAvailableWeight;
            case CONTESTED_LATER -> contestedLaterWeight;
            case CONTESTED_TIE -> contestedTieWeight;
            case LIKELY_CLAIMED_FIRST -> likelyClaimedFirstWeight;
            case UNFORECASTED -> unforecastedWeight;
        };
    }
}