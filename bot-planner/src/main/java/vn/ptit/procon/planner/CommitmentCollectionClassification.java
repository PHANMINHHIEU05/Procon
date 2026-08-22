package vn.ptit.procon.planner;

/**
 * M12 risk class of one simulator-projected own collection event.
 *
 * <p>Exactly one class is assigned per collection event, so a spot facing many opponent claims
 * cannot accumulate several penalties for a single portion we projected to collect.</p>
 */
public enum CommitmentCollectionClassification {

    /** Hard forecast stock ran out before our arrival; only {@code OBSERVED_NOW} can cause this. */
    HARD_CLAIMED_FIRST,

    /** A {@code DIRECT_INTENT} claim arrives strictly before us while hard stock still remains. */
    DIRECT_INTENT_BEFORE,

    /** A {@code FOLLOW_ON_INTENT} claim arrives strictly before us and no direct claim does. */
    FOLLOW_ON_INTENT_BEFORE,

    /** An opponent claim of any commitment class arrives on the same step we do. */
    CONTESTED_TIE,

    /** The spot is forecast, but nothing is claimed before or with us. */
    LIKELY_AVAILABLE,

    /** No opponent intent source reaches this spot at all. */
    UNFORECASTED
}
