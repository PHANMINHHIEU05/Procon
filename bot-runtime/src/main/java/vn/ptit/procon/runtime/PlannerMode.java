package vn.ptit.procon.runtime;

/** Supported autonomous strategy modes selected by process configuration. */
public enum PlannerMode {
    WAIT,
    BASELINE,
    BRAND_AWARE,
    REFUEL_AWARE,
    REFUEL_PROBE;

    static PlannerMode parse(String value) {
        String normalized = value == null || value.isBlank() ? WAIT.name() : value.trim().toUpperCase();
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "PROCON_PLANNER_MODE must be WAIT, BASELINE, BRAND_AWARE, REFUEL_AWARE,"
                            + " or REFUEL_PROBE: " + value,
                    exception);
        }
    }
}
