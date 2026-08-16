package vn.ptit.procon.planner;

/** Search guidance variants sharing the same bounded anytime engine. */
public enum AnytimeSearchPolicy {
    ORIGINAL,
    HARVEST,
    CONTENTION,
    ANYTIME_ARRIVAL_CONTENTION,
    ANYTIME_WEIGHTED_ARRIVAL_CONTENTION,
    ANYTIME_RISK_ADJUSTED,
    ANYTIME_INTENT_AWARE
}
