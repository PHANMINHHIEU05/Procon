package vn.ptit.procon.planner.v2;

/** Bounded target-menu policies used for competitive-opportunity ablation. */
public enum CompetitiveTargetPolicy {
    CURRENT,
    RACE_ONLY,
    RACE_PLUS_CONTINUATION,
    FINAL_FIXED;

    boolean usesRace() { return this != CURRENT; }
    boolean usesContinuation() { return this == RACE_PLUS_CONTINUATION || this == FINAL_FIXED; }
    boolean usesAllocation() { return this == FINAL_FIXED; }
}
