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
    void parsesBaselinePlannerModeAndRejectsUnknownModes() {
        RuntimeConfig baseline = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "baseline"));

        assertEquals(PlannerMode.BASELINE, baseline.plannerMode());
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake",
                "PROCON_PLANNER_MODE", "advanced")));
    }
}