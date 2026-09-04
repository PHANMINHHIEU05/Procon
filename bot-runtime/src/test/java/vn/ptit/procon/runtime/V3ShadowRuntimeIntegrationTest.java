package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;
import vn.ptit.procon.protocol.DayStateMapper;
import vn.ptit.procon.protocol.ProconHttpClient;
import vn.ptit.procon.protocol.SetupMapper;

/**
 * The shadow inside the real match loop, against a fake server: it watches one day and moves nothing.
 *
 * <p>Every test here runs the SAME one-day match twice — once with {@link V3ShadowRunner#disabled()} and
 * once with a shadow ON — and compares the two runs. The production evidence is therefore differential:
 * the submitted wire payload, the protocol call counts and the production log lines must be identical,
 * and the ON run may only ADD {@code V3_SHADOW_*} lines.
 *
 * <p>The shadow evaluator is a stub on purpose. It fabricates a crushing V3 victory (raw hybrid 60 against
 * V2's 29) so each test proves the strongest form of the safety rule: even when the shadow reports that V3
 * would have been far better, the bytes on the wire are still V2/R3's. V3's actual strength is the offline
 * harness's subject, not this file's.
 *
 * <p>Ordering is asserted over the captured event log and a server-side marker list, never over a
 * millisecond threshold — except in {@code V3_SHADOW_SLOW_TASK_DOES_NOT_DELAY_SUBMISSION}, where the whole
 * match is compared against a shadow that alone would need thirty seconds.
 */
final class V3ShadowRuntimeIntegrationTest {

    /** Generous on purpose: these tests measure order, not search speed. */
    private static final long BUDGET_MILLIS = 30_000;

    /** A shadow day that could never fit inside an action deadline. */
    private static final long SLOW_MILLIS = 30_000;

    private static final String SHADOW_THREAD = "v3-shadow";

    private static final String SETUP_JSON = """
            {"daySteps":[3],
             "map":{"width":3,"height":1,"cells":[[0,0,0]]},
             "spots":[{"brand":1,"pos":1,"stocks":2}],
             "agents":[0,2],"fuelLimits":8}
            """;

    private static final String DAY_ZERO_STATE = """
            {"day":0,
             "agents":[{"kind":0,"pos":0,"fuel":8},{"kind":1,"pos":2,"fuel":null}],
             "others":[{}],"traffics":[]}
            """;

    private HttpServer server;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final List<String> actionBodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> assignmentBodies = Collections.synchronizedList(new ArrayList<>());

    /** Cross-thread markers: the server records the POST, the evaluator records the shadow. */
    private final List<String> order = new CopyOnWriteArrayList<>();

    private final AtomicBoolean actionsAccepted = new AtomicBoolean();
    private final AtomicReference<String> actionResponse = new AtomicReference<>();

