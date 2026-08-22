package vn.ptit.procon.planner;

/**
 * Immutable integer heuristic units for M12 commitment classifications.
 *
 * <p>These are ordinal structural tiers, not probabilities and not calibrated losses. An available
 * collection is worth the most; a follow-on claim before us costs a little; a direct claim before
 * us costs as much as an equal-step contest; only a hard claim before us is worth nothing.</p>
 */
public record CommitmentAdjustmentWeights(
        int likelyAvailableWeight,
        int unforecastedWeight,
        int followOnIntentBeforeWeight,
        int directIntentBeforeWeight,
        int contestedTieWeight,
        int hardClaimedFirstWeight) {

    public static final int DEFAULT_LIKELY_AVAILABLE_WEIGHT = 4;
    public static final int DEFAULT_UNFORECASTED_WEIGHT = 4;
    public static final int DEFAULT_FOLLOW_ON_INTENT_BEFORE_WEIGHT = 3;
    public static final int DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT = 2;
    public static final int DEFAULT_CONTESTED_TIE_WEIGHT = 2;
    public static final int DEFAULT_HARD_CLAIMED_FIRST_WEIGHT = 0;

    public CommitmentAdjustmentWeights {
        if (likelyAvailableWeight < 0 || unforecastedWeight < 0 || followOnIntentBeforeWeight < 0
                || directIntentBeforeWeight < 0 || contestedTieWeight < 0
                || hardClaimedFirstWeight < 0) {
            throw new IllegalArgumentException("Commitment adjustment weights must be non-negative");
        }
        if (likelyAvailableWeight == 0 && unforecastedWeight == 0 && followOnIntentBeforeWeight == 0
                && directIntentBeforeWeight == 0 && contestedTieWeight == 0
                && hardClaimedFirstWeight == 0) {
            throw new IllegalArgumentException("At least one commitment weight must be positive");
        }
    }

    public static CommitmentAdjustmentWeights defaults() {
        return new CommitmentAdjustmentWeights(
                DEFAULT_LIKELY_AVAILABLE_WEIGHT,
                DEFAULT_UNFORECASTED_WEIGHT,
                DEFAULT_FOLLOW_ON_INTENT_BEFORE_WEIGHT,
                DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT,
                DEFAULT_CONTESTED_TIE_WEIGHT,
                DEFAULT_HARD_CLAIMED_FIRST_WEIGHT);
    }

    public int weightFor(CommitmentCollectionClassification classification) {
        return switch (classification) {
            case LIKELY_AVAILABLE -> likelyAvailableWeight;
            case UNFORECASTED -> unforecastedWeight;
            case FOLLOW_ON_INTENT_BEFORE -> followOnIntentBeforeWeight;
            case DIRECT_INTENT_BEFORE -> directIntentBeforeWeight;
            case CONTESTED_TIE -> contestedTieWeight;
            case HARD_CLAIMED_FIRST -> hardClaimedFirstWeight;
        };
    }
}
