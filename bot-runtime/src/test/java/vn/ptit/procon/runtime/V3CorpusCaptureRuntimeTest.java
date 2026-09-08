package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.protocol.ActionEncoder;
import vn.ptit.procon.protocol.DayStateMapper;
import vn.ptit.procon.protocol.ProconHttpClient;
import vn.ptit.procon.protocol.SetupMapper;
import vn.ptit.procon.protocol.dto.DayStateDto;
import vn.ptit.procon.protocol.dto.SetupDto;

/**
 * Deferred live-state capture inside the real match loop, against a fake server.
 *
 * <p>The evidence is differential, exactly as for the shadow: the SAME one-day match runs twice — once
 * with {@link V3CorpusCapture#disabled()} and once with capture ON — and the submitted wire payload, the
 * protocol call counts and every production log line must be identical. Capture may only ADD
 * {@code V3_CAPTURE_*} lines and files under its own directory.
 *
 * <p>{@code CAPTURE_REPRODUCES_SUBMITTED_ACTIONS} is the load-bearing test: it rebuilds the captured day
 * through the production mappers and re-plans it with the production V2/R3 planner, then asserts the
 * encoded actions and their fingerprint equal the ones the live loop actually submitted. That is the
 * whole premise of an offline corpus, proven rather than assumed.
 */
final class V3CorpusCaptureRuntimeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @TempDir
    Path corpusRoot;

    private HttpServer server;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final List<String> actionBodies = Collections.synchronizedList(new ArrayList<>());
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
        actionResponse.set(actionResult(0, "capture-actions"));
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        context("setup", exchange -> {
            count("setup");
            json(exchange, 200, SETUP_JSON);
        });
        context("assignment", exchange -> {
            count("assignment");
            body(exchange);
            json(exchange, 200, actionResult(0, "capture-assignment"));
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
            actionsAccepted.set(true);
            json(exchange, 200, actionResponse.get());
        });
        context("result", exchange -> {
            count("result");
            json(exchange, 200, "{\"status\":\"FINAL\",\"score\":0}");
        });
        server.start();
    }

    /** Rebuilds the fake match so a second run in the same test starts from a clean protocol history. */
    private void reset() throws IOException {
        server.stop(0);
        calls.clear();
        actionBodies.clear();
        actionsAccepted.set(false);
        buildServer();
    }

    /** The production runtime with the capture injection seam and the frozen V2/R3 planner. */
    private MatchRuntime runtime(V3CorpusCapture capture) {
        return new MatchRuntime("m-fake", client(), Duration.ofMillis(200), ignored -> { },
                new SetupMapper(), new DayStateMapper(), new SmokeAssignmentPolicy(), new PlanValidator(),
                new DaySimulator(), new JointTeamBeamR3Planner(), new ParityRecorder(), false, false,
                V3ShadowRunner.disabled(), capture);
    }

    /** The pre-capture constructor shape: no capture parameter exists at this call site at all. */
    private MatchRuntime legacyRuntime() {
        return new MatchRuntime("m-fake", client(), Duration.ofMillis(200), ignored -> { },
                new SetupMapper(), new DayStateMapper(), new SmokeAssignmentPolicy(), new PlanValidator(),
                new DaySimulator(), new JointTeamBeamR3Planner(), new ParityRecorder());
    }

    private ProconHttpClient client() {
        return new ProconHttpClient("http://localhost:" + server.getAddress().getPort(), "m-fake",
                "fake-token", Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private Run go(V3CorpusCapture capture) throws Exception {
        reset();
        MatchRuntime runtime = runtime(capture);
        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtime.run()));
        return snapshot(results.get(0), logs);
    }

    private Run goLegacy() throws Exception {
        reset();
        MatchRuntime runtime = legacyRuntime();
        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtime.run()));
        return snapshot(results.get(0), logs);
    }

    /** The same one-day match for a day the server rejects: the message is captured, never rethrown. */
    private Run goRejected(V3CorpusCapture capture, List<String> messages) throws Exception {
        reset();
        actionResponse.set(invalidActionResult(0, "E_STEP_OVERFLOW", "capture-rejected"));
        MatchRuntime runtime = runtime(capture);
        String logs = capturing(
                () -> messages.add(assertThrows(IllegalStateException.class, runtime::run).getMessage()));
        return snapshot(null, logs);
    }

    private Run snapshot(MatchRuntimeResult result, String logs) {
        Map<String, Integer> httpCalls = new TreeMap<>();
        calls.forEach((endpoint, counter) -> httpCalls.put(endpoint, counter.get()));
        return new Run(result, logs, logs.lines().map(line -> line.split(" ", 2)[0]).toList(),
                List.copyOf(actionBodies), Map.copyOf(httpCalls));
    }

    /** One whole match observation: its result, its log, its wire payloads and its protocol counts. */
    private record Run(MatchRuntimeResult result, String logs, List<String> events, List<String> actions,
            Map<String, Integer> httpCalls) {

        /** The log with every capture line removed — what the production runtime alone printed. */
        private List<String> productionLines() {
            return normalise(logs).stream().filter(line -> !line.startsWith("V3_CAPTURE")).toList();
        }
    }

    /**
     * The log with its own wall-clock readings masked. The V2/R3 planner reports its search timings and
     * those legitimately differ run to run; everything else a run prints is a function of the state and
     * the plan, so it must not.
     */
    private static List<String> normalise(String logs) {
        return logs.lines()
                .map(line -> line.replaceAll("(?i)([A-Za-z0-9]*millis)=[-0-9]+", "$1=<t>"))
                .toList();
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

    private static void json(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
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

    private static String capturing(ThrowingRunnable action) throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static int lines(String logs, String prefix) {
        return (int) logs.lines().filter(line -> line.startsWith(prefix)).count();
    }

    /** Every file the capture left behind, relative to the corpus root and sorted for comparison. */
    private List<String> corpusFiles() throws IOException {
        if (!Files.isDirectory(corpusRoot)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(corpusRoot)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> corpusRoot.relativize(path).toString())
                    .sorted()
                    .toList();
        }
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
    @DisplayName("CAPTURE_OFF_IS_THE_DEFAULT")
    void CAPTURE_OFF_IS_THE_DEFAULT() {
        Map<String, String> environment = new HashMap<>();
        environment.put("PROCON_MATCH_ID", "m-default");
        environment.put("PROCON_TOKEN", "fake-token");
        RuntimeConfig config = RuntimeConfig.fromEnvironment(environment);

        assertFalse(config.v3CaptureStates(), "capture must be OFF unless it is explicitly asked for");
        assertEquals(RuntimeConfig.DEFAULT_V3_CAPTURE_DIRECTORY, config.v3CaptureDirectory());
        assertFalse(V3CorpusCapture.from(config).enabled());
        assertFalse(config.toString().contains("fake-token"), "the token is never rendered: " + config);

        environment.put("PROCON_V3_CAPTURE_STATES", "true");
        environment.put("PROCON_V3_CAPTURE_DIR", corpusRoot.toString());
        RuntimeConfig on = RuntimeConfig.fromEnvironment(environment);
        assertTrue(on.v3CaptureStates());
        assertEquals(corpusRoot.toString(), on.v3CaptureDirectory());
        assertTrue(V3CorpusCapture.from(on).enabled());

        environment.put("PROCON_V3_CAPTURE_STATES", "yes");
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.fromEnvironment(environment),
                "an ambiguous flag value is rejected, never silently treated as ON");
    }

    @Test
    @DisplayName("CAPTURE_OFF_RUNTIME_INVARIANCE")
    void CAPTURE_OFF_RUNTIME_INVARIANCE() throws Exception {
        Run legacy = goLegacy();
        Run off = go(V3CorpusCapture.disabled());

        assertEquals(normalise(legacy.logs()), normalise(off.logs()),
                "the OFF capture must leave the log line-for-line identical to the pre-capture runtime");
        assertEquals(legacy.events(), off.events(), "and event for event");
        assertEquals(legacy.actions(), off.actions(), "the same wire payload, action for action");
        assertEquals(legacy.httpCalls(), off.httpCalls());
        assertEquals(legacy.result().submittedDays(), off.result().submittedDays());
        assertEquals(1, off.result().submittedDays());

        assertEquals(0, lines(off.logs(), "V3_CAPTURE"), "an OFF capture writes not one line: " + off.logs());
        assertEquals(List.of(), corpusFiles(), "and not one file");
        assertFalse(legacyRuntime().corpusCapture().enabled(),
                "the historical constructor shape defaults to the OFF capture");
    }

    @Test
    @DisplayName("CAPTURE_ON_SUBMISSION_INVARIANCE")
    void CAPTURE_ON_SUBMISSION_INVARIANCE() throws Exception {
        Run off = go(V3CorpusCapture.disabled());
        Run on = go(V3CorpusCapture.enabled(corpusRoot));

        assertEquals(off.actions(), on.actions(),
                "the submitted payload must be identical with capture OFF and ON");
        assertEquals(off.productionLines(), on.productionLines(),
                "capture may only ADD lines; it changes no production line");
        assertEquals(off.httpCalls(), on.httpCalls());
        assertEquals(off.result().submittedDays(), on.result().submittedDays());

        assertEquals(List.of("m-fake/day-0/accepted.json", "m-fake/day-0/actions.json",
                        "m-fake/day-0/meta.json", "m-fake/day-0/state.json", "m-fake/setup.json"),
                corpusFiles(), "one accepted day, one setup, no temporary file left behind");
        assertEquals(1, lines(on.logs(), "V3_CAPTURE_WRITTEN"));
        assertEquals(0, lines(on.logs(), "V3_CAPTURE_FAILED"), on.logs());

        // The capture is written BEFORE the POST and confirmed after it: that ordering is the guarantee
        // that the recorded state is the state V2/R3 planned from.
        List<String> events = on.logs().lines().map(line -> line.split(" ", 2)[0]).toList();
        assertTrue(events.indexOf("V3_CAPTURE_WRITTEN") < events.indexOf("ACTIONS_SUBMITTED"),
                "capture must precede the POST: " + on.logs());
        assertTrue(events.indexOf("ACTIONS_ACCEPTED") < events.size(), on.logs());
    }

    @Test
    @DisplayName("CAPTURE_REPRODUCES_SUBMITTED_ACTIONS")
    void CAPTURE_REPRODUCES_SUBMITTED_ACTIONS() throws Exception {
        Run on = go(V3CorpusCapture.enabled(corpusRoot));
        Path day = corpusRoot.resolve("m-fake/day-0");

        SetupDto setup = MAPPER.readValue(corpusRoot.resolve("m-fake/setup.json").toFile(), SetupDto.class);
        DayStateDto stateDto = MAPPER.readValue(day.resolve("state.json").toFile(), DayStateDto.class);
        V3CorpusCapture.CapturedMeta meta =
                MAPPER.readValue(day.resolve("meta.json").toFile(), V3CorpusCapture.CapturedMeta.class);
        V3CorpusCapture.CapturedActions captured =
                MAPPER.readValue(day.resolve("actions.json").toFile(), V3CorpusCapture.CapturedActions.class);

        // Rebuild the pre-submit DayState with the production mappers only.
        List<AgentKind> assignment = meta.assignment().stream().map(AgentKind::valueOf).toList();
        StaticMatchData matchData = new SetupMapper().toDomain(setup);
        DayState replayed = new DayStateMapper().toDomain(stateDto, matchData, assignment);

        assertEquals(0, replayed.day().value());
        assertEquals(meta.agentCount(), replayed.agents().size());
        assertEquals(meta.stepBudget(), replayed.stepBudget());
        assertEquals(V3ShadowSubmissionAuthority.V2_R3, meta.plannerAuthority());

        // Re-plan it with the production planner and re-encode: the corpus must reproduce the wire.
        TeamPlan replayedPlan = new JointTeamBeamR3Planner().plan(replayed);
        List<List<Integer>> reEncoded = new ActionEncoder().encode(replayedPlan, replayed.agents().size());
        assertEquals(captured.actions(), reEncoded,
                "the offline replay must reproduce the exact wire actions the live loop submitted");
        assertEquals(MAPPER.readTree(on.actions().get(0)), MAPPER.valueToTree(captured.actions()),
                "and the captured actions must equal the bytes the fake server received");

        String replayedFingerprint =
                String.format(Locale.ROOT, "%08x", MAPPER.writeValueAsString(reEncoded).hashCode());
        assertEquals(captured.fingerprint(), replayedFingerprint, "fingerprint parity");
        assertEquals(meta.actionFingerprint(), captured.fingerprint());

        V3CorpusCapture.CapturedAcceptance acceptance = MAPPER.readValue(
                day.resolve(V3CorpusCapture.ACCEPTED_MARKER).toFile(),
                V3CorpusCapture.CapturedAcceptance.class);
        assertTrue(acceptance.accepted());
        assertEquals(0, acceptance.day());
        assertEquals("m-fake", acceptance.matchId());
    }

    @Test
    @DisplayName("CAPTURE_STORES_NO_CREDENTIAL")
    void CAPTURE_STORES_NO_CREDENTIAL() throws Exception {
        go(V3CorpusCapture.enabled(corpusRoot));

        List<String> files = corpusFiles();
        assertFalse(files.isEmpty(), "the capture must have produced evidence to inspect");
        for (String file : files) {
            String content = Files.readString(corpusRoot.resolve(file), StandardCharsets.UTF_8);
            String lowered = content.toLowerCase(Locale.ROOT);
            for (String forbidden : List.of("fake-token", "bearer", "authorization", "cookie",
                    "hexsession", "token", "session", "secret")) {
                assertFalse(lowered.contains(forbidden),
                        () -> file + " must never contain '" + forbidden + "': " + content);
            }
        }
    }

    @Test
    @DisplayName("CAPTURE_MARKS_ONLY_ACCEPTED_DAYS")
    void CAPTURE_MARKS_ONLY_ACCEPTED_DAYS() throws Exception {
        List<String> messages = new ArrayList<>();
        Run rejected = goRejected(V3CorpusCapture.enabled(corpusRoot), messages);

        assertTrue(messages.get(0).contains("Server rejected day 0"), messages.get(0));
        assertEquals(1, lines(rejected.logs(), "V3_CAPTURE_WRITTEN"),
                "the pre-submission capture still happened: it is evidence about the rejection too");
        assertEquals(List.of("m-fake/day-0/actions.json", "m-fake/day-0/meta.json",
                        "m-fake/day-0/state.json", "m-fake/setup.json"),
                corpusFiles(), "but a rejected day carries no acceptance marker");
        assertFalse(Files.exists(corpusRoot.resolve("m-fake/day-0/" + V3CorpusCapture.ACCEPTED_MARKER)));
    }

    @Test
    @DisplayName("CAPTURE_FAILURE_CANNOT_REACH_THE_WIRE")
    void CAPTURE_FAILURE_CANNOT_REACH_THE_WIRE() throws Exception {
        Run off = go(V3CorpusCapture.disabled());

        // A regular file where the corpus root must be a directory: every write below it must fail.
        Path blocked = corpusRoot.resolve("blocked");
        Files.writeString(blocked, "not a directory", StandardCharsets.UTF_8);
        Run broken = go(V3CorpusCapture.enabled(blocked));

        assertEquals(off.actions(), broken.actions(),
                "a failing capture must not change one byte of the submitted payload");
        assertEquals(off.productionLines(), broken.productionLines());
        assertEquals(off.httpCalls(), broken.httpCalls());
        assertEquals(1, broken.result().submittedDays(), "the match still completed");
        assertEquals(1, lines(broken.logs(), "V3_CAPTURE_FAILED"), broken.logs());
        assertEquals(0, lines(broken.logs(), "V3_CAPTURE_WRITTEN"), broken.logs());
        assertEquals("not a directory", Files.readString(blocked, StandardCharsets.UTF_8));
    }
}