    @BeforeEach
    void startFakeMatch() throws IOException {
        buildServer();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** The one-day fake match. {@code /actions} accepts whatever V2/R3 planned and records it. */
    private void buildServer() throws IOException {
        actionResponse.set(actionResult(0, "shadow-actions"));
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        context("setup", exchange -> {
            count("setup");
            json(exchange, 200, SETUP_JSON);
        });
        context("assignment", exchange -> {
            count("assignment");
            assignmentBodies.add(body(exchange));
            json(exchange, 200, actionResult(0, "shadow-assignment"));
        });
        context("start", exchange -> {
            count("start");
            json(exchange, 200, "{\"started\":true}");
        });
        context("state", exchange -> {
            count("state");
            if (actionsAccepted.get()) {
                json(exchange, 425, "{}");
                return;
            }
            json(exchange, 200, DAY_ZERO_STATE);
        });
        context("actions", exchange -> {
            count("actions");
            actionBodies.add(body(exchange));
            order.add("V2_POST_CALLED");
            actionsAccepted.set(true);
            json(exchange, 200, actionResponse.get());
        });
        context("result", exchange -> {
            count("result");
            order.add("V2_RESULT_READ");
            json(exchange, 200, "{\"status\":\"FINAL\",\"score\":0}");
        });
        server.start();
    }

    /** Rebuilds the fake match so a second run in the same test starts from a clean protocol history. */
    private void reset() throws IOException {
        server.stop(0);
        calls.clear();
        actionBodies.clear();
        assignmentBodies.clear();
        order.clear();
        actionsAccepted.set(false);
        buildServer();
    }

    /** The production runtime with the shadow injection seam. The planner is the frozen V2/R3 planner. */
    private MatchRuntime runtime(V3ShadowRunner shadow) {
        return new MatchRuntime("m-fake", client(), Duration.ofMillis(200), ignored -> { },
                new SetupMapper(), new DayStateMapper(), new SmokeAssignmentPolicy(), new PlanValidator(),
                new DaySimulator(), new JointTeamBeamR3Planner(), new ParityRecorder(), false, false,
                shadow);
    }

    /** The pre-Phase-2.7 constructor shape: no shadow parameter exists at this call site at all. */
    private MatchRuntime legacyRuntime() {
        return new MatchRuntime("m-fake", client(), Duration.ofMillis(200), ignored -> { },
                new SetupMapper(), new DayStateMapper(), new SmokeAssignmentPolicy(), new PlanValidator(),
                new DaySimulator(), new JointTeamBeamR3Planner(), new ParityRecorder());
    }

    private ProconHttpClient client() {
        return new ProconHttpClient("http://localhost:" + server.getAddress().getPort(), "m-fake",
                "fake-token", Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private Run go(V3ShadowRunner shadow) throws Exception {
        reset();
        MatchRuntime runtime = runtime(shadow);
        List<MatchRuntimeResult> results = new ArrayList<>();
        // The bounded idle wait is INSIDE the capture so a settled shadow's lines cannot land after the
        // stream is restored. It cannot hide a blocking shadow: run() has already returned by then.
        String logs = capturing(() -> {
            results.add(runtime.run());
            shadow.awaitIdle(2_000);
        });
        return snapshot(results.get(0), logs);
    }

    private Run goLegacy() throws Exception {
        reset();
        MatchRuntime runtime = legacyRuntime();
        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtime.run()));
        return snapshot(results.get(0), logs);
    }

    /** The same one-day match, for a day the server rejects: the message is captured, never rethrown. */
    private Run goRejected(V3ShadowRunner shadow, List<String> messages) throws Exception {
        reset();
        actionResponse.set(invalidActionResult(0, "E_STEP_OVERFLOW", "shadow-rejected"));
        MatchRuntime runtime = runtime(shadow);
        String logs = capturing(() -> {
            messages.add(assertThrows(IllegalStateException.class, runtime::run).getMessage());
            shadow.awaitIdle(2_000);
        });
        return snapshot(null, logs);
    }

    private Run snapshot(MatchRuntimeResult result, String logs) {
        Map<String, Integer> httpCalls = new TreeMap<>();
        calls.forEach((endpoint, counter) -> httpCalls.put(endpoint, counter.get()));
        return new Run(result, logs, logs.lines().map(line -> line.split(" ", 2)[0]).toList(),
                List.copyOf(actionBodies), List.copyOf(assignmentBodies), Map.copyOf(httpCalls));
    }

    /** One whole match observation: its result, its log, its wire payloads and its protocol counts. */
    private record Run(MatchRuntimeResult result, String logs, List<String> events, List<String> actions,
            List<String> assignments, Map<String, Integer> httpCalls) {

        /** The log with every shadow line removed — what the production runtime alone printed. */
        private List<String> productionLines() {
            return normalise(logs).stream().filter(line -> !line.startsWith("V3_SHADOW")).toList();
        }
    }

    /**
     * The log with its own wall-clock readings masked. The V2/R3 planner reports its search timings
     * ({@code searchWallMillis}, {@code totalWallMillis}, …) and those legitimately differ run to run;
     * everything else a run prints is a function of the state and the plan, so it must not.
     */
    private static List<String> normalise(String logs) {
        return logs.lines()
                .map(line -> line.replaceAll("(?i)([A-Za-z0-9]*millis)=[-0-9]+", "$1=<t>"))
                .toList();
    }

    private static long shadowThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> SHADOW_THREAD.equals(thread.getName())).count();
    }

    /**
     * A fabricated crushing V3 victory: raw own 15 and raw hybrid 60 against V2's 7 and 29. Nothing may
     * act on it, which is exactly what every test below asserts.
     */
    private static V3ShadowEvaluation evaluation(int day) {
        return new V3ShadowEvaluation(day, 7, 4, 6, 5, 3, 29, "V2ROOT", "V2PHYS", 15, 9, 16, 6, 4, 60,
                "RAWPHYS", 15, 60, "RAWPHYS", false, "V3ROOT", "V3ROOT|0:1>2/1:STOP", 12, false, 34, 21,
                13, 8, 0, V3ShadowEvaluation.Verdict.WIN, V3ShadowEvaluation.Verdict.WIN);
    }

    /** What the shadow thread itself saw, recorded from inside the evaluator. */
    private static final class Observed {
        private final List<Integer> days = new CopyOnWriteArrayList<>();
        private final List<Integer> postsAtEntry = new CopyOnWriteArrayList<>();
        private final List<Integer> agents = new CopyOnWriteArrayList<>();
    }

    /** The instant observer. It reports the fabricated V3 win and records what it was handed. */
    private V3ShadowEvaluator instant(Observed observed) {
        return (state, submitted) -> {
            order.add("V3_SHADOW_ENTERED");
            observed.days.add(state.day().value());
            observed.postsAtEntry.add(actionBodies.size());
            observed.agents.add(state.agents().size());
            order.add("V3_SHADOW_FINISHED");
            return evaluation(state.day().value());
        };
    }

    private void context(String endpoint, Handler handler) {
        server.createContext("/api/v1/matches/m-fake/" + endpoint, exchange -> {
            assertEquals("Bearer fake-token", exchange.getRequestHeaders().getFirst("Authorization"));
            handler.handle(exchange);
        });
    }

    private int count(String endpoint) {
        return calls.computeIfAbsent(endpoint, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String actionResult(int day, String submissionId) {
        return """
                {"day":%d,"match_id":"m-fake","protocol_version":"v0.1-draft",
                 "reason":"","response_ms":10,"submission_id":"%s",
                 "type":"action_result","valid":true}
                """.formatted(day, submissionId);
    }

    private static String invalidActionResult(int day, String reason, String submissionId) {
        return """
                {"day":%d,"match_id":"m-fake","protocol_version":"v0.1-draft",
                 "reason":"%s","response_ms":10,"submission_id":"%s",
                 "type":"action_result","valid":false}
                """.formatted(day, reason, submissionId);
    }

    private static String capturing(ThrowingRunnable body) throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static int lines(String logs, String prefix) {
        return (int) logs.lines().filter(line -> line.startsWith(prefix)).count();
    }

    /** The index of the first line for an event, so causally ordered events can be compared. */
    private static int index(String logs, String event) {
        List<String> all = logs.lines().toList();
        for (int position = 0; position < all.size(); position++) {
            if (all.get(position).startsWith(event + " ")) return position;
        }
        throw new AssertionError("No " + event + " line in:\n" + logs);
    }

    private static void assertOrder(String logs, String... events) {
        int previous = -1;
        for (String event : events) {
            int at = index(logs, event);
            assertTrue(at > previous, () -> "expected " + List.of(events) + " in order, but " + event
                    + " came too early:\n" + logs);
            previous = at;
        }
    }

    private static String field(String logs, String event, String key) {
        String line = logs.lines().filter(value -> value.startsWith(event + " ")).findFirst()
                .orElseThrow(() -> new AssertionError("No " + event + " line in:\n" + logs));
        Matcher matcher = Pattern.compile("(?:^| )" + Pattern.quote(key) + "=([^ ]*)").matcher(line);
        assertTrue(matcher.find(), () -> key + " missing from: " + line);
        return matcher.group(1);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @Test
    @DisplayName("SHADOW_OFF_RUNTIME_INVARIANCE")
    void SHADOW_OFF_RUNTIME_INVARIANCE() throws Exception {
        long threadsBefore = shadowThreads();
        Run legacy = goLegacy();
        Run off = go(V3ShadowRunner.disabled());

        assertEquals(normalise(legacy.logs()), normalise(off.logs()),
                "the OFF runner must leave the log line-for-line identical to the pre-shadow runtime");
        assertEquals(legacy.events(), off.events(), "and event for event");
        assertEquals(legacy.actions(), off.actions(), "the same wire payload, action for action");
        assertEquals(legacy.assignments(), off.assignments());
        assertEquals(legacy.httpCalls(), off.httpCalls());
        assertEquals(legacy.result().submittedDays(), off.result().submittedDays());
        assertEquals(legacy.result().rateLimitOccurrences(), off.result().rateLimitOccurrences());
        assertEquals(legacy.result().parityComparisons().size(),
                off.result().parityComparisons().size());
        assertEquals(1, off.result().submittedDays());
        assertEquals(1, off.actions().size());

        assertEquals(0, lines(off.logs(), "V3_SHADOW"), "an OFF shadow writes not one line: " + off.logs());
        assertFalse(off.logs().contains("V3_SHADOW"), off.logs());
        assertEquals(threadsBefore, shadowThreads(), "the OFF runtime starts no shadow thread");
        assertEquals(0, off.result().parityComparisons().size(), "one submitted day, no next state read");
    }

    @Test
    @DisplayName("SHADOW_ON_SUBMISSION_INVARIANCE")
    void SHADOW_ON_SUBMISSION_INVARIANCE() throws Exception {
        Run off = go(V3ShadowRunner.disabled());
        Observed observed = new Observed();
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(BUDGET_MILLIS, true, instant(observed))) {
            Run on = go(runner);

            assertEquals(off.actions(), on.actions(),
                    "the submitted payload must be identical with the shadow OFF and ON");
            assertEquals(off.productionLines(), on.productionLines(),
                    "the shadow may only ADD lines; it changes no production line");
            assertEquals(off.httpCalls(), on.httpCalls());
            assertEquals(off.result().submittedDays(), on.result().submittedDays());

            // The audit line names V2/R3 as the authority and the submitted payload as V2/R3's own.
            assertEquals(V3ShadowSubmissionAuthority.V2_R3,
                    field(on.logs(), "V3_SHADOW_SUBMISSION_AUTHORITY", "submittedPlanner"));
            assertEquals("true", field(on.logs(), "V3_SHADOW_SUBMISSION_AUTHORITY", "submittedMatchesV2"));
            assertEquals(field(on.logs(), "V3_SHADOW_SUBMISSION_AUTHORITY", "v2PhysicalSignature"),
                    field(on.logs(), "V3_SHADOW_SUBMISSION_AUTHORITY", "submittedPhysicalSignature"));
            assertEquals(1, runner.submissionAuthorities().size());
            assertTrue(runner.submissionAuthorities().get(0).submittedMatchesV2());
            assertEquals(V3ShadowSubmissionAuthority.V2_R3,
                    runner.submissionAuthorities().get(0).submittedPlanner());

            // The shadow reported a crushing V3 win, and the wire never heard a word of it.
            assertEquals("29", field(on.logs(), "V3_SHADOW_RESULT", "v2Hybrid4"));
            assertEquals("60", field(on.logs(), "V3_SHADOW_RESULT", "v3RawHybrid4"));
            assertEquals(1, runner.summary().daysCompleted());
            assertEquals(1, runner.summary().rawV3Wins());
            assertEquals(List.of(0), observed.days, "the shadow observed the authoritative day");
            assertEquals(1, lines(on.logs(), "V3_SHADOW_VERBOSE"), "verbose was asked for");
            assertFalse(on.logs().contains("fake-token"), "no diagnostic may print the credential");
        }
    }

    @Test
    @DisplayName("V3_SHADOW_ZERO_EXTRA_HTTP_CALLS")
    void V3_SHADOW_ZERO_EXTRA_HTTP_CALLS() throws Exception {
        Run off = go(V3ShadowRunner.disabled());
        Observed observed = new Observed();
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(BUDGET_MILLIS, false, instant(observed))) {
            Run on = go(runner);

            assertEquals(off.httpCalls(), on.httpCalls(),
                    "enabling the shadow must create zero additional protocol calls");
            assertEquals(Map.of("setup", 1, "assignment", 1, "start", 1, "state", 1, "actions", 1,
                    "result", 1), on.httpCalls());
            assertEquals(off.actions(), on.actions());
            assertEquals(1, runner.summary().daysCompleted(),
                    "non-vacuous: the shadow really did evaluate the day");
            assertEquals(1, observed.days.size());
        }
    }

    @Test
    @DisplayName("V3_SHADOW_SCHEDULED_AFTER_V2_ACCEPTED")
    void V3_SHADOW_SCHEDULED_AFTER_V2_ACCEPTED() throws Exception {
        Observed observed = new Observed();
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(BUDGET_MILLIS, false, instant(observed))) {
            Run on = go(runner);

            // The whole critical path first, in the runtime's own words, and only then the shadow.
            assertOrder(on.logs(), "ASSIGNMENT_ACCEPTED", "DAY_STATE_RECEIVED", "LOCAL_PLAN_VALID",
                    "ACTION_REQUEST_FINGERPRINT", "ACTIONS_SUBMITTED", "ACTION_RESPONSE_DAY_OBSERVED",
                    "ACTIONS_ACCEPTED", "V3_SHADOW_SUBMISSION_AUTHORITY", "V3_SHADOW_START",
                    "V3_SHADOW_STATE_SNAPSHOT_AUDIT", "V3_SHADOW_RESULT");
            assertEquals(List.of(1), observed.postsAtEntry,
                    "the POST had already happened when the shadow began");
            assertTrue(order.indexOf("V2_POST_CALLED") >= 0 && order.indexOf("V3_SHADOW_ENTERED") >= 0);
            assertTrue(order.indexOf("V2_POST_CALLED") < order.indexOf("V3_SHADOW_ENTERED"), order + "");

            // The shadow read the very state V2/R3 planned from.
            assertEquals("true", field(on.logs(), "V3_SHADOW_STATE_SNAPSHOT_AUDIT", "same"));
            assertEquals(field(on.logs(), "V3_SHADOW_STATE_SNAPSHOT_AUDIT", "v2StateFingerprint"),
                    field(on.logs(), "V3_SHADOW_STATE_SNAPSHOT_AUDIT", "v3StateFingerprint"));
            assertTrue(runner.snapshotAudits().get(0).same());
            assertEquals("m-fake", runner.snapshotAudits().get(0).matchId());
            assertEquals(1, on.result().submittedDays());
        }
    }

    @Test
    @DisplayName("V3_SHADOW_DOES_NOT_BLOCK_V2_POST")
    void V3_SHADOW_DOES_NOT_BLOCK_V2_POST() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        V3ShadowEvaluator blocked = (state, submitted) -> {
            order.add("V3_SHADOW_ENTERED");
            entered.countDown();
            release.await();
            order.add("V3_SHADOW_FINISHED");
            return evaluation(state.day().value());
        };
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(BUDGET_MILLIS, false, blocked)) {
            try {
                Run on = go(runner);

                assertEquals(1, on.result().submittedDays(),
                        "the day was submitted and accepted while the shadow was stuck");
                assertEquals(1, on.actions().size());
                assertEquals("FINAL", on.result().authoritativeResult().get("status").textValue());
                assertTrue(entered.await(5, TimeUnit.SECONDS), "the shadow really did start");
                assertTrue(order.indexOf("V2_POST_CALLED") < order.indexOf("V3_SHADOW_ENTERED"),
                        order + "");
                assertTrue(order.contains("V2_RESULT_READ"), order + "");
                assertFalse(order.contains("V3_SHADOW_FINISHED"),
                        "the match ended without the shadow ever finishing: " + order);
                assertEquals(V3ShadowStatus.TIMEOUT, runner.results().get(0).status());
                assertTrue(runner.results().get(0).evaluation().isEmpty(), "no partial V3 plan is kept");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @DisplayName("V3_SHADOW_SLOW_TASK_DOES_NOT_DELAY_SUBMISSION")
    void V3_SHADOW_SLOW_TASK_DOES_NOT_DELAY_SUBMISSION() throws Exception {
        V3ShadowEvaluator slow = (state, submitted) -> {
            order.add("V3_SHADOW_ENTERED");
            Thread.sleep(SLOW_MILLIS);
            order.add("V3_SHADOW_FINISHED");
            return evaluation(state.day().value());
        };
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(BUDGET_MILLIS, false, slow)) {
            long startedNanos = System.nanoTime();
            Run on = go(runner);
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;

            assertTrue(elapsedMillis < SLOW_MILLIS / 3, "the whole match took " + elapsedMillis
                    + " ms while its shadow alone would have needed " + SLOW_MILLIS + " ms");
            assertEquals(1, on.result().submittedDays());
            assertOrder(on.logs(), "LOCAL_PLAN_VALID", "ACTIONS_SUBMITTED", "ACTIONS_ACCEPTED",
                    "V3_SHADOW_START", "V3_SHADOW_TIMEOUT");
            assertEquals(0, lines(on.logs(), "V3_SHADOW_RESULT"), "the search never finished");
            assertFalse(order.contains("V3_SHADOW_FINISHED"), order + "");
            assertEquals(V3ShadowStatus.TIMEOUT, runner.results().get(0).status());
            assertEquals(1, runner.summary().daysTimedOut());
            assertEquals(1, lines(on.logs(), "V3_SHADOW_MATCH_SUMMARY"));
        }
    }

    @Test
    @DisplayName("V3_SHADOW_LAST_SUBMITTED_DAY_UNCHANGED")
    void V3_SHADOW_LAST_SUBMITTED_DAY_UNCHANGED() throws Exception {
        Run off = go(V3ShadowRunner.disabled());
        Observed observed = new Observed();
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(BUDGET_MILLIS, false, instant(observed))) {
            Run on = go(runner);

            assertEquals(off.result().submittedDays(), on.result().submittedDays());
            assertEquals(1, on.result().submittedDays(), "day 0 is submitted exactly once");
            assertEquals(1, lines(on.logs(), "ACTIONS_SUBMITTED"));
            assertEquals(1, lines(on.logs(), "ACTIONS_ACCEPTED"));
            assertEquals("0", field(on.logs(), "ACTIONS_ACCEPTED", "day"));
            assertEquals(lines(off.logs(), "ACTIONS_ACCEPTED"), lines(on.logs(), "ACTIONS_ACCEPTED"));
            assertEquals(off.logs().lines().filter(line -> line.startsWith("ACTION_RESPONSE_DAY")).toList(),
                    on.logs().lines().filter(line -> line.startsWith("ACTION_RESPONSE_DAY")).toList());
            assertEquals(0, lines(on.logs(), "AUTHORITATIVE_DAY_GAP"));
            assertEquals(0, lines(on.logs(), "DAY_ADVANCED"));
            assertEquals(off.httpCalls(), on.httpCalls(), "day progression is driven by /state alone");
            assertEquals(1, runner.summary().daysCompleted(), "and the shadow really did complete");
        }

        // Structural: the action loop asks the shadow whether it is on, hands it a day, names the last V3
        // signature for the audit and ends it. It reads no shadow score, so no shadow number can ever
        // become a submission decision — there is nowhere for "if v3 better then submit v3" to live.
        Path source =
                Path.of("src", "main", "java", "vn", "ptit", "procon", "runtime", "MatchRuntime.java");
        if (Files.exists(source)) {
            Set<String> used = new LinkedHashSet<>();
            Matcher matcher =
                    Pattern.compile("shadow\\.([A-Za-z0-9]+)\\(").matcher(Files.readString(source));
            while (matcher.find()) {
                used.add(matcher.group(1));
            }
            assertEquals(Set.of("enabled", "schedule", "finish", "lastV3PhysicalSignature"), used,
                    "MatchRuntime must consult nothing else on the shadow: " + used);
        }
    }

    @Test
    @DisplayName("V3_SHADOW_ACTION_RESULT_CONTRACT_UNCHANGED")
    void V3_SHADOW_ACTION_RESULT_CONTRACT_UNCHANGED() throws Exception {
        // (a) valid=false is still the sole rejection authority, and a rejected day is never shadowed.
        List<String> offMessages = new ArrayList<>();
        Run offRejected = goRejected(V3ShadowRunner.disabled(), offMessages);
        Observed observed = new Observed();
        List<String> onMessages = new ArrayList<>();
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(BUDGET_MILLIS, false, instant(observed))) {
            Run onRejected = goRejected(runner, onMessages);

            assertEquals(offMessages, onMessages, "the rejection diagnostic is unchanged by the shadow");
            assertTrue(onMessages.get(0).startsWith("Server rejected day 0 actions"), onMessages + "");
            assertEquals(offRejected.productionLines(), onRejected.productionLines());
            assertEquals(1, lines(onRejected.logs(), "SERVER_ACTION_REJECTED"));
            assertEquals(0, lines(onRejected.logs(), "ACTIONS_ACCEPTED"));
            assertEquals(0, lines(onRejected.logs(), "V3_SHADOW_START"),
                    "a day the server rejected is never handed to the shadow");
            assertEquals(0, lines(onRejected.logs(), "V3_SHADOW_SUBMISSION_AUTHORITY"));
            assertEquals(0, runner.summary().daysEligible());
            assertEquals(List.of(), observed.days);
            assertEquals(1, lines(onRejected.logs(), "V3_SHADOW_MATCH_SUMMARY"),
                    "the shadow still shuts itself down on the failure path");
        }

        // (b) action_result.day is diagnostic only: a response day of 3 must not reach the shadow.
        Observed second = new Observed();
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(BUDGET_MILLIS, false, instant(second))) {
            reset();
            actionResponse.set(actionResult(3, "shadow-response-day-3"));
            MatchRuntime runtime = runtime(runner);
            List<MatchRuntimeResult> results = new ArrayList<>();
            String logs = capturing(() -> {
                results.add(runtime.run());
                runner.awaitIdle(2_000);
            });

            assertEquals(1, results.get(0).submittedDays());
            assertEquals("3", field(logs, "ACTION_RESPONSE_DAY_OBSERVED", "responseDay"));
            assertEquals(1, lines(logs, "ACTION_RESPONSE_DAY_ANOMALY"));
            assertEquals("0", field(logs, "ACTIONS_ACCEPTED", "day"));
            assertEquals("0", field(logs, "V3_SHADOW_SUBMISSION_AUTHORITY", "day"));
            assertEquals("0", field(logs, "V3_SHADOW_START", "day"));
            assertEquals("0", field(logs, "V3_SHADOW_RESULT", "day"));
            assertEquals(List.of(0), second.days,
                    "the shadow observed the authoritative day, never the response day");
        }
    }

    @Test
    @DisplayName("V3_SHADOW_NO_ASSIGNMENT_CHANGE")
    void V3_SHADOW_NO_ASSIGNMENT_CHANGE() throws Exception {
        Run off = go(V3ShadowRunner.disabled());
        Observed observed = new Observed();
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(BUDGET_MILLIS, false, instant(observed))) {
            Run on = go(runner);

            assertEquals(List.of("[0,1]"), off.assignments(), "the frozen smoke assignment");
            assertEquals(off.assignments(), on.assignments(), "the shadow changes no assignment byte");
            assertEquals(1, on.httpCalls().get("assignment"),
                    "and provokes no further assignment call");
            assertEquals(off.logs().lines().filter(line -> line.startsWith("ASSIGNMENT")).toList(),
                    on.logs().lines().filter(line -> line.startsWith("ASSIGNMENT")).toList());
            assertOrder(on.logs(), "ASSIGNMENT_SUBMITTED", "ASSIGNMENT_ACCEPTED", "MATCH_STARTED",
                    "V3_SHADOW_START");
            assertEquals(List.of(2), observed.agents,
                    "the shadow only ever sees the state of an already assigned team");
        }
    }
}
