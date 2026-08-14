package vn.ptit.procon.runtime;

/** Supported autonomous strategy modes selected by process configuration. */
public enum PlannerMode {
    WAIT,
    BASELINE;

    static PlannerMode parse(String value) {
        String normalized = value == null || value.isBlank() ? WAIT.name() : value.trim().toUpperCase();
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "PROCON_PLANNER_MODE must be WAIT or BASELINE: " + value, exception);
        }
    }
}