package vn.ptit.procon.runtime;

/** Supported autonomous strategy modes selected by process configuration. */
public enum PlannerMode {
    WAIT,
    BASELINE,
    BRAND_AWARE,
    REFUEL_AWARE,
    REFUEL_PROBE,
    TEAM_COORDINATED,
    ANYTIME,
    ANYTIME_HARVEST,
    ANYTIME_CONTENTION,
    ANYTIME_ARRIVAL_CONTENTION,
    ANYTIME_WEIGHTED_ARRIVAL_CONTENTION,
    ANYTIME_RISK_ADJUSTED,
    ANYTIME_INTENT_AWARE;

    static PlannerMode parse(String value) {
        String normalized = value == null || value.isBlank() ? WAIT.name() : value.trim().toUpperCase();
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "PROCON_PLANNER_MODE must be WAIT, BASELINE, BRAND_AWARE, REFUEL_AWARE,"
                            + " REFUEL_PROBE, TEAM_COORDINATED, ANYTIME, ANYTIME_HARVEST,"
                            + " ANYTIME_CONTENTION, ANYTIME_ARRIVAL_CONTENTION,"
                            + " ANYTIME_WEIGHTED_ARRIVAL_CONTENTION, ANYTIME_RISK_ADJUSTED,"
                            + " or ANYTIME_INTENT_AWARE: " + value,
                    exception);
        }
    }
}
