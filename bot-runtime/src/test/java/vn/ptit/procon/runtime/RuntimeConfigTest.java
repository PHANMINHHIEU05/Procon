package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeConfigTest {

    @Test
    void loadsDefaultsAndRedactsToken() {
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "never-print-this"));

        assertEquals(RuntimeConfig.DEFAULT_BASE_URL, config.baseUrl());
        assertEquals(Duration.ofMillis(250), config.pollInterval());
        assertEquals(PlannerMode.WAIT, config.plannerMode());
        assertFalse(config.othersShapeDiagnostics());
        assertFalse(config.othersValueDiagnostics());
        assertFalse(config.contentionDiagnostics());
        assertFalse(config.toString().contains("never-print-this"));
    }

    @Test
    void parsesStrictOptInOthersShapeDiagnostics() {
        RuntimeConfig enabled = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_OTHERS_SHAPE_DIAGNOSTICS", "TrUe"));

        assertEquals(true, enabled.othersShapeDiagnostics());
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_OTHERS_SHAPE_DIAGNOSTICS", "yes")));
    }

    @Test
    void parsesIndependentStrictOptInOthersValueDiagnostics() {
        RuntimeConfig valueOnly = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_OTHERS_VALUE_DIAGNOSTICS", "TrUe"));
        RuntimeConfig shapeOnly = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_OTHERS_SHAPE_DIAGNOSTICS", "true"));

        assertTrue(valueOnly.othersValueDiagnostics());
        assertFalse(valueOnly.othersShapeDiagnostics());
        assertTrue(shapeOnly.othersShapeDiagnostics());
        assertFalse(shapeOnly.othersValueDiagnostics());
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_OTHERS_VALUE_DIAGNOSTICS", "yes")));
    }

    @Test
    void parsesIndependentStrictContentionDiagnostics() {
        RuntimeConfig enabled = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_CONTENTION_DIAGNOSTICS", "true"));

        assertTrue(enabled.contentionDiagnostics());
        assertFalse(enabled.othersShapeDiagnostics());
        assertFalse(enabled.othersValueDiagnostics());
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_CONTENTION_DIAGNOSTICS", "yes")));
    }

    @Test
    void requiresCredentialsAndEnforcesMinimumPollingInterval() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_POLL_INTERVAL_MS", "199")));
    }

    @Test
    void parsesAutonomousPlannerModesAndRejectsUnknownModes() {
        RuntimeConfig baseline = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "baseline"));
        RuntimeConfig brandAware = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "brand_aware"));
        RuntimeConfig refuelAware = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "refuel_aware"));
        RuntimeConfig refuelProbe = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "refuel_probe"));
        RuntimeConfig teamCoordinated = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "team_coordinated"));
        RuntimeConfig anytime = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "anytime"));
        RuntimeConfig anytimeHarvest = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "anytime_harvest"));
        RuntimeConfig anytimeContention = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "anytime_contention"));

        assertEquals(PlannerMode.BASELINE, baseline.plannerMode());
        assertEquals(PlannerMode.BRAND_AWARE, brandAware.plannerMode());
        assertEquals(PlannerMode.REFUEL_AWARE, refuelAware.plannerMode());
        assertEquals(PlannerMode.REFUEL_PROBE, refuelProbe.plannerMode());
        assertEquals(PlannerMode.TEAM_COORDINATED, teamCoordinated.plannerMode());
        assertEquals(PlannerMode.ANYTIME, anytime.plannerMode());
        assertEquals(PlannerMode.ANYTIME_HARVEST, anytimeHarvest.plannerMode());
        assertEquals(PlannerMode.ANYTIME_CONTENTION, anytimeContention.plannerMode());
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "advanced")));
    }

    @Test
    void parsesAnytimeArrivalContentionMode() {
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "anytime_arrival_contention"));

        assertEquals(PlannerMode.ANYTIME_ARRIVAL_CONTENTION, config.plannerMode());
    }

    @Test
    void parsesAnytimeWeightedArrivalContentionMode() {
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "anytime_weighted_arrival_contention"));

        assertEquals(PlannerMode.ANYTIME_WEIGHTED_ARRIVAL_CONTENTION, config.plannerMode());
    }

    @Test
    void parsesAnytimeRiskAdjustedMode() {
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "anytime_risk_adjusted"));

        assertEquals(PlannerMode.ANYTIME_RISK_ADJUSTED, config.plannerMode());
    }

    @Test
    void parsesAnytimeIntentAwareMode() {
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "anytime_intent_aware"));

        assertEquals(PlannerMode.ANYTIME_INTENT_AWARE, config.plannerMode());
    }
}
