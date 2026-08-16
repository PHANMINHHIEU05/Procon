package vn.ptit.procon.planner;

/** Planner-only comparison of one own collection event with forecast claims. */
public enum IntentCollectionClassification {
    LIKELY_AVAILABLE,
    CONTESTED_LATER,
    CONTESTED_TIE,
    LIKELY_CLAIMED_FIRST,
    UNFORECASTED
}