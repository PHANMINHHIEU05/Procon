package vn.ptit.procon.engine;

/** Stable classifications for plans rejected by the local execution engine. */
public enum SimulationFailureCode {
    INVALID_STATE,
    MISSING_AGENT_PLAN,
    UNKNOWN_AGENT,
    NOT_ADJACENT,
    POND_DESTINATION,
    IMPASSABLE_SOURCE,
    STEP_OVERFLOW,
    NO_FUEL,
    MISSING_TRAFFIC
}