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
    ANYTIME_INTENT_AWARE,
    ANYTIME_DIVERSE_INTENT_AWARE,
    ANYTIME_STRATIFIED_INTENT_AWARE,
    ANYTIME_STRATIFIED_COMMITMENT_AWARE,
    ANYTIME_STRATIFIED_SEMI_COMMITMENT_AWARE,
    ANYTIME_STRATIFIED_SEMI_COMMITMENT_HORIZON_AWARE,
    ANYTIME_STRATIFIED_SEMI_COMMITMENT_HARVEST_HORIZON_AWARE,
    ANYTIME_STRATIFIED_RELATIVE_MARGIN_AWARE,
    ANYTIME_STRATIFIED_REPLACEMENT_AWARE_RELATIVE_MARGIN,
    ANYTIME_STRATIFIED_COUPLED_COMPETITIVE_MARGIN,
    ANYTIME_STRATIFIED_HYBRID_CALIBRATED_MARGIN,
    ANYTIME_STRATIFIED_HYBRID_DIVERSE_CANDIDATES,
    ANYTIME_STRATIFIED_TEAM_ALLOCATED_HYBRID,
    ANYTIME_STRATIFIED_CAPACITY_COMPETITIVE,
    JOINT_TEAM_BEAM_V2,
    JOINT_TEAM_BEAM_V2_R3;

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
                            + " ANYTIME_INTENT_AWARE, ANYTIME_DIVERSE_INTENT_AWARE,"
                            + " ANYTIME_STRATIFIED_INTENT_AWARE,"
                            + " ANYTIME_STRATIFIED_COMMITMENT_AWARE, or"
                            + " ANYTIME_STRATIFIED_SEMI_COMMITMENT_AWARE, or"
                            + " ANYTIME_STRATIFIED_SEMI_COMMITMENT_HORIZON_AWARE, or"
                            + " ANYTIME_STRATIFIED_SEMI_COMMITMENT_HARVEST_HORIZON_AWARE, or"
                            + " ANYTIME_STRATIFIED_RELATIVE_MARGIN_AWARE, or"
                            + " ANYTIME_STRATIFIED_REPLACEMENT_AWARE_RELATIVE_MARGIN, or"
                            + " ANYTIME_STRATIFIED_COUPLED_COMPETITIVE_MARGIN, or"
                            + " ANYTIME_STRATIFIED_HYBRID_CALIBRATED_MARGIN, or"
                            + " ANYTIME_STRATIFIED_HYBRID_DIVERSE_CANDIDATES, or"
                            + " ANYTIME_STRATIFIED_TEAM_ALLOCATED_HYBRID, or"
                            + " ANYTIME_STRATIFIED_CAPACITY_COMPETITIVE, or"
                            + " JOINT_TEAM_BEAM_V2, or JOINT_TEAM_BEAM_V2_R3: " + value,
                    exception);
        }
    }
}
