package vn.ptit.procon.planner;

/**
 * Immutable integer heuristic tiers for M12.1 semi-commitment classifications.
 *
 * <p>Ordinal structural tiers, not probabilities, not percentages and not coefficients fitted to any
 * live match. An unclaimed collection is worth the most; a follow-on claim before us costs a little;
 * a direct claim that still leaves capacity costs as much as an equal-step contest; a bounded semi
 * reservation costs almost everything; only a hard claim before us is worth nothing.</p>
 *
 * <p>A semi claim scores one rather than zero because the bounded middle model predicts the portion
 * unavailable on weaker evidence than an {@code OBSERVED_NOW} loss: the opponent is forecast to
 * arrive there, not observed standing there. It must still rank below a direct conflict where
 * capacity survives, which is why it sits strictly under
 * {@link #DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT}.</p>
 */
public record SemiCommitmentAdjustmentWeights(
        int likelyAvailableWeight,
        int unforecastedWeight,
        int followOnIntentBeforeWeight,
        int directIntentBeforeWeight,
        int contestedTieWeight,
        int semiClaimedFirstWeight,
        int hardClaimedFirstWeight) {

    public static final int DEFAULT_LIKELY_AVAILABLE_WEIGHT = 4;
    public static final int DEFAULT_UNFORECASTED_WEIGHT = 4;
    public static final int DEFAULT_FOLLOW_ON_INTENT_BEFORE_WEIGHT = 3;
    public static final int DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT = 2;
    public static final int DEFAULT_CONTESTED_TIE_WEIGHT = 2;
    public static final int DEFAULT_SEMI_CLAIMED_FIRST_WEIGHT = 1;
    public static final int DEFAULT_HARD_CLAIMED_FIRST_WEIGHT = 0;

    public SemiCommitmentAdjustmentWeights {
        if (likelyAvailableWeight < 0 || unforecastedWeight < 0 || followOnIntentBeforeWeight < 0
                || directIntentBeforeWeight < 0 || contestedTieWeight < 0
                || semiClaimedFirstWeight < 0 || hardClaimedFirstWeight < 0) {
            throw new IllegalArgumentException(
                    "Semi-commitment adjustment weights must be non-negative");
        }
        if (likelyAvailableWeight == 0 && unforecastedWeight == 0 && followOnIntentBeforeWeight == 0
                && directIntentBeforeWeight == 0 && contestedTieWeight == 0
                && semiClaimedFirstWeight == 0 && hardClaimedFirstWeight == 0) {
            throw new IllegalArgumentException(
                    "At least one semi-commitment weight must be positive");
        }
    }

    public static SemiCommitmentAdjustmentWeights defaults() {
        return new SemiCommitmentAdjustmentWeights(
                DEFAULT_LIKELY_AVAILABLE_WEIGHT,
                DEFAULT_UNFORECASTED_WEIGHT,
                DEFAULT_FOLLOW_ON_INTENT_BEFORE_WEIGHT,
                DEFAULT_DIRECT_INTENT_BEFORE_WEIGHT,
                DEFAULT_CONTESTED_TIE_WEIGHT,
                DEFAULT_SEMI_CLAIMED_FIRST_WEIGHT,
                DEFAULT_HARD_CLAIMED_FIRST_WEIGHT);
    }

    public int weightFor(SemiCommitmentCollectionClassification classification) {
        return switch (classification) {
            case LIKELY_AVAILABLE -> likelyAvailableWeight;
            case UNFORECASTED -> unforecastedWeight;
            case FOLLOW_ON_INTENT_BEFORE -> followOnIntentBeforeWeight;
            case DIRECT_INTENT_BEFORE -> directIntentBeforeWeight;
            case CONTESTED_TIE -> contestedTieWeight;
            case SEMI_CLAIMED_FIRST -> semiClaimedFirstWeight;
            case HARD_CLAIMED_FIRST -> hardClaimedFirstWeight;
        };
    }
}
