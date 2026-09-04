package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.StrategicSearchConfig;
import vn.ptit.procon.planner.v3.V3Phase24Fixtures;
import vn.ptit.procon.planner.v3.V3ShadowPlanner;

/**
 * The shadow switch: OFF unless asked for, and inert when OFF.
 *
 * <p>"Default OFF" is not only a parsed {@code false}. It also means the OFF runner builds no planner, holds
 * no executor and starts no thread — so the tests below measure the absence, not just the flag.
 */
final class V3ShadowConfigTest {

    private static final String THREAD_NAME = "v3-shadow";

    private static Map<String, String> baseEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("PROCON_MATCH_ID", "m-6861");
        environment.put("PROCON_TOKEN", "secret-value-never-logged");
        return environment;
    }

    private static long shadowThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> THREAD_NAME.equals(thread.getName())).count();
    }

    @Test
    @DisplayName("V3_SHADOW_DEFAULT_OFF")
    void V3_SHADOW_DEFAULT_OFF() {
        RuntimeConfig silent = RuntimeConfig.fromEnvironment(baseEnvironment());
        assertFalse(silent.v3Shadow(), "an environment that never mentions the shadow leaves it OFF");
        assertFalse(silent.v3ShadowVerbose());
        assertEquals(RuntimeConfig.DEFAULT_V3_SHADOW_MAX_MILLIS, silent.v3ShadowMaxMillis());
        assertEquals(4000, RuntimeConfig.DEFAULT_V3_SHADOW_MAX_MILLIS);

        // Every historical constructor keeps its exact meaning.
        RuntimeConfig legacy = new RuntimeConfig("https://example.test", "m-1", "t",
                Duration.ofMillis(250), Duration.ofSeconds(15), PlannerMode.JOINT_TEAM_BEAM_V2_R3, true,
                true, true, true);
        assertFalse(legacy.v3Shadow());
        assertFalse(legacy.v3ShadowVerbose());
        assertEquals(RuntimeConfig.DEFAULT_V3_SHADOW_MAX_MILLIS, legacy.v3ShadowMaxMillis());
        assertFalse(new RuntimeConfig("https://example.test", "m-1", "t", Duration.ofMillis(250),
                Duration.ofSeconds(15)).v3Shadow());

        // The OFF runner: no evaluator, no executor, no thread, and every method a no-op.
        long before = shadowThreads();
        List<String> events = new CopyOnWriteArrayList<>();
        try (V3ShadowRunner runner = V3ShadowRunner.disabled()) {
            assertFalse(runner.enabled());
            assertFalse(runner.verbose());
            assertEquals(before, shadowThreads(), "the OFF runner must start no shadow thread");
            assertEquals(0, runner.queueRemainingCapacity(), "the OFF runner holds no executor at all");
            assertEquals(0, runner.queueSize());
            assertEquals("UNAVAILABLE", runner.lastV3PhysicalSignature());

            runner.schedule(0, V3Phase24Fixtures.rawKindZero5x5(),
                    SafePlanFactory.waitAll(V3Phase24Fixtures.rawKindZero5x5()), "fp", "m-1",
                    new V3ShadowSubmissionAuthority(0, V3ShadowSubmissionAuthority.V2_R3, "0:W3;", "0:W3;",
                            "UNAVAILABLE", true),
                    (event, fields) -> events.add(event));
            runner.finish((event, fields) -> events.add(event));

            assertEquals(List.of(), events, "an OFF shadow writes not one line, so logs are unchanged");
            assertTrue(runner.results().isEmpty());
            assertTrue(runner.comparisons().isEmpty());
            assertTrue(runner.snapshotAudits().isEmpty());
            assertTrue(runner.submissionAuthorities().isEmpty());
            assertEquals(0, runner.summary().daysEligible());
            assertEquals(0, runner.summary().daysStarted());
            assertEquals(0, runner.summary().totalV3Millis());
            assertTrue(runner.awaitIdle(0), "there is nothing to wait for");
            assertEquals(before, shadowThreads());
        }
    }

    @Test
    @DisplayName("V3_SHADOW_CONFIG_PARSE")
    void V3_SHADOW_CONFIG_PARSE() {
        Map<String, String> environment = baseEnvironment();
        environment.put("PROCON_V3_SHADOW", "true");
        environment.put("PROCON_V3_SHADOW_MAX_MILLIS", "2500");
        environment.put("PROCON_V3_SHADOW_VERBOSE", "true");
        RuntimeConfig on = RuntimeConfig.fromEnvironment(environment);
        assertTrue(on.v3Shadow());
        assertEquals(2500, on.v3ShadowMaxMillis());
        assertTrue(on.v3ShadowVerbose());

        environment.put("PROCON_V3_SHADOW", "TRUE");
        environment.put("PROCON_V3_SHADOW_VERBOSE", "False");
        environment.put("PROCON_V3_SHADOW_MAX_MILLIS", "  ");
        RuntimeConfig mixed = RuntimeConfig.fromEnvironment(environment);
        assertTrue(mixed.v3Shadow(), "the flag is case-insensitive");
        assertFalse(mixed.v3ShadowVerbose());
        assertEquals(RuntimeConfig.DEFAULT_V3_SHADOW_MAX_MILLIS, mixed.v3ShadowMaxMillis(),
                "a blank budget falls back to the default, never to zero");

        for (String invalid : List.of("yes", "1", "on", "off")) {
            Map<String, String> broken = baseEnvironment();
            broken.put("PROCON_V3_SHADOW", invalid);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> RuntimeConfig.fromEnvironment(broken));
            assertEquals("PROCON_V3_SHADOW must be true or false", failure.getMessage());
        }
        for (String invalid : List.of("0", "-5")) {
            Map<String, String> broken = baseEnvironment();
            broken.put("PROCON_V3_SHADOW_MAX_MILLIS", invalid);
            assertEquals("PROCON_V3_SHADOW_MAX_MILLIS must be positive",
                    assertThrows(IllegalArgumentException.class,
                            () -> RuntimeConfig.fromEnvironment(broken)).getMessage());
        }
        Map<String, String> notANumber = baseEnvironment();
        notANumber.put("PROCON_V3_SHADOW_MAX_MILLIS", "soon");
        assertEquals("PROCON_V3_SHADOW_MAX_MILLIS must be an integer",
                assertThrows(IllegalArgumentException.class,
                        () -> RuntimeConfig.fromEnvironment(notANumber)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new RuntimeConfig("https://example.test", "m-1",
                "t", Duration.ofMillis(250), Duration.ofSeconds(15), PlannerMode.WAIT, false, false, false,
                false, true, 0, false));

        // The rendered configuration never carries the credential.
        String rendered = on.toString();
        assertFalse(rendered.contains("secret-value-never-logged"), rendered);
        assertTrue(rendered.contains("token=<set>"), rendered);
        assertTrue(rendered.contains("v3Shadow=true"), rendered);
        assertTrue(rendered.contains("v3ShadowMaxMillis=2500"), rendered);
        assertTrue(rendered.contains("v3ShadowVerbose=true"), rendered);

        // The budget is a shadow observation bound and reaches nothing else.
        assertEquals(2500, V3ShadowPlanner.shadowConfig(on.v3ShadowMaxMillis()).maxPlanningMillis());
        assertEquals(2500, new V3ShadowPlannerEvaluator(on.v3ShadowMaxMillis()).config()
                .maxPlanningMillis());
        assertThrows(IllegalArgumentException.class, () -> V3ShadowPlanner.shadowConfig(0));
        for (java.lang.reflect.Constructor<?> constructor
                : JointTeamBeamR3Planner.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(parameter == long.class || parameter == StrategicSearchConfig.class,
                        "the production planner accepts no millisecond budget and no strategic config, so"
                                + " a shadow timeout cannot become its deadline: " + constructor);
            }
        }
        // And there is no V3 production planner mode to select.
        for (PlannerMode mode : PlannerMode.values()) {
            assertFalse(mode.name().contains("V3"), mode.name());
            assertFalse(mode.name().contains("SHADOW"), mode.name());
        }
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeConfig.fromEnvironment(Map.of("PROCON_MATCH_ID", "m", "PROCON_TOKEN", "t",
                        "PROCON_PLANNER_MODE", "JOINT_TEAM_BEAM_V3")));
    }
}
