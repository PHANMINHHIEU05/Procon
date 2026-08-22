package vn.ptit.procon.planner;

/**
 * M12.1 interpretation of one projected own collection under the bounded semi-reservation model.
 *
 * <p>Deliberately a separate enum from {@link CommitmentCollectionClassification}: M12 must stay
 * byte-for-byte what it was, and {@link CommitmentAdjustmentWeights#weightFor} switches
 * exhaustively over the M12 classes. Extending that enum would have silently changed M12.</p>
 *
 * <p>Precedence when several descriptions apply is exactly the declaration order below:
 * hard depletion first, then the bounded direct reservation, then the ordering-only risk classes.
 * Equal-step opponent claims never reach a {@code *_BEFORE} class; they stay
 * {@link #CONTESTED_TIE}.</p>
 */
public enum SemiCommitmentCollectionClassification {

    /** An {@code OBSERVED_NOW} opponent consumed the last portion strictly before us. */
    HARD_CLAIMED_FIRST,

    /**
     * The collection survives hard depletion, but the one bounded {@code DIRECT_INTENT} reservation
     * at this spot consumes the last relevant portion before us.
     */
    SEMI_CLAIMED_FIRST,

    /**
     * A {@code DIRECT_INTENT} claim arrives strictly before us, but enough capacity remains and the
     * collection is still semi-realizable.
     */
    DIRECT_INTENT_BEFORE,

    /** Only later hypothetical continuations arrive before us; they never reserve stock. */
    FOLLOW_ON_INTENT_BEFORE,

    /** An opponent claim arrives at exactly our collection step; no reservation is made. */
    CONTESTED_TIE,

    /** Claims exist at this spot but none of them affects our collection. */
    LIKELY_AVAILABLE,

    /** The forecast produced no claim at all for this spot. */
    UNFORECASTED
}
