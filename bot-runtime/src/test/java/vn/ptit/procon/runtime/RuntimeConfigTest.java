package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertFalse(config.toString().contains("never-print-this"));
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

        assertEquals(PlannerMode.BASELINE, baseline.plannerMode());
        assertEquals(PlannerMode.BRAND_AWARE, brandAware.plannerMode());
        assertEquals(PlannerMode.REFUEL_AWARE, refuelAware.plannerMode());
        assertEquals(PlannerMode.REFUEL_PROBE, refuelProbe.plannerMode());
        assertEquals(PlannerMode.TEAM_COORDINATED, teamCoordinated.plannerMode());
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "advanced")));
    }
}
