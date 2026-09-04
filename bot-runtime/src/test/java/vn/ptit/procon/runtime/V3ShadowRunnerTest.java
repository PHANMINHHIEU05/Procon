package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v3.V3Phase24Fixtures;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;
import vn.ptit.procon.planner.v3.V3ShadowPlanner;

/**
 * The shadow boundary under adversarial evaluators: slow, hostile, blocked and cancelled.
 *
 * <p>No test here runs a real V3 search. The point is the BOUNDARY — that a shadow task which times out,
 * throws, or never returns is recorded and then forgotten, and that nothing it does can reach a submission.
 * The searches themselves are exercised by the offline harness.
 *
 * <p>Time is injected wherever a verdict depends on it, so the timeout classification is decided by an
 * explicit virtual clock rather than by how loaded the machine happens to be.
 */
final class V3ShadowRunnerTest {

    private static final long SMALL_BUDGET_MILLIS = 100;
    private static final long LARGE_BUDGET_MILLIS = 60_000;

    private final DayState state = V3Phase24Fixtures.mediumSharedStock();
    private final TeamPlan plan = SafePlanFactory.waitAll(state);
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> lines = new CopyOnWriteArrayList<>();

    /** The runtime's log shape, captured twice: the bare event order, and the flattened key=value line. */
    private final V3ShadowRunner.Log log = (event, fields) -> {
        events.add(event);
        StringBuilder value = new StringBuilder(event);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            value.append(' ').append(fields[index]).append('=').append(fields[index + 1]);
        }
        lines.add(value.toString());
    };

    private String line(String event) {
        return lines.stream().filter(value -> value.startsWith(event + " ")).findFirst()
                .orElseThrow(() -> new AssertionError("No " + event + " line in " + lines));
    }

    private long count(String event) {
        return events.stream().filter(event::equals).count();
    }

    /** An authority audit is required to schedule; these unit tests never vary it. */
    private static V3ShadowSubmissionAuthority authority(int day) {
        return new V3ShadowSubmissionAuthority(day, V3ShadowSubmissionAuthority.V2_R3, "0:W3;", "0:W3;",
                "UNAVAILABLE", true);
    }

    private void schedule(V3ShadowRunner runner, int day) {
        runner.schedule(day, state, plan, V3ShadowStateSnapshotAudit.fingerprint(state), "m-unit",
                authority(day), log);
    }

    /**
     * A stub evaluation with a distinct value in every mandated field, so a dropped or mislabelled field is
     * visible in an assertion rather than hidden behind a repeated zero.
     *
     * <p>{@code raw} and {@code safe} are given independently on purpose: the pair is the whole point of the
     * raw-versus-fallback record, and a stub that made them equal could not detect a collapsed field.
     */
    private static V3ShadowEvaluation evaluation(int day, int rawOwn, int rawHybrid, int safeOwn,
            int safeHybrid, boolean fallbackUsed) {
        return evaluation(day, rawOwn, rawHybrid, safeOwn, safeHybrid, fallbackUsed,
                V3ShadowEvaluation.Verdict.WIN, fallbackUsed ? V3ShadowEvaluation.Verdict.TIE
                        : V3ShadowEvaluation.Verdict.WIN);
    }

    private static V3ShadowEvaluation evaluation(int day, int rawOwn, int rawHybrid, int safeOwn,
            int safeHybrid, boolean fallbackUsed, V3ShadowEvaluation.Verdict rawVerdict,
            V3ShadowEvaluation.Verdict safeVerdict) {
        return new V3ShadowEvaluation(day, 7, 4, 6, 5, 3, 29, "V2ROOT", "V2PHYS", rawOwn, 5, 8, 6, 4,
                rawHybrid, "RAWPHYS", safeOwn, safeHybrid, fallbackUsed ? "V2PHYS" : "RAWPHYS", fallbackUsed,
                "V3ROOT", "V3ROOT|0:1>2/1:STOP", 12, false, 34, 21, 13, 8, 0, rawVerdict, safeVerdict);
    }

    /** The neutral stub: instant, deterministic, and identical for every day. */
    private static V3ShadowEvaluator instant() {
        return (state, submitted) -> evaluation(state.day().value(), 9, 37, 9, 37, false);
    }

    @Test
    @DisplayName("V3_SHADOW_TIMEOUT_ISOLATED")
    void V3_SHADOW_TIMEOUT_ISOLATED() {
        AtomicLong nanos = new AtomicLong();
        V3ShadowEvaluator slow = (snapshot, submitted) -> {
            nanos.addAndGet(500_000_000L);
            return evaluation(snapshot.day().value(), 99, 400, 99, 400, false);
        };
        try (V3ShadowRunner runner =
                V3ShadowRunner.enabled(SMALL_BUDGET_MILLIS, false, slow, nanos::get)) {
            schedule(runner, 3);
            assertTrue(runner.awaitIdle(5_000), "the shadow worker must settle");

            V3ShadowResult result = runner.results().get(0);
            assertEquals(V3ShadowStatus.TIMEOUT, result.status());
            assertTrue(result.timedOut());
            assertFalse(result.completed());
            assertEquals(500, result.planningMillis(), "the virtual clock decides the classification");
            assertTrue(result.evaluation().isEmpty(),
                    "an overrun candidate is discarded whole, never partially recorded");
            assertEquals(-1, result.rawV3OwnSemi());
            assertEquals("NA", result.v3PhysicalSignature());
            assertEquals("UNAVAILABLE", runner.lastV3PhysicalSignature(),
                    "a timed-out day must not publish a V3 signature");
            assertTrue(runner.comparisons().isEmpty(), "a timed-out day contributes no comparison");

            assertEquals(1, count("V3_SHADOW_TIMEOUT"));
            assertEquals(0, count("V3_SHADOW_RESULT"));
            assertTrue(line("V3_SHADOW_TIMEOUT").contains("budgetMillis=100"), lines.toString());

            runner.finish(log);
            V3ShadowAggregate.Snapshot summary = runner.summary();
            assertEquals(1, summary.daysTimedOut());
            assertEquals(0, summary.daysCompleted());
            assertTrue(summary.accountedFor() && summary.settled(), summary.toString());
        }
    }

    @Test
    @DisplayName("V3_SHADOW_EXCEPTION_ISOLATED")
    void V3_SHADOW_EXCEPTION_ISOLATED() {
        AtomicLong calls = new AtomicLong();
        V3ShadowEvaluator hostile = (snapshot, submitted) -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("shadow blew up");
            throw new AssertionError("hostile error");
        };
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(LARGE_BUDGET_MILLIS, false, hostile)) {
            schedule(runner, 5);
            assertTrue(runner.awaitIdle(5_000));
            // The second day proves the worker thread survived the first throwable.
            schedule(runner, 6);
            assertTrue(runner.awaitIdle(5_000));

            List<V3ShadowResult> results = runner.results();
            assertEquals(2, results.size(), results.toString());
            assertEquals(V3ShadowStatus.ERROR, results.get(0).status());
            assertEquals("java.lang.IllegalStateException", results.get(0).failureClass());
            assertEquals("shadow blew up", results.get(0).failureMessage());
            assertTrue(results.get(0).evaluation().isEmpty());
            assertEquals("java.lang.AssertionError", results.get(1).failureClass(),
                    "the boundary catches Throwable, not merely Exception");

            assertEquals(2, count("V3_SHADOW_ERROR"));
            assertEquals(0, count("V3_SHADOW_RESULT"));
            assertTrue(line("V3_SHADOW_ERROR").contains("exception=java.lang.IllegalStateException"));
            assertTrue(runner.comparisons().isEmpty());
            assertEquals(2, runner.summary().daysFailed());
            assertTrue(runner.summary().settled());
        }
    }

    @Test
    @DisplayName("V3_SHADOW_BUSY_EXECUTOR_DROPS_TASK")
    void V3_SHADOW_BUSY_EXECUTOR_DROPS_TASK() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        V3ShadowEvaluator blocked = (snapshot, submitted) -> {
            entered.countDown();
            release.await();
            return evaluation(snapshot.day().value(), 9, 37, 9, 37, false);
        };
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(LARGE_BUDGET_MILLIS, false, blocked)) {
            try {
                schedule(runner, 1);
                assertTrue(entered.await(5, TimeUnit.SECONDS), "day 1 must occupy the worker");
                schedule(runner, 2);
                schedule(runner, 3);

                List<V3ShadowResult> dropped = runner.results();
                assertEquals(2, dropped.size(), dropped.toString());
                assertEquals(List.of(2, 3), dropped.stream().map(V3ShadowResult::day).toList(),
                        "the deterministic policy drops the NEWEST day, never the running one");
                assertTrue(dropped.stream().allMatch(V3ShadowResult::dropped));
                assertEquals("PREVIOUS_TASK_RUNNING", dropped.get(0).failureMessage());
                assertEquals(0, dropped.get(0).planningMillis());

                assertEquals(1, count("V3_SHADOW_START"));
                assertEquals(2, count("V3_SHADOW_DROP"));
                assertTrue(line("V3_SHADOW_DROP").contains("reason=PREVIOUS_TASK_RUNNING"));
                V3ShadowAggregate.Snapshot busy = runner.summary();
                assertEquals(3, busy.daysEligible());
                assertEquals(1, busy.daysStarted());
                assertEquals(2, busy.daysDropped());
                assertTrue(busy.accountedFor(), busy.toString());
            } finally {
                release.countDown();
            }
            assertTrue(runner.awaitIdle(5_000));
            assertEquals(3, runner.results().size());
            assertEquals(1, runner.summary().daysCompleted(), "only the day that ran is completed");
            assertEquals(1, runner.comparisons().size(), "a dropped day produces no comparison");
        }
    }

    @Test
    @DisplayName("V3_SHADOW_NO_UNBOUNDED_QUEUE")
    void V3_SHADOW_NO_UNBOUNDED_QUEUE() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        V3ShadowEvaluator blocked = (snapshot, submitted) -> {
            entered.countDown();
            release.await();
            return evaluation(snapshot.day().value(), 9, 37, 9, 37, false);
        };
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(LARGE_BUDGET_MILLIS, false, blocked)) {
            try {
                schedule(runner, 0);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (int day = 1; day <= 60; day++) {
                    schedule(runner, day);
                    assertEquals(0, runner.queueSize(), "day " + day + " must never be enqueued");
                    assertEquals(1, runner.queueRemainingCapacity(),
                            "the queue capacity is one and stays unused");
                }
                assertEquals(1, count("V3_SHADOW_START"), "at most one shadow task per runtime instance");
                assertEquals(60, count("V3_SHADOW_DROP"));
                assertEquals(60, runner.summary().daysDropped());
                assertEquals(61, runner.summary().daysEligible());
                assertTrue(runner.summary().accountedFor());
            } finally {
                release.countDown();
            }
            assertTrue(runner.awaitIdle(5_000));
            assertEquals(0, runner.queueSize());
            assertTrue(runner.summary().settled(), runner.summary().toString());
        }
    }

    /** Every field the specification requires to be observable, wherever it is declared. */
    private static final List<String> MANDATED_RESULT_FIELDS = List.of("day", "stateFingerprint",
            "completed", "timedOut", "failed", "planningMillis", "rawV3OwnSemi", "rawV3Brands",
            "rawV3CoupledOwn", "rawV3BaselineOpponent", "rawV3CoupledOpponent", "rawV3Hybrid4",
            "v3SupportRoot", "v3StrategicSignature", "v3PhysicalSignature", "fallbackUsedInsideV3",
            "statesExpanded", "completeTeamCandidates", "materializedPlans", "coupledEvaluations",
            "pathfindingExecutions");

    private static boolean declares(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) return true;
        }
        return false;
    }

    @Test
    @DisplayName("V3_SHADOW_RESULT_RECORD")
    void V3_SHADOW_RESULT_RECORD() {
        for (String field : MANDATED_RESULT_FIELDS) {
            assertTrue(declares(V3ShadowResult.class, field) || declares(V3ShadowEvaluation.class, field),
                    "No accessor for the mandated field " + field);
        }

        try (V3ShadowRunner runner = V3ShadowRunner.enabled(LARGE_BUDGET_MILLIS, false, instant())) {
            String fingerprint = V3ShadowStateSnapshotAudit.fingerprint(state);
            schedule(runner, 0);
            assertTrue(runner.awaitIdle(5_000));
            V3ShadowResult result = runner.results().get(0);

            assertEquals(0, result.day());
            assertEquals(fingerprint, result.stateFingerprint());
            assertTrue(result.completed());
            assertFalse(result.timedOut());
            assertFalse(result.failed());
            assertFalse(result.dropped());
            assertTrue(result.planningMillis() >= 0);
            assertEquals(9, result.rawV3OwnSemi());
            assertEquals(37, result.rawV3Hybrid4());
            assertEquals(9, result.safeV3OwnSemi());
            assertEquals(37, result.safeV3Hybrid4());
            assertEquals("V3ROOT", result.v3SupportRoot());
            assertEquals("RAWPHYS", result.v3PhysicalSignature());
            assertEquals("V3ROOT|0:1>2/1:STOP", result.v3StrategicSignature());
            assertFalse(result.fallbackUsedInsideV3());
            assertEquals(34, result.statesExpanded());
            assertEquals(21, result.completeTeamCandidates());
            assertEquals(13, result.materializedPlans());
            assertEquals(8, result.coupledEvaluations());
            assertEquals(0, result.pathfindingExecutions());
            assertEquals(5, result.evaluation().orElseThrow().rawV3Brands());
            assertEquals("", result.failureClass());
        }

        // The record itself refuses every incoherent combination, so "no partial V3 plan" is structural.
        V3ShadowEvaluation stub = evaluation(0, 9, 37, 9, 37, false);
        assertThrows(IllegalArgumentException.class, () -> new V3ShadowResult(0, "fp",
                V3ShadowStatus.COMPLETED, 1, Optional.empty(), "", ""));
        assertThrows(IllegalArgumentException.class, () -> new V3ShadowResult(0, "fp",
                V3ShadowStatus.TIMEOUT, 1, Optional.of(stub), "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> V3ShadowResult.completed(-1, "fp", 1, stub));
        assertThrows(IllegalArgumentException.class,
                () -> V3ShadowResult.completed(0, "fp", -1, stub));
    }

    @Test
    @DisplayName("V3_SHADOW_RAW_AND_SAFE_RESULTS_RECORDED")
    void V3_SHADOW_RAW_AND_SAFE_RESULTS_RECORDED() {
        // Raw 15/60 against a safe 13/52: the day where V3's own incumbent fallback overrode a better raw.
        V3ShadowEvaluator diverging =
                (snapshot, submitted) -> evaluation(snapshot.day().value(), 15, 60, 13, 52, true);
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(LARGE_BUDGET_MILLIS, true, diverging)) {
            schedule(runner, 0);
            assertTrue(runner.awaitIdle(5_000));

            V3ShadowResult result = runner.results().get(0);
            assertEquals(15, result.rawV3OwnSemi());
            assertEquals(60, result.rawV3Hybrid4());
            assertEquals(13, result.safeV3OwnSemi());
            assertEquals(52, result.safeV3Hybrid4());
            assertTrue(result.fallbackUsedInsideV3(), "the fallback flag is recorded, not inferred");

            V3ShadowEvaluation value = result.evaluation().orElseThrow();
            assertEquals("RAWPHYS", value.rawV3PhysicalSignature());
            assertEquals("V2PHYS", value.safeV3PhysicalSignature(),
                    "a used fallback means the safe plan is the incumbent's");
            assertNotEquals(value.rawV3PhysicalSignature(), value.safeV3PhysicalSignature());
            assertFalse(value.samePhysicalPlan());
            assertEquals(8, value.ownDelta());
            assertEquals(31, value.hybridDelta4());

            String reported = line("V3_SHADOW_RESULT");
            assertTrue(reported.contains("v3RawOwn=15"), reported);
            assertTrue(reported.contains("v3SafeOwn=13"), reported);
            assertTrue(reported.contains("v3RawHybrid4=60"), reported);
            assertTrue(reported.contains("v3SafeHybrid4=52"), reported);
            assertTrue(reported.contains("v2Own=7") && reported.contains("v2Hybrid4=29"), reported);
            assertTrue(reported.contains("deltaHybrid4=31"), reported);

            String comparison = line("V3_SHADOW_COMPARISON");
            assertTrue(comparison.contains("v3FallbackUsed=true"), comparison);
            assertTrue(comparison.contains("rawVerdict=WIN") && comparison.contains("safeVerdict=TIE"),
                    comparison);
            assertTrue(comparison.contains("status=COMPLETED"), comparison);

            V3ShadowComparison record = runner.comparisons().get(0);
            assertEquals(15, record.rawV3OwnSemi());
            assertEquals(60, record.rawV3Hybrid4());
            assertEquals(7, record.v2OwnSemi());
            assertEquals(29, record.v2Hybrid4());
            assertEquals(V3ShadowStatus.COMPLETED, record.status());

            // Verbose mode adds the signatures, and only the signatures.
            assertEquals(1, count("V3_SHADOW_VERBOSE"));
            assertTrue(line("V3_SHADOW_VERBOSE").contains("v3StrategicSignature=V3ROOT|0:1>2/1:STOP"));

            assertEquals(1, runner.summary().rawV3Wins());
            assertEquals(1, runner.summary().safeV3Ties());
            assertEquals(0, runner.summary().safeV3Wins());
        }
    }

    @Test
    @DisplayName("V3_SHADOW_MATCH_SUMMARY")
    void V3_SHADOW_MATCH_SUMMARY() {
        AtomicLong calls = new AtomicLong();
        V3ShadowEvaluator mixed = (snapshot, submitted) -> {
            long call = calls.getAndIncrement();
            if (call == 2) throw new IllegalStateException("third day fails");
            return evaluation(snapshot.day().value(), 9, 37, 9, 29, call == 1);
        };
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(LARGE_BUDGET_MILLIS, false, mixed)) {
            for (int day = 0; day < 3; day++) {
                schedule(runner, day);
                assertTrue(runner.awaitIdle(5_000), "day " + day + " must settle before the next");
            }
            runner.finish(log);

            V3ShadowAggregate.Snapshot summary = runner.summary();
            assertEquals(3, summary.daysEligible());
            assertEquals(3, summary.daysStarted());
            assertEquals(2, summary.daysCompleted());
            assertEquals(0, summary.daysDropped());
            assertEquals(0, summary.daysTimedOut());
            assertEquals(1, summary.daysFailed());
            assertEquals(2, summary.rawV3Wins());
            assertEquals(0, summary.rawV3Ties());
            assertEquals(0, summary.rawV3Losses());
            assertEquals(1, summary.safeV3Wins());
            assertEquals(1, summary.safeV3Ties());
            assertEquals(0, summary.safeV3Losses());
            assertTrue(summary.totalV3Millis() >= 0);
            assertTrue(summary.accountedFor() && summary.settled(), summary.toString());

            assertEquals(1, count("V3_SHADOW_MATCH_SUMMARY"));
            String reported = line("V3_SHADOW_MATCH_SUMMARY");
            for (String key : List.of("daysEligible=3", "daysStarted=3", "daysCompleted=2",
                    "daysDropped=0", "daysTimedOut=0", "daysFailed=1", "rawV3Wins=2", "rawV3Ties=0",
                    "rawV3Losses=0", "safeV3Wins=1", "safeV3Ties=1", "safeV3Losses=0", "v3ShadowMillis=",
                    "submittedPlanner=V2_R3")) {
                assertTrue(reported.contains(key), key + " missing from " + reported);
            }
        }

        // A comparator LOSS is tallied as a loss and never quietly reclassified.
        V3ShadowAggregate aggregate = new V3ShadowAggregate();
        aggregate.recordEligible();
        aggregate.recordStarted();
        aggregate.record(V3ShadowResult.completed(0, "fp", 7, evaluation(0, 5, 20, 5, 20, false,
                V3ShadowEvaluation.Verdict.LOSS, V3ShadowEvaluation.Verdict.LOSS)));
        assertEquals(1, aggregate.snapshot().rawV3Losses());
        assertEquals(1, aggregate.snapshot().safeV3Losses());
        assertEquals(7, aggregate.snapshot().totalV3Millis());
        assertTrue(aggregate.snapshot().settled());
    }

    @Test
    @DisplayName("V3_SHADOW_EXECUTOR_BOUNDED_SHUTDOWN")
    void V3_SHADOW_EXECUTOR_BOUNDED_SHUTDOWN() throws InterruptedException {
        assertEquals(250, V3ShadowRunner.SHUTDOWN_GRACE_MILLIS, "the grace is bounded and small");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        V3ShadowEvaluator neverReturns = (snapshot, submitted) -> {
            entered.countDown();
            release.await();
            return evaluation(snapshot.day().value(), 9, 37, 9, 37, false);
        };
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(LARGE_BUDGET_MILLIS, false, neverReturns)) {
            try {
                schedule(runner, 4);
                assertTrue(entered.await(5, TimeUnit.SECONDS));

                long startedNanos = System.nanoTime();
                runner.finish(log);
                long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
                assertTrue(elapsedMillis < 3_000,
                        "match end must not wait on a multi-second search: " + elapsedMillis + "ms");

                assertEquals(1, runner.results().size());
                assertEquals(V3ShadowStatus.TIMEOUT, runner.results().get(0).status(),
                        "a cancelled straggler is classified, not lost");
                assertEquals(1, runner.summary().daysTimedOut());
                assertTrue(runner.summary().settled(), runner.summary().toString());
                assertEquals(1, count("V3_SHADOW_MATCH_SUMMARY"));
                assertEquals(0, runner.queueSize());

                // After shutdown a further day is dropped; a rejection never reaches the action loop.
                schedule(runner, 5);
                assertEquals(2, runner.results().size());
                assertTrue(runner.results().get(1).dropped());
            } finally {
                release.countDown();
            }
        }
    }

    /** The shadow surface: if none of these can name a protocol type, none of them can POST. */
    private static final List<Class<?>> SHADOW_TYPES = List.of(V3ShadowPlanner.class,
            V3ShadowEvaluation.class, V3ShadowEvaluator.class, V3ShadowPlannerEvaluator.class,
            V3ShadowRunner.class, V3ShadowResult.class, V3ShadowComparison.class, V3ShadowAggregate.class,
            V3ShadowStateSnapshotAudit.class, V3ShadowStatus.class);

    @Test
    @DisplayName("V3_SHADOW_HAS_NO_HTTP_CLIENT")
    void V3_SHADOW_HAS_NO_HTTP_CLIENT() throws Exception {
        for (Class<?> type : SHADOW_TYPES) {
            for (Field field : type.getDeclaredFields()) {
                assertFalse(protocol(field.getType()),
                        type.getSimpleName() + " holds a protocol type: " + field);
            }
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(protocol(method.getReturnType()),
                        type.getSimpleName() + " returns a protocol type: " + method);
                assertNotEquals(TeamPlan.class, method.getReturnType(),
                        type.getSimpleName() + " must not hand a plan back: " + method);
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertFalse(protocol(parameter),
                            type.getSimpleName() + " accepts a protocol type: " + method);
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    assertFalse(protocol(parameter),
                            type.getSimpleName() + " is constructed from a protocol type: " + parameter);
                }
            }
        }

        // The evaluation carries scores and signatures — never a plan, so it cannot express a submission.
        for (RecordComponent component : V3ShadowEvaluation.class.getRecordComponents()) {
            assertNotEquals(TeamPlan.class, component.getType(), component.getName());
        }
        // Exactly one entry point, taking a state and the incumbent, returning data. No submit callback.
        Method[] evaluator = V3ShadowEvaluator.class.getDeclaredMethods();
        assertEquals(1, evaluator.length);
        assertEquals(V3ShadowEvaluation.class, evaluator[0].getReturnType());
        assertEquals(List.of(DayState.class, TeamPlan.class), List.of(evaluator[0].getParameterTypes()));

        // The structural proof: bot-planner cannot see bot-protocol at all.
        Path pom = Path.of("..", "bot-planner", "pom.xml");
        if (Files.exists(pom)) {
            assertFalse(Files.readString(pom).contains("bot-protocol"),
                    "bot-planner must not depend on bot-protocol; an accidental POST must be impossible");
        }

        // No shadow diagnostic may name a credential.
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(LARGE_BUDGET_MILLIS, true, instant())) {
            schedule(runner, 0);
            assertTrue(runner.awaitIdle(5_000));
            runner.finish(log);
        }
        for (String value : lines) {
            String lower = value.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("bearer"), value);
            assertFalse(lower.contains("authorization"), value);
            assertFalse(lower.contains("token"), value);
            assertFalse(lower.contains("procon_"), value);
        }
    }

    private static boolean protocol(Class<?> type) {
        return type.getName().startsWith("vn.ptit.procon.protocol");
    }

    @Test
    @DisplayName("V3_SHADOW_SUBMISSION_AUTHORITY_V2")
    void V3_SHADOW_SUBMISSION_AUTHORITY_V2() {
        Map<AgentId, List<AgentAction>> actions = Map.of(
                new AgentId(0), List.<AgentAction>of(new WaitAction(3)),
                new AgentId(1), List.<AgentAction>of(new WaitAction(3)));
        TeamPlan production = new TeamPlan(actions);
        List<List<Integer>> posted = List.of(List.of(-3), List.of(-3));

        V3ShadowSubmissionAuthority honest = V3ShadowSubmissionAuthority.of(0,
                V3ShadowSubmissionAuthority.V2_R3, production, 2, posted, "RAWPHYS");
        assertEquals("V2_R3", honest.submittedPlanner());
        assertTrue(honest.submittedMatchesV2(), "the payload on the wire is V2/R3's own encoding");
        assertEquals("0:W3;1:W3;", honest.v2PhysicalSignature());
        assertEquals(honest.v2PhysicalSignature(), honest.submittedPhysicalSignature());
        assertEquals("RAWPHYS", honest.v3PhysicalSignature());
        assertNotEquals(honest.v2PhysicalSignature(), honest.v3PhysicalSignature(),
                "the audit records V3's signature only to prove it was NOT the one submitted");

        // The audit is not a constant: a payload that is not V2's is detected.
        V3ShadowSubmissionAuthority tampered = V3ShadowSubmissionAuthority.of(0,
                V3ShadowSubmissionAuthority.V2_R3, production, 2, List.of(List.of(2), List.of(-3)),
                "RAWPHYS");
        assertFalse(tampered.submittedMatchesV2());
        assertNotEquals(tampered.v2PhysicalSignature(), tampered.submittedPhysicalSignature());

        // There is no promoted status, and there never will be.
        assertEquals(4, V3ShadowStatus.values().length);
        for (V3ShadowStatus status : V3ShadowStatus.values()) {
            assertFalse(status.name().contains("PROMOT"), status.name());
        }

        try (V3ShadowRunner runner = V3ShadowRunner.enabled(LARGE_BUDGET_MILLIS, false, instant())) {
            schedule(runner, 7);
            assertTrue(runner.awaitIdle(5_000));
            assertEquals(1, runner.submissionAuthorities().size());
            assertEquals(7, runner.submissionAuthorities().get(0).day());
            String reported = line("V3_SHADOW_SUBMISSION_AUTHORITY");
            assertTrue(reported.contains("submittedPlanner=V2_R3"), reported);
            assertTrue(reported.contains("submittedMatchesV2=true"), reported);
            assertTrue(events.indexOf("V3_SHADOW_SUBMISSION_AUTHORITY")
                    < events.indexOf("V3_SHADOW_START"), events.toString());
        }
    }

    @Test
    @DisplayName("V3_SHADOW_STATE_SNAPSHOT_SAME_AS_V2")
    void V3_SHADOW_STATE_SNAPSHOT_SAME_AS_V2() {
        AtomicReference<DayState> observed = new AtomicReference<>();
        V3ShadowEvaluator recording = (snapshot, submitted) -> {
            observed.set(snapshot);
            return evaluation(snapshot.day().value(), 9, 37, 9, 37, false);
        };
        String v2Fingerprint = V3ShadowStateSnapshotAudit.fingerprint(state);
        assertEquals(v2Fingerprint, V3ShadowStateSnapshotAudit.fingerprint(state),
                "the fingerprint of one immutable state is stable");

        try (V3ShadowRunner runner = V3ShadowRunner.enabled(LARGE_BUDGET_MILLIS, false, recording)) {
            schedule(runner, 2);
            assertTrue(runner.awaitIdle(5_000));

            assertSame(state, observed.get(), "V3 reads the very instance V2 planned from");
            V3ShadowStateSnapshotAudit audit = runner.snapshotAudits().get(0);
            assertTrue(audit.same(), audit.toString());
            assertEquals(v2Fingerprint, audit.v2StateFingerprint());
            assertEquals(v2Fingerprint, audit.v3StateFingerprint());
            assertEquals(v2Fingerprint, audit.stateFingerprint());
            assertEquals("m-unit", audit.matchId());
            assertEquals(2, audit.day());
            assertEquals(v2Fingerprint, runner.results().get(0).stateFingerprint());
            assertTrue(line("V3_SHADOW_STATE_SNAPSHOT_AUDIT").contains("same=true"));
        }

        // The audit is not vacuous: different states differ, and a mismatch is reported as one.
        assertNotEquals(v2Fingerprint, V3ShadowStateSnapshotAudit.fingerprint(V3Phase24Fixtures.largeDense()));
        assertFalse(V3ShadowStateSnapshotAudit.of("m-unit", 2, "AAAA:1", "BBBB:1").same());
    }
}
