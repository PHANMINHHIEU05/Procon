package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.ArrivalContentionAnytimePlanner;
import vn.ptit.procon.planner.WeightedArrivalContentionAnytimePlanner;
import vn.ptit.procon.planner.RiskAdjustedAnytimePlanner;
import vn.ptit.procon.planner.IntentAwareAnytimePlanner;
import vn.ptit.procon.protocol.ProconHttpClient;
import vn.ptit.procon.protocol.dto.SubmissionResult;

class MatchRuntimeIntegrationTest {

    private HttpServer server;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final List<String> actionBodies = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger assignmentAttempts = new AtomicInteger();
    private final AtomicInteger stateSuccesses = new AtomicInteger();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void startFakeMatch() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        context("setup", exchange -> {
            if (count("setup") == 1) {
                json(exchange, 425, "{\"reason\":\"not ready\"}");
            } else {
                // Matches the observed live setup: traffic thresholds are not setup fields.
                json(exchange, 200, """
                        {"daySteps":[3,3,3,3],
                         "map":{"width":3,"height":1,"cells":[[0,0,0]]},
                         "spots":[{"brand":1,"pos":1,"stocks":2}],
                         "agents":[0,2],"fuelLimits":8}
                        """);
            }
        });
        context("assignment", exchange -> {
            count("assignment");
            int attempt = assignmentAttempts.incrementAndGet();
            assertEquals("[0,1]", body(exchange));
            if (attempt == 1) {
                json(exchange, 425, "{\"reason\":\"not ready\"}");
            } else {
                json(exchange, 200, actionResult(0, "assignment-test"));
            }
        });
        context("start", exchange -> {
            if (count("start") == 1) {
                json(exchange, 429, "{\"reason\":\"E_RATE_LIMIT\"}");
            } else if (calls.get("start").get() == 2) {
                json(exchange, 425, "{}");
            } else {
                json(exchange, 200, "{\"started\":true}");
            }
        });
        context("state", exchange -> {
            count("state");
            int successfulIndex = stateSuccesses.getAndIncrement();
            int day = Math.min(3, successfulIndex / 2); // each day is deliberately observed twice
            json(exchange, 200, "{\"day\":" + day
                    + ",\"agents\":[{\"kind\":0,\"pos\":0,\"fuel\":8},"
                    + "{\"kind\":1,\"pos\":2,\"fuel\":null}],"
                    + "\"others\":[{}],\"traffics\":[]}");
        });
        context("actions", exchange -> {
            count("actions");
            String requestBody = body(exchange);
            actionBodies.add(requestBody);
            if (!isExplicitFullDayPlan(requestBody)) {
                json(exchange, 200, """
                        {"type":"action_result","valid":false,
                         "reason":"E_STEP_OVERFLOW: explicit plan must consume exactly 3 steps"}
                        """);
            } else {
                json(exchange, 200, actionResult(Math.min(3, calls.get("actions").get() - 1),
                        "actions-test-" + calls.get("actions").get()));
            }
        });
        context("result", exchange -> {
            count("result");
            json(exchange, 200, "{\"status\":\"FINAL\",\"score\":0}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void completesFourDayFakeMatchWithoutDuplicateSubmissions() throws Exception {
        RuntimeConfig config = new RuntimeConfig(
                "http://localhost:" + server.getAddress().getPort(),
                "m-fake",
                "fake-token",
                Duration.ofMillis(200),
                Duration.ofSeconds(2));
        MatchRuntime runtime = new MatchRuntime(
                config.matchId(),
                new ProconHttpClient(
                        config.baseUrl(), config.matchId(), config.token(),
                        config.connectTimeout(), config.httpTimeout()),
                config.pollInterval(),
                ignored -> { },
                new vn.ptit.procon.protocol.SetupMapper(),
                new vn.ptit.procon.protocol.DayStateMapper(),
                new SmokeAssignmentPolicy(),
                new vn.ptit.procon.engine.PlanValidator(),
                new vn.ptit.procon.engine.DaySimulator(),
                new ParityRecorder());

        MatchRuntimeResult result = runtime.run();

        assertEquals(4, result.submittedDays());
        assertEquals(2, calls.get("setup").get());
        assertEquals(2, assignmentAttempts.get());
        assertEquals(3, calls.get("start").get());
        assertEquals(7, calls.get("state").get());
        assertEquals(4, calls.get("actions").get());
        assertEquals(List.of("[[-3],[-3]]", "[[-3],[-3]]", "[[-3],[-3]]", "[[-3],[-3]]"), actionBodies);
        assertEquals(1, calls.get("result").get());
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
        assertEquals(1, result.rateLimitOccurrences());
        assertEquals(3, result.parityComparisons().size());
        assertTrue(result.parityComparisons().stream()
                .allMatch(comparison -> comparison.position() == ParityStatus.MATCH
                        && comparison.patrolFuel() == ParityStatus.MATCH));
    }

    @Test
    void baselineModeSubmitsMovementDirectionsForReachableSpot() throws Exception {
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "BASELINE"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(4, result.submittedDays());
        assertEquals(List.of("[[2,-1],[-3]]", "[[2,-1],[-3]]", "[[2,-1],[-3]]", "[[2,-1],[-3]]"),
                actionBodies);
        assertTrue(actionBodies.stream().allMatch(body -> body.contains("2")));
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void brandAwareModeIsAvailableAndSubmitsCompletePlans() throws Exception {
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "BRAND_AWARE"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(4, result.submittedDays());
        assertEquals(List.of("[[2,-1],[-3]]", "[[2,-1],[-3]]", "[[2,-1],[-3]]", "[[2,-1],[-3]]"),
                actionBodies);
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void refuelAwareModeIsAvailableAndPreservesCompletePlans() throws Exception {
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "REFUEL_AWARE"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(4, result.submittedDays());
        assertEquals(List.of("[[2,-1],[-3]]", "[[2,-1],[-3]]", "[[2,-1],[-3]]", "[[2,-1],[-3]]"),
                actionBodies);
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void refuelProbeModeIsAvailableAndPreservesCompletePlans() throws Exception {
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "REFUEL_PROBE"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(4, result.submittedDays());
        assertEquals(List.of("[[2,-1],[-3]]", "[[2,-1],[-3]]", "[[2,-1],[-3]]", "[[2,-1],[-3]]"),
                actionBodies);
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void teamCoordinatedModeCompletesMultiPatrolAssignmentAndRetrievesResult() throws Exception {
        restartForTeamCoordinatedScenario();
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "TEAM_COORDINATED"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(1, result.submittedDays());
        // PATROL 0 takes B (RIGHT), leaving constrained A for PATROL 1; PATROL 2 takes C.
        assertEquals(List.of("[[2],[2,-1],[2],[-2]]"), actionBodies);
        assertEquals(1, calls.get("actions").get());
        assertEquals(1, calls.get("result").get());
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void anytimeModeSubmitsCompletePlansAndRetrievesResult() throws Exception {
        restartForTeamCoordinatedScenario();
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "ANYTIME"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(1, result.submittedDays());
        assertEquals(List.of("[[2],[2,-1],[2],[-2]]"), actionBodies);
        assertEquals(1, calls.get("actions").get());
        assertEquals(1, calls.get("result").get());
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void anytimeHarvestModeLogsUnavailableAuthoritativeUdonWithoutSecrets() throws Exception {
        restartForTeamCoordinatedScenario();
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "ANYTIME_HARVEST"));
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        MatchRuntimeResult result;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            result = new MatchRuntime(config).run();
        } finally {
            System.setOut(originalOut);
        }

        String logs = captured.toString(StandardCharsets.UTF_8);
        assertEquals(1, result.submittedDays());
        assertEquals(List.of("[[2],[2,-1],[2],[-2]]"), actionBodies);
        assertTrue(logs.contains("UDON_OBSERVABILITY matchId=m-fake day=0 "
                + "predictedDayUdon=3 authoritativeActual=UNAVAILABLE"));
        assertTrue(logs.contains("ANYTIME_HARVEST_DONE"));
        assertFalse(logs.contains("OTHERS_SHAPE"));
        assertFalse(logs.contains("OTHERS_VALUES"));
        assertFalse(logs.contains("OTHERS_KIND_VALUES"));
        assertFalse(logs.contains("fake-token"));
    }

    @Test
    void optInOthersDiagnosticLogsOnlyBoundedShape() throws Exception {
        restartForTeamCoordinatedScenario();
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "TEAM_COORDINATED",
                "PROCON_OTHERS_SHAPE_DIAGNOSTICS", "true"));
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new MatchRuntime(config).run();
        } finally {
            System.setOut(originalOut);
        }

        String logs = captured.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("OTHERS_SHAPE matchId=m-fake day=0 nodeType=ARRAY "
                + "entries=1 shape=ARRAY[OBJECT{}] truncated=false"));
        assertEquals(1, logs.lines().filter(line -> line.startsWith("OTHERS_SHAPE ")).count());
        assertFalse(logs.contains("OTHERS_VALUES"));
        assertFalse(logs.contains("OTHERS_KIND_VALUES"));
        assertFalse(logs.contains("fake-token"));
    }

    @Test
    void optInOthersValuesLogsOnlyKnownLiveFieldsIndependentlyFromShape() throws Exception {
        restartForTeamCoordinatedScenario("""
                [{"id":12,"agents":[
                    {"pos":3,"kind":0,"fuel":4,"secret":"never-log-value"},
                    {"pos":31,"kind":9,"fuel":47}]},
                 {"id":4,"agents":[{"pos":5,"kind":1,"fuel":3}]},
                 {"id":19,"agents":[]}]
                """);
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "TEAM_COORDINATED",
                "PROCON_OTHERS_VALUE_DIAGNOSTICS", "true"));
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new MatchRuntime(config).run();
        } finally {
            System.setOut(originalOut);
        }

        String logs = captured.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("OTHERS_VALUES matchId=m-fake day=0 group=0 rawId=4 agents=1 "
                + "values=[{index=0,pos=5,rawKind=1,fuel=3,positionValid=true,"
                + "withinOwnPatrolFuelRange=true}]"));
        assertTrue(logs.contains("OTHERS_VALUES matchId=m-fake day=0 group=1 rawId=12 agents=2 "
                + "values=[{index=0,pos=3,rawKind=0,fuel=4,positionValid=true,"
                + "withinOwnPatrolFuelRange=true},{index=1,pos=31,rawKind=9,fuel=47,"
                + "positionValid=false,withinOwnPatrolFuelRange=false}]"));
        assertTrue(logs.contains(
                "OTHERS_KIND_VALUES matchId=m-fake day=0 values=[0, 1, 9] truncated=false"));
        assertFalse(logs.contains("OTHERS_SHAPE"));
        assertFalse(logs.contains("never-log-value"));
        assertFalse(logs.contains("fake-token"));
        assertFalse(logs.contains("Authorization"));
    }

    @Test
    void anytimeContentionModeUsesMappedOtherStateAndRetrievesResult() throws Exception {
        restartForTeamCoordinatedScenario("""
                [{"id":1,"agents":[
                    {"pos":1,"kind":0,"fuel":41},
                    {"pos":3,"kind":0,"fuel":43},
                    {"pos":4,"kind":0,"fuel":27},
                    {"pos":5,"kind":1,"fuel":60}]}]
                """);
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "ANYTIME_CONTENTION"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(1, result.submittedDays());
        assertEquals(1, calls.get("actions").get());
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void plannerFactorySelectsArrivalContentionAnytimePlanner() {
        assertInstanceOf(
                ArrivalContentionAnytimePlanner.class,
                MatchRuntime.plannerFor(PlannerMode.ANYTIME_ARRIVAL_CONTENTION, false));
    }

    @Test
    void anytimeArrivalContentionModeSubmitsCompletePlanAndRetrievesResult() throws Exception {
        restartForTeamCoordinatedScenario("""
                [{"id":1,"agents":[
                    {"pos":1,"kind":0,"fuel":41},
                    {"pos":3,"kind":0,"fuel":43},
                    {"pos":4,"kind":0,"fuel":27},
                    {"pos":5,"kind":1,"fuel":60}]}]
                """);
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "ANYTIME_ARRIVAL_CONTENTION"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(1, result.submittedDays());
        assertEquals(List.of("[[2],[2,-1],[2],[-2]]"), actionBodies);
        assertEquals(1, calls.get("actions").get());
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void plannerFactorySelectsWeightedArrivalContentionAnytimePlanner() {
        assertInstanceOf(
                WeightedArrivalContentionAnytimePlanner.class,
                MatchRuntime.plannerFor(PlannerMode.ANYTIME_WEIGHTED_ARRIVAL_CONTENTION, false));
    }

    @Test
    void anytimeWeightedArrivalContentionModeSubmitsCompletePlanAndRetrievesResult() throws Exception {
        restartForTeamCoordinatedScenario("""
                [{"id":1,"agents":[
                    {"pos":1,"kind":0,"fuel":41},
                    {"pos":3,"kind":0,"fuel":43},
                    {"pos":4,"kind":0,"fuel":27},
                    {"pos":5,"kind":1,"fuel":60}]}]
                """);
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "ANYTIME_WEIGHTED_ARRIVAL_CONTENTION"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(1, result.submittedDays());
        assertEquals(List.of("[[2],[2,-1],[2],[-2]]"), actionBodies);
        assertEquals(1, calls.get("actions").get());
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void plannerFactorySelectsRiskAdjustedAnytimePlanner() {
        assertInstanceOf(
                RiskAdjustedAnytimePlanner.class,
                MatchRuntime.plannerFor(PlannerMode.ANYTIME_RISK_ADJUSTED, false));
    }

    @Test
    void plannerFactorySelectsIntentAwareAnytimePlanner() {
        assertInstanceOf(
                IntentAwareAnytimePlanner.class,
                MatchRuntime.plannerFor(PlannerMode.ANYTIME_INTENT_AWARE, false));
    }

    @Test
    void plannerFactorySelectsDiverseIntentAwareAnytimePlanner() {
        assertInstanceOf(
                vn.ptit.procon.planner.DiverseIntentAwareAnytimePlanner.class,
                MatchRuntime.plannerFor(PlannerMode.ANYTIME_DIVERSE_INTENT_AWARE, false));
    }

    @Test
    void plannerFactorySelectsStratifiedIntentAwareAnytimePlanner() {
        assertInstanceOf(
                vn.ptit.procon.planner.StratifiedIntentAwareAnytimePlanner.class,
                MatchRuntime.plannerFor(PlannerMode.ANYTIME_STRATIFIED_INTENT_AWARE, false));
    }

    @Test
    void plannerFactorySelectsCommitmentAwareStratifiedPlanner() {
        assertInstanceOf(
                vn.ptit.procon.planner.CommitmentAwareStratifiedPlanner.class,
                MatchRuntime.plannerFor(PlannerMode.ANYTIME_STRATIFIED_COMMITMENT_AWARE, false));
    }

    @Test
    void anytimeRiskAdjustedModeSubmitsCompletePlanAndRetrievesResult() throws Exception {
        restartForTeamCoordinatedScenario("""
                [{"id":1,"agents":[
                    {"pos":1,"kind":0,"fuel":41},
                    {"pos":3,"kind":0,"fuel":43},
                    {"pos":4,"kind":0,"fuel":27},
                    {"pos":5,"kind":1,"fuel":60}]}]
                """);
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "ANYTIME_RISK_ADJUSTED"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(1, result.submittedDays());
        assertEquals(List.of("[[2],[2,-1],[2],[-2]]"), actionBodies);
        assertEquals(1, calls.get("actions").get());
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void anytimeIntentAwareModeSubmitsCompletePlanAndRetrievesResult() throws Exception {
        restartForTeamCoordinatedScenario("""
                [{"id":1,"agents":[
                    {"pos":1,"kind":0,"fuel":41},
                    {"pos":3,"kind":0,"fuel":43},
                    {"pos":4,"kind":0,"fuel":27},
                    {"pos":5,"kind":1,"fuel":60}]}]
                """);
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "ANYTIME_INTENT_AWARE"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(1, result.submittedDays());
        assertEquals(List.of("[[2],[2,-1],[2],[-2]]"), actionBodies);
        assertEquals(1, calls.get("actions").get());
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void contentionDiagnosticsAreOptInBoundedAndContainNoSecrets() throws Exception {
        restartForTeamCoordinatedScenario("""
                [{"id":1,"agents":[
                    {"pos":1,"kind":0,"fuel":41},
                    {"pos":3,"kind":0,"fuel":43},
                    {"pos":4,"kind":0,"fuel":27},
                    {"pos":5,"kind":1,"fuel":60,"secret":"never-log-this"}]}]
                """);
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "ANYTIME_CONTENTION",
                "PROCON_CONTENTION_DIAGNOSTICS", "true"));
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new MatchRuntime(config).run();
        } finally {
            System.setOut(originalOut);
        }

        String logs = captured.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("CONTENTION_SUMMARY day=0 observedGroups=1 observedAgents=4 "
                + "spotsConsidered=3 safeSpots="));
        assertTrue(logs.contains("CONTENTION_SPOT day=0 spot=1"));
        assertTrue(logs.contains("CONTENTION_CANDIDATE day=0"));
        assertTrue(logs.contains("ANYTIME_CONTENTION_DONE day=0"));
        assertTrue(logs.contains("tiedProjected="));
        assertTrue(logs.contains("stronglyContestedProjected="));
        assertTrue(logs.lines().filter(line -> line.startsWith("CONTENTION_SPOT ")).count() <= 8);
        assertTrue(logs.lines().filter(line -> line.startsWith("CONTENTION_CANDIDATE ")).count() <= 4);
        assertFalse(logs.contains("fake-token"));
        assertFalse(logs.contains("never-log-this"));
        assertFalse(logs.contains("Authorization"));
    }

    @Test
    void fakeServerRejectsIncompletePlanAndAcceptsExplicitPadding() throws Exception {
        ProconHttpClient client = new ProconHttpClient(
                "http://localhost:" + server.getAddress().getPort(),
                "m-fake",
                "fake-token",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2));
        TeamPlan incomplete = new TeamPlan(Map.of(
                new AgentId(0), List.of(new MoveAction(Direction.RIGHT)),
                new AgentId(1), List.of(new WaitAction(3))));
        TeamPlan padded = new TeamPlan(Map.of(
                new AgentId(0), List.of(new MoveAction(Direction.RIGHT), new WaitAction(1)),
                new AgentId(1), List.of(new WaitAction(3))));

        SubmissionResult rejected = client.postActions(incomplete, 2);
        SubmissionResult accepted = client.postActions(padded, 2);

        assertFalse(rejected.valid());
        assertTrue(accepted.valid());
        assertEquals(List.of("[[2],[-3]]", "[[2,-1],[-3]]"), actionBodies);
    }

    private void restartForTeamCoordinatedScenario() throws IOException {
        restartForTeamCoordinatedScenario("[{}]");
    }

    private void restartForTeamCoordinatedScenario(String others) throws IOException {
        server.stop(0);
        calls.clear();
        actionBodies.clear();
        assignmentAttempts.set(0);
        stateSuccesses.set(0);
        AtomicBoolean actionsAccepted = new AtomicBoolean();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        context("setup", exchange -> {
            count("setup");
            json(exchange, 200, """
                    {"daySteps":[2],
                     "map":{"width":6,"height":1,"cells":[[1,0,0,0,0,0]]},
                     "spots":[
                       {"brand":"A","pos":1,"stocks":1},
                       {"brand":"B","pos":3,"stocks":1},
                       {"brand":"C","pos":5,"stocks":1}],
                     "agents":[2,0,4,5],"fuelLimits":5}
                    """);
        });
        context("assignment", exchange -> {
            count("assignment");
            assertEquals("[0,0,0,1]", body(exchange));
            json(exchange, 200, actionResult(0, "team-assignment"));
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
            json(exchange, 200, """
                    {"day":0,
                     "agents":[
                       {"kind":0,"pos":2,"fuel":5},
                       {"kind":0,"pos":0,"fuel":5},
                       {"kind":0,"pos":4,"fuel":5},
                       {"kind":1,"pos":5,"fuel":null}],
                     "others":%s,
                     "traffics":[{"pos":0,"status":0}]}
                    """.formatted(others));
        });
        context("actions", exchange -> {
            count("actions");
            String requestBody = body(exchange);
            actionBodies.add(requestBody);
            assertEquals("[[2],[2,-1],[2],[-2]]", requestBody);
            actionsAccepted.set(true);
            json(exchange, 200, actionResult(0, "team-actions"));
        });
        context("result", exchange -> {
            count("result");
            json(exchange, 200, "{\"status\":\"FINAL\",\"score\":3}");
        });
        server.start();
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

    private String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String actionResult(int day, String submissionId) {
        return """
                {"day":%d,"match_id":"m-fake","protocol_version":"v0.1-draft",
                 "reason":"","response_ms":10,"submission_id":"%s",
                 "type":"action_result","valid":true}
                """.formatted(day, submissionId);
    }

    private boolean isExplicitFullDayPlan(String requestBody) throws IOException {
        JsonNode agentPlans = objectMapper.readTree(requestBody);
        if (!agentPlans.isArray() || agentPlans.size() != 2) {
            return false;
        }
        int[] positions = {0, 2};
        for (int agent = 0; agent < agentPlans.size(); agent++) {
            JsonNode actions = agentPlans.get(agent);
            if (!actions.isArray()) {
                return false;
            }
            int usedSteps = 0;
            int position = positions[agent];
            for (JsonNode actionNode : actions) {
                int action = actionNode.intValue();
                if (action < 0) {
                    usedSteps += -action;
                    continue;
                }
                usedSteps += 2;
                if (action == Direction.RIGHT.code()) {
                    position++;
                } else if (action == Direction.LEFT.code()) {
                    position--;
                } else {
                    return false;
                }
            }
            if (usedSteps != 3) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
