package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.AgentStepUsage;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.engine.WireActionDuration;
import vn.ptit.procon.planner.ArrivalContentionAnytimePlanner;
import vn.ptit.procon.planner.CoupledCompetitiveMarginPlanner;
import vn.ptit.procon.planner.IntentAwareAnytimePlanner;
import vn.ptit.procon.planner.RiskAdjustedAnytimePlanner;
import vn.ptit.procon.planner.WeightedArrivalContentionAnytimePlanner;
import vn.ptit.procon.protocol.ActionEncoder;
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
        restartForSingleDayModeScenario();
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "BASELINE"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(1, result.submittedDays());
        assertEquals(List.of("[[2,-1],[-3]]"), actionBodies);
        assertTrue(actionBodies.stream().allMatch(body -> body.contains("2")));
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void brandAwareModeIsAvailableAndSubmitsCompletePlans() throws Exception {
        restartForSingleDayModeScenario();
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "BRAND_AWARE"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(1, result.submittedDays());
        assertEquals(List.of("[[2,-1],[-3]]"), actionBodies);
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void refuelAwareModeIsAvailableAndPreservesCompletePlans() throws Exception {
        restartForSingleDayModeScenario();
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "REFUEL_AWARE"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(1, result.submittedDays());
        assertEquals(List.of("[[2,-1],[-3]]"), actionBodies);
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void refuelProbeModeIsAvailableAndPreservesCompletePlans() throws Exception {
        restartForSingleDayModeScenario();
        RuntimeConfig config = RuntimeConfig.fromEnvironment(Map.of(
                "PROCON_BASE_URL", "http://localhost:" + server.getAddress().getPort(),
                "PROCON_MATCH_ID", "m-fake",
                "PROCON_TOKEN", "fake-token",
                "PROCON_POLL_INTERVAL_MS", "200",
                "PROCON_HTTP_TIMEOUT_SECONDS", "2",
                "PROCON_PLANNER_MODE", "REFUEL_PROBE"));

        MatchRuntimeResult result = new MatchRuntime(config).run();

        assertEquals(1, result.submittedDays());
        assertEquals(List.of("[[2,-1],[-3]]"), actionBodies);
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
    void plannerFactorySelectsSemiCommitmentAwareStratifiedPlanner() {
        assertInstanceOf(
                vn.ptit.procon.planner.SemiCommitmentAwareStratifiedPlanner.class,
                MatchRuntime.plannerFor(
                        PlannerMode.ANYTIME_STRATIFIED_SEMI_COMMITMENT_AWARE, false));
    }

    @Test
    void plannerFactorySelectsHarvestHorizonAwareSemiCommitmentPlanner() {
        assertInstanceOf(
                vn.ptit.procon.planner.HarvestHorizonAwareSemiCommitmentPlanner.class,
                MatchRuntime.plannerFor(
                        PlannerMode.ANYTIME_STRATIFIED_SEMI_COMMITMENT_HARVEST_HORIZON_AWARE,
                        false));
    }

    @Test
    void plannerFactorySelectsRelativeMarginAwarePlanner() {
        assertInstanceOf(
                vn.ptit.procon.planner.RelativeMarginAwarePlanner.class,
                MatchRuntime.plannerFor(PlannerMode.ANYTIME_STRATIFIED_RELATIVE_MARGIN_AWARE, false));
    }

    @Test
    void parityMismatchAfterAcceptedDayNeverReachesThePlannerOrSubmitsFromStaleState()
            throws Exception {
        restartForParityDesyncScenario(99, 1);
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        MatchRuntime runtime = runtimeWith(planner);

        List<IllegalStateException> failure = new ArrayList<>();
        String logs = capturing(() -> failure.add(
                assertThrows(IllegalStateException.class, runtime::run)));

        // Day 0 planned and accepted; day 1 disagreed on position and PATROL fuel, so it is refused.
        assertEquals(1, planner.calls.get());
        assertEquals(1, calls.get("actions").get());
        assertEquals(List.of("[[-3],[-3]]"), actionBodies);
        assertEquals(1 + 1 + 4, calls.get("state").get());
        assertEquals(1, lines(logs, "PARITY_RESYNC_REQUIRED "));
        assertEquals(4, lines(logs, "PARITY_RESYNC_ATTEMPT "));
        assertEquals(1, lines(logs, "PARITY_RESYNC_FAILED "));
        assertEquals(1, lines(logs, "ACTIONS_ACCEPTED "));
        assertFalse(logs.contains("PARITY_RESYNC_RECOVERED"));
        assertTrue(logs.contains("PARITY_RESYNC_REQUIRED matchId=m-fake day=1 submittedDay=0 "
                + "position=MISMATCH patrolFuel=MISMATCH agentMismatches=1 maxAttempts=4"));
        assertTrue(logs.contains("PARITY_RESYNC_FAILED matchId=m-fake day=1 attempts=4 "
                + "reason=PARITY_STILL_DISAGREES"));
        assertTrue(failure.get(0).getMessage().contains("Refusing to plan or submit day 1"));
        assertFalse(logs.contains("fake-token"));
    }

    @Test
    void boundedParityResyncRecoversAndOnlyThenPlansTheAuthoritativeDay() throws Exception {
        restartForParityDesyncScenario(1, 1);
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        MatchRuntime runtime = runtimeWith(planner);

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtime.run()));
        MatchRuntimeResult result = results.get(0);

        assertEquals(2, result.submittedDays());
        assertEquals(2, planner.calls.get());
        assertEquals(3, calls.get("state").get());
        assertEquals(1, lines(logs, "PARITY_RESYNC_REQUIRED "));
        assertEquals(1, lines(logs, "PARITY_RESYNC_RECOVERED "));
        assertEquals(0, lines(logs, "PARITY_RESYNC_FAILED "));
        assertTrue(logs.contains("PARITY_RESYNC_RECOVERED matchId=m-fake day=1 submittedDay=0 "
                + "attempt=1 position=MATCH patrolFuel=MATCH"));
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
    }

    @Test
    void actionResponseDayOutsideCommonWindowIsDiagnosticOnly() throws Exception {
        restartForParityDesyncScenario(0, 3);
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtimeWith(planner).run()));

        // Day 1 was accepted even though its action_result reported day 3.
        assertEquals(2, calls.get("actions").get());
        assertEquals(2, results.get(0).submittedDays());
        assertEquals(2, lines(logs, "ACTION_RESPONSE_DAY_OBSERVED "));
        assertEquals(1, lines(logs, "ACTION_RESPONSE_DAY_ANOMALY "));
        assertTrue(logs.contains("ACTION_RESPONSE_DAY_ANOMALY matchId=m-fake submittedDay=1 "
                + "responseDay=3 delta=2"));
        assertEquals(2, lines(logs, "ACTIONS_ACCEPTED "));
        assertFalse(logs.contains("PARITY_RESYNC_REQUIRED"));
        assertFalse(logs.contains("fake-token"));
    }

    @Test
    void submittedDay0ResponseDay0IsAccepted() throws Exception {
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        restartForResponseDayScenario(1, 0, List.of(0));

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtimeWith(planner).run()));

        assertEquals(1, results.get(0).submittedDays());
        assertEquals(1, lines(logs, "ACTIONS_ACCEPTED matchId=m-fake day=0"));
        assertEquals(1, lines(logs, "ACTION_RESPONSE_DAY_OBSERVED "));
        assertEquals(0, lines(logs, "ACTION_RESPONSE_DAY_ANOMALY "));
        assertEquals("FINAL", results.get(0).authoritativeResult().get("status").textValue());
    }

    /** The exact m-4277 shape: day 0 submitted, HTTP 200 action_result reporting day 1. */
    @Test
    void submittedDay0ResponseDay1IsAcceptedAsDiagnosticObservation() throws Exception {
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        restartForResponseDayScenario(2, 0, List.of(1, 1));

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtimeWith(planner).run()));

        assertEquals(2, results.get(0).submittedDays());
        assertEquals(2, planner.calls.get());
        assertEquals(2, lines(logs, "ACTION_RESPONSE_DAY_OBSERVED "));
        assertEquals(0, lines(logs, "ACTION_RESPONSE_DAY_ANOMALY "));
        assertTrue(logs.contains(
                "ACTION_RESPONSE_DAY_OBSERVED matchId=m-fake submittedDay=0 responseDay=1 delta=1"));
        assertEquals("FINAL", results.get(0).authoritativeResult().get("status").textValue());
        assertFalse(logs.contains("fake-token"));
    }

    @Test
    void submittedDay1ResponseDay2IsAcceptedAsDiagnosticObservation() throws Exception {
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        restartForResponseDayScenario(2, 0, List.of(0, 2));

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtimeWith(planner).run()));

        assertEquals(2, results.get(0).submittedDays());
        assertEquals(2, lines(logs, "ACTION_RESPONSE_DAY_OBSERVED "));
        assertEquals(0, lines(logs, "ACTION_RESPONSE_DAY_ANOMALY "));
        assertTrue(logs.contains(
                "ACTION_RESPONSE_DAY_OBSERVED matchId=m-fake submittedDay=1 responseDay=2 delta=1"));
        assertEquals("FINAL", results.get(0).authoritativeResult().get("status").textValue());
    }

    /** Any response day is diagnostic only; authoritative state still controls progression. */
    @Test
    void submittedDay1ResponseDay3IsAcceptedAndMarkedAnomalous() throws Exception {
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        restartForResponseDayScenario(2, 0, List.of(0, 3));

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtimeWith(planner).run()));

        assertEquals(2, results.get(0).submittedDays());
        assertEquals(2, lines(logs, "ACTION_RESPONSE_DAY_OBSERVED "));
        assertEquals(1, lines(logs, "ACTION_RESPONSE_DAY_ANOMALY "));
        assertEquals(2, lines(logs, "ACTIONS_ACCEPTED "));
        assertEquals("FINAL", results.get(0).authoritativeResult().get("status").textValue());
    }

    @Test
    void submittedDay2ResponseDay1IsAcceptedAndMarkedAnomalous() throws Exception {
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        restartForResponseDayScenario(3, 0, List.of(0, 1, 1));

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtimeWith(planner).run()));

        assertEquals(3, results.get(0).submittedDays());
        assertEquals(3, lines(logs, "ACTION_RESPONSE_DAY_OBSERVED "));
        assertEquals(1, lines(logs, "ACTION_RESPONSE_DAY_ANOMALY "));
        assertEquals(3, lines(logs, "ACTIONS_ACCEPTED "));
        assertEquals("FINAL", results.get(0).authoritativeResult().get("status").textValue());
    }

    @Test
    void absentResponseDayPreservesExistingBehavior() throws Exception {
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        restartForResponseDayScenario(2, 0, Collections.singletonList(null));

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtimeWith(planner).run()));

        assertEquals(2, results.get(0).submittedDays());
        assertEquals(2, lines(logs, "ACTIONS_ACCEPTED "));
        assertEquals(2, lines(logs, "ACTION_RESPONSE_DAY_OBSERVED "));
        assertEquals(0, lines(logs, "ACTION_RESPONSE_DAY_ANOMALY "));
        assertEquals("FINAL", results.get(0).authoritativeResult().get("status").textValue());
    }

    /**
     * A response-day observation is informational only: the runtime keeps polling {@code /state}
     * and plans day 1 from the authoritative read.
     */
    @Test
    void acceptedPostAdvanceResponseStillPollsAuthoritativeNextStateBeforePlanning() throws Exception {
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        // One stale read: after day 0 is accepted with responseDay=1, /state still reports day 0.
        restartForResponseDayScenario(2, 1, List.of(1, 1));

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtimeWith(planner).run()));

        assertEquals(2, results.get(0).submittedDays());
        assertEquals(2, planner.calls.get());
        // Two day-0 reads prove the response observation did not stand in for the next DayState.
        assertEquals(2, lines(logs, "DAY_STATE_RECEIVED matchId=m-fake day=0"));
        assertEquals(1, lines(logs, "DAY_STATE_RECEIVED matchId=m-fake day=1"));
        assertEquals(3, calls.get("state").get());
        assertTrue(logs.indexOf("ACTION_RESPONSE_DAY_OBSERVED matchId=m-fake submittedDay=0")
                        < logs.indexOf("DAY_STATE_RECEIVED matchId=m-fake day=1"),
                "the authoritative day-1 read must follow the response observation");
        assertTrue(logs.indexOf("DAY_STATE_RECEIVED matchId=m-fake day=1")
                        < logs.indexOf("ACTIONS_SUBMITTED matchId=m-fake day=1"),
                "day 1 must be planned only after its authoritative state arrived");
        assertTrue(logs.contains("DAY_ADVANCED matchId=m-fake from=0 to=1"));
    }

    @Test
    void acceptedResponseDoesNotCreateDuplicateSubmission() throws Exception {
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        restartForResponseDayScenario(2, 1, List.of(1, 1));

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtimeWith(planner).run()));

        assertEquals(2, results.get(0).submittedDays());
        assertEquals(2, calls.get("actions").get());
        assertEquals(List.of("[[-3],[-3]]", "[[-3],[-3]]"), actionBodies);
        // Neither the resubmission of N nor a skipped N+1: exactly one submission per real day.
        assertEquals(1, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=0"));
        assertEquals(1, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=1"));
        assertEquals(1, lines(logs, "ACTIONS_ACCEPTED matchId=m-fake day=0"));
        assertEquals(1, lines(logs, "ACTIONS_ACCEPTED matchId=m-fake day=1"));
        assertEquals(0, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=2"));
    }

    @Test
    void m5042ResponseDayTwoResyncsFromAuthoritativeStateWithoutDuplicateDayZeroPost()
            throws Exception {
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        restartForResponseDayScenario(2, 1, List.of(2, 1));

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtimeWith(planner).run()));

        assertEquals(2, results.get(0).submittedDays());
        assertEquals(2, planner.calls.get());
        assertEquals(2, calls.get("actions").get());
        assertEquals(1, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=0"));
        assertEquals(1, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=1"));
        assertEquals(2, lines(logs, "ACTION_RESPONSE_DAY_OBSERVED "));
        assertEquals(1, lines(logs, "ACTION_RESPONSE_DAY_ANOMALY "));
        assertEquals(0, lines(logs, "ACTION_RESPONSE_DAY_MISMATCH "));
        assertEquals(2, lines(logs, "DAY_STATE_RECEIVED matchId=m-fake day=0"));
        assertEquals(1, lines(logs, "DAY_STATE_RECEIVED matchId=m-fake day=1"));
        assertTrue(logs.contains("DAY_ADVANCED matchId=m-fake from=0 to=1"));
    }

    @Test
    void authoritativeDayGapAfterAcceptedActionFailsClosedWithoutInventingSkippedSubmission()
            throws Exception {
        restartForAuthoritativeGapScenario();
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());

        List<IllegalStateException> failure = new ArrayList<>();
        String logs = capturing(() -> failure.add(
                assertThrows(IllegalStateException.class, () -> runtimeWith(planner).run())));

        assertEquals(1, calls.get("actions").get());
        assertEquals(1, planner.calls.get());
        assertEquals(1, lines(logs, "ACTIONS_ACCEPTED matchId=m-fake day=0"));
        assertEquals(1, lines(logs, "ACTION_RESPONSE_DAY_ANOMALY "));
        assertEquals(1, lines(logs, "AUTHORITATIVE_DAY_GAP "));
        assertTrue(logs.contains("AUTHORITATIVE_DAY_GAP matchId=m-fake submittedDay=0 "
                + "authoritativeDay=2 skippedDays=1..1"));
        assertEquals(0, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=1"));
        assertEquals(0, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=2"));
        assertTrue(failure.getFirst().getMessage().contains("refusing to pretend skipped days were submitted"));
    }

    @Test
    void staleAuthoritativeStateIsRetriedWithoutResubmittingThePriorDay() throws Exception {
        restartForStaleAuthoritativeStateScenario();
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtimeWith(planner).run()));

        assertEquals(3, results.getFirst().submittedDays());
        assertEquals(3, calls.get("actions").get());
        assertEquals(1, lines(logs, "AUTHORITATIVE_STATE_STALE "));
        assertEquals(1, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=0"));
        assertEquals(1, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=1"));
        assertEquals(1, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=2"));
        assertTrue(logs.contains("AUTHORITATIVE_STATE_STALE matchId=m-fake observedDay=0 "
                + "lastObservedDay=1 submittedDay=1 attempt=1"));
    }

    @Test
    void everyObservedResponseDayIsAcceptanceDiagnosticOnly() throws Exception {
        for (int responseDay : List.of(0, 1, 2, 3)) {
            restartForResponseDayScenario(2, 0, List.of(responseDay, 1));
            CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
            List<MatchRuntimeResult> results = new ArrayList<>();
            String logs = capturing(() -> results.add(runtimeWith(planner).run()));

            assertEquals(2, results.getFirst().submittedDays());
            assertEquals(2, lines(logs, "ACTION_RESPONSE_DAY_OBSERVED "));
            assertEquals(responseDay >= 2 ? 1 : 0,
                    lines(logs, "ACTION_RESPONSE_DAY_ANOMALY "));
            assertEquals(0, lines(logs, "ACTION_RESPONSE_DAY_MISMATCH "));
            assertEquals(1, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=0"));
            assertEquals(1, lines(logs, "ACTIONS_SUBMITTED matchId=m-fake day=1"));
        }
    }

    /**
     * An accepted response-day observation must not disable the bounded parity-resync safety path:
     * {@code lastSubmittedDay} stays at N, so the authoritative day N+1 is still fully checked.
     */
    @Test
    void parityMismatchAfterAnAcceptedResponseStillTriggersBoundedResync()
            throws Exception {
        restartForParityDesyncScenario(99, 1, 1);
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        MatchRuntime runtime = runtimeWith(planner);

        List<IllegalStateException> failure = new ArrayList<>();
        String logs = capturing(() -> failure.add(
                assertThrows(IllegalStateException.class, runtime::run)));

        assertEquals(1, lines(logs, "ACTION_RESPONSE_DAY_OBSERVED "));
        assertEquals(0, lines(logs, "ACTION_RESPONSE_DAY_MISMATCH "));
        assertEquals(1, planner.calls.get());
        assertEquals(1, calls.get("actions").get());
        assertEquals(1, lines(logs, "PARITY_RESYNC_REQUIRED "));
        assertEquals(4, lines(logs, "PARITY_RESYNC_ATTEMPT "));
        assertEquals(1, lines(logs, "PARITY_RESYNC_FAILED "));
        assertTrue(logs.contains("PARITY_RESYNC_FAILED matchId=m-fake day=1 attempts=4 "
                + "reason=PARITY_STILL_DISAGREES"));
        assertTrue(failure.get(0).getMessage().contains("Refusing to plan or submit day 1"));
        assertFalse(logs.contains("fake-token"));
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

    /**
     * m-4699 regression: server responds with valid=false, reason="E_STEP_OVERFLOW", day=3 on day 0 submission.
     * Must surface SERVER_ACTION_REJECTED FIRST, and must NEVER emit ACTION_RESPONSE_DAY_MISMATCH.
     */
    @Test
    void m4699InvalidResponseSurfacesServerActionRejectedBeforeDayMismatch() throws Exception {
        restartForInvalidActionScenario(60, 3, "E_STEP_OVERFLOW");
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        MatchRuntime runtime = runtimeWith(planner);

        List<IllegalStateException> failure = new ArrayList<>();
        String logs = capturing(() -> failure.add(
                assertThrows(IllegalStateException.class, runtime::run)));

        assertEquals(1, calls.get("actions").get());
        assertEquals(1, lines(logs, "SERVER_ACTION_REJECTED "));
        assertEquals(0, lines(logs, "ACTION_RESPONSE_DAY_MISMATCH "));
        assertEquals(0, lines(logs, "ACTIONS_ACCEPTED "));
        assertTrue(logs.contains("SERVER_ACTION_REJECTED matchId=m-fake submittedDay=0 "
                + "responseDay=3 httpStatus=200 reason=E_STEP_OVERFLOW"));
        assertTrue(failure.get(0).getMessage().contains(
                "Server rejected day 0 actions: type=action_result httpStatus=200 day=3 reason=E_STEP_OVERFLOW"));
        assertEquals(1, planner.calls.get());
    }

    /**
     * m-4703 regression: dayBudget=60, locally valid plan reaching boundary step 60.
     * Plan is validated, audits duration per agent, and submits safely without overflow.
     */
    @Test
    void m4703BoundaryDay60PlanReachesBoundarySafelyAndAuditsDuration() throws Exception {
        restartFor60StepBoundaryScenario();
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        MatchRuntime runtime = runtimeWith(planner);

        List<MatchRuntimeResult> results = new ArrayList<>();
        String logs = capturing(() -> results.add(runtime.run()));

        assertEquals(1, results.get(0).submittedDays());
        assertEquals(1, calls.get("actions").get());
        assertEquals(2, lines(logs, "ACTION_DURATION_AUDIT "));
        assertEquals(0, lines(logs, "WIRE_ACTION_DURATION_INVALID "));
        assertTrue(logs.contains("ACTION_DURATION_AUDIT matchId=m-fake day=0 agent=0 dayBudget=60 "
                + "plannerDuration=60 validatedDuration=60 encodedDuration=60 actionCount=1 finalAction=WAIT(60) paddingApplied=false"));
        assertTrue(logs.contains("ACTION_DURATION_AUDIT matchId=m-fake day=0 agent=1 dayBudget=60 "
                + "plannerDuration=60 validatedDuration=60 encodedDuration=60 actionCount=1 finalAction=WAIT(60) paddingApplied=false"));
        assertEquals(1, lines(logs, "ACTIONS_ACCEPTED matchId=m-fake day=0"));
        assertEquals("FINAL", results.get(0).authoritativeResult().get("status").textValue());
    }

    /**
     * Exact m-4703 live rejection reproduction:
     * When the server rejects day 0 with:
     * E_STEP_OVERFLOW (xe 0, bước 60): kế hoạch thừa lệnh: tổng số bước vượt quá số bước trong ngày
     * MatchRuntime must surface SERVER_ACTION_REJECTED with exact server reason, fail closed,
     * not advance lastSubmittedDay, and not retry.
     */
    @Test
    void m4703ExactServerStepOverflowRejectionIsSurfacedCleanly() throws Exception {
        restartForInvalidActionScenario(
                60, 0, "E_STEP_OVERFLOW (xe 0, bước 60): kế hoạch thừa lệnh: tổng số bước vượt quá số bước trong ngày");
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        MatchRuntime runtime = runtimeWith(planner);

        List<IllegalStateException> failure = new ArrayList<>();
        String logs = capturing(() -> failure.add(
                assertThrows(IllegalStateException.class, runtime::run)));

        assertEquals(1, calls.get("actions").get());
        assertEquals(1, lines(logs, "SERVER_ACTION_REJECTED "));
        assertEquals(0, lines(logs, "ACTION_RESPONSE_DAY_MISMATCH "));
        assertEquals(0, lines(logs, "ACTIONS_ACCEPTED "));
        assertTrue(logs.contains("SERVER_ACTION_REJECTED matchId=m-fake submittedDay=0 "
                + "responseDay=0 httpStatus=200 reason=E_STEP_OVERFLOW (xe 0, bước 60): kế hoạch thừa lệnh: tổng số bước vượt quá số bước trong ngày"));
        assertTrue(failure.get(0).getMessage().contains(
                "Server rejected day 0 actions: type=action_result httpStatus=200 day=0 reason=E_STEP_OVERFLOW (xe 0, bước 60): kế hoạch thừa lệnh: tổng số bước vượt quá số bước trong ngày"));
        assertEquals(1, planner.calls.get());
    }

    @Test
    void serverStepOverflowAddsBoundedArrayAndCorrelationDiagnosticsWithoutChangingFailure() throws Exception {
        restartForInvalidActionScenario(
                60, 3,
                "E_STEP_OVERFLOW (xe 3, bước 59): không đủ bước để hoàn thành di chuyển trong ngày "
                        + "(cần 2 bước, ngày còn 1)");
        CountingPlanner planner = new CountingPlanner(new vn.ptit.procon.planner.WaitDayPlanner());
        MatchRuntime runtime = runtimeWith(planner);

        List<IllegalStateException> failure = new ArrayList<>();
        String logs = capturing(() -> failure.add(
                assertThrows(IllegalStateException.class, runtime::run)));

        assertEquals(1, calls.get("actions").get());
        assertTrue(logs.contains("SERVER_ACTION_REJECTED matchId=m-fake submittedDay=0 "
                + "responseDay=3 httpStatus=200 reason=E_STEP_OVERFLOW (xe 3, bước 59):"));
        assertTrue(logs.contains("rejectedAgent=3 serverStep=59 serverRequiredSteps=2 serverRemainingSteps=1"));
        assertTrue(logs.lines().anyMatch(line -> line.contains(
                "REJECTED_ACTION_ARRAY matchId=m-fake submittedDay=0 agent=0")
                && line.contains("actions=[-60]")));
        assertTrue(logs.lines().anyMatch(line -> line.contains(
                "WIRE_MOVEMENT_DURATION_TRACE matchId=m-fake day=0 agent=0")
                && line.contains("actionType=WAIT")
                && line.contains("localStartStep=0")
                && line.contains("localStepCost=60")
                && line.contains("localEndStep=60")));
        assertTrue(failure.getFirst().getMessage().contains("Server rejected day 0 actions"));
    }

    /**
     * Captures the exact production JSON payload and traces agent 0 command-by-command
     * for a 6-agent, 60-step budget match planned by the production CoupledCompetitiveMarginPlanner.
     */
    @Test
    void captureProductionJsonPayloadAndTraceAgent0For60StepBudget() throws Exception {
        Terrain[] terrain = new Terrain[144];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<UdonSpot> spots = List.of(
                new UdonSpot(new BrandId("A"), new Position(10), 2),
                new UdonSpot(new BrandId("B"), new Position(25), 2),
                new UdonSpot(new BrandId("C"), new Position(40), 2),
                new UdonSpot(new BrandId("D"), new Position(60), 2),
                new UdonSpot(new BrandId("E"), new Position(80), 2),
                new UdonSpot(new BrandId("F"), new Position(105), 2));
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));

        List<AgentState> agents = List.of(
                AgentState.patrol(new AgentId(0), new Position(0), 60),
                AgentState.patrol(new AgentId(1), new Position(2), 60),
                AgentState.patrol(new AgentId(2), new Position(4), 60),
                AgentState.patrol(new AgentId(3), new Position(6), 60),
                AgentState.patrol(new AgentId(4), new Position(8), 60),
                AgentState.refuel(new AgentId(5), new Position(12)));

        DayState state = new DayState(
                new StaticMatchData(new HexMap(12, 12, terrain), new DayStepBudgets(new int[] {60}),
                        List.of(), new FuelCapacity(60), spots),
                new DayIndex(0), agents, Map.of(), stock,
                List.of(new ObservedOtherGroup(7, List.of(
                        new ObservedOtherAgent(new Position(100), 0, -1)))));

        CoupledCompetitiveMarginPlanner planner = new CoupledCompetitiveMarginPlanner();
        TeamPlan plan = planner.plan(state);

        PlanValidator validator = new PlanValidator();
        assertTrue(validator.validate(state, plan).valid());

        DaySimulator simulator = new DaySimulator();
        DaySimulationResult simResult = simulator.simulate(state, plan);
        assertTrue(simResult instanceof ValidDaySimulationResult);
        ValidDaySimulationResult validSim = (ValidDaySimulationResult) simResult;

        ActionEncoder encoder = new ActionEncoder();
        List<List<Integer>> encoded = encoder.encode(plan, 6);

        ObjectMapper mapper = new ObjectMapper();
        String jsonPayload = mapper.writeValueAsString(encoded);

        System.out.println("=== PRODUCTION JSON PAYLOAD ===");
        System.out.println(jsonPayload);
        System.out.println("================================");

        for (int i = 0; i < 6; i++) {
            AgentId id = new AgentId(i);
            AgentStepUsage usage = validSim.stepUsage().get(id);
            int duration = usage.totalSteps();
            System.out.println("agent" + i + " wire: " + encoded.get(i) + " duration=" + duration);
            assertTrue(WireActionDuration.isValid(duration, 60));
            assertTrue(duration <= 60);
        }
    }

    private void restartForTeamCoordinatedScenario() throws IOException {
        restartForTeamCoordinatedScenario("[{}]");
    }

    /**
     * Single-day mode-availability scenario for the planners that actually move a PATROL.
     *
     * <p>The shared four-day stub answers {@code /state} with a fixed agent row, so it reports the
     * PATROL still standing on its start cell with a full tank after having accepted a MOVE. That is
     * a physically impossible server and the runtime now correctly refuses to plan from it. A
     * one-day match keeps these tests on the question they actually ask — is the mode reachable and
     * does it submit a complete, server-accepted plan — without asking the runtime to continue past
     * a genuine desync. The multi-day submission loop stays covered by
     * {@link #completesFourDayFakeMatchWithoutDuplicateSubmissions()}, whose WAIT plans keep the
     * fixed state and the prediction in agreement.</p>
     */
    private void restartForSingleDayModeScenario() throws IOException {
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
                    {"daySteps":[3],
                     "map":{"width":3,"height":1,"cells":[[0,0,0]]},
                     "spots":[{"brand":1,"pos":1,"stocks":2}],
                     "agents":[0,2],"fuelLimits":8}
                    """);
        });
        context("assignment", exchange -> {
            count("assignment");
            assertEquals("[0,1]", body(exchange));
            json(exchange, 200, actionResult(0, "single-day-assignment"));
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
            json(exchange, 200, "{\"day\":0"
                    + ",\"agents\":[{\"kind\":0,\"pos\":0,\"fuel\":8},"
                    + "{\"kind\":1,\"pos\":2,\"fuel\":null}],"
                    + "\"others\":[{}],\"traffics\":[]}");
        });
        context("actions", exchange -> {
            count("actions");
            actionBodies.add(body(exchange));
            actionsAccepted.set(true);
            json(exchange, 200, actionResult(0, "single-day-actions"));
        });
        context("result", exchange -> {
            count("result");
            json(exchange, 200, "{\"status\":\"FINAL\",\"score\":0}");
        });
        server.start();
    }

    /**
     * m-4157-shaped desync: day 0 is accepted, then the next authoritative day disagrees with the
     * accepted-day prediction on own position and PATROL fuel.
     *
     * @param mismatchingDayOneStates how many day-1 reads report the disagreeing agent state before
     *                                the state converges back onto the prediction
     * @param dayOneResponseDay       the day reported by {@code /actions} for the day-1 submission
     */
    private void restartForParityDesyncScenario(int mismatchingDayOneStates, int dayOneResponseDay)
            throws IOException {
        restartForParityDesyncScenario(mismatchingDayOneStates, 0, dayOneResponseDay);
    }

    /**
     * As above, with the day-0 {@code /actions} response day chosen too.
     *
     * <p>Passing {@code dayZeroResponseDay = 1} reproduces the m-4277 post-action advancement on top
     * of the desync fixture, which proves that accepting an {@code N + 1} response does not disable
     * the bounded parity-resync path for the authoritative day that follows.</p>
     *
     * @param dayZeroResponseDay the day reported by {@code /actions} for the day-0 submission
     */
    private void restartForParityDesyncScenario(
            int mismatchingDayOneStates, int dayZeroResponseDay, int dayOneResponseDay)
            throws IOException {
        server.stop(0);
        calls.clear();
        actionBodies.clear();
        assignmentAttempts.set(0);
        stateSuccesses.set(0);
        AtomicInteger dayOneReads = new AtomicInteger();
        AtomicInteger acceptedDays = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        context("setup", exchange -> {
            count("setup");
            json(exchange, 200, """
                    {"daySteps":[3,3],
                     "map":{"width":3,"height":1,"cells":[[0,0,0]]},
                     "spots":[{"brand":1,"pos":1,"stocks":2}],
                     "agents":[0,2],"fuelLimits":8}
                    """);
        });
        context("assignment", exchange -> {
            count("assignment");
            assertEquals("[0,1]", body(exchange));
            json(exchange, 200, actionResult(0, "parity-assignment"));
        });
        context("start", exchange -> {
            count("start");
            json(exchange, 200, "{\"started\":true}");
        });
        context("state", exchange -> {
            count("state");
            if (acceptedDays.get() == 0) {
                json(exchange, 200, desyncDayState(0, 0, 8));
                return;
            }
            if (acceptedDays.get() >= 2) {
                json(exchange, 425, "{}");
                return;
            }
            boolean mismatching = dayOneReads.incrementAndGet() <= mismatchingDayOneStates;
            json(exchange, 200, mismatching
                    ? desyncDayState(1, 1, 6)
                    : desyncDayState(1, 0, 8));
        });
        context("actions", exchange -> {
            int attempt = count("actions");
            actionBodies.add(body(exchange));
            int submittedDay = acceptedDays.getAndIncrement();
            json(exchange, 200, actionResult(
                    submittedDay == 0 ? dayZeroResponseDay : dayOneResponseDay,
                    "parity-actions-" + attempt));
        });
        context("result", exchange -> {
            count("result");
            json(exchange, 200, "{\"status\":\"FINAL\",\"score\":0}");
        });
        server.start();
    }

    /**
     * WAIT-only match whose {@code /actions} reports a caller-chosen day, so response-day
     * diagnostics can be probed directly without any other moving part.
     *
     * <p>Every day predicts and observes the same agent row, so parity always agrees and the only
     * thing under test is the response-day contract.</p>
     *
     * @param dayCount        number of days in the match
     * @param staleReadsPerDay how many {@code /state} reads after an accepted submission still report
     *                        the day that was just submitted before the authoritative day advances.
     *                        A positive value proves the runtime waits for the authoritative state
     *                        instead of trusting the {@code action_result} day field
     * @param responseDays    day reported by {@code /actions} per submission, in order; a
     *                        {@code null} entry omits the {@code day} field entirely
     */
    private void restartForResponseDayScenario(
            int dayCount, int staleReadsPerDay, List<Integer> responseDays) throws IOException {
        server.stop(0);
        calls.clear();
        actionBodies.clear();
        assignmentAttempts.set(0);
        stateSuccesses.set(0);
        AtomicInteger acceptedDays = new AtomicInteger();
        AtomicInteger readsSinceSubmission = new AtomicInteger();
        String daySteps = String.join(",", Collections.nCopies(dayCount, "3"));
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        context("setup", exchange -> {
            count("setup");
            json(exchange, 200, "{\"daySteps\":[" + daySteps + "],"
                    + "\"map\":{\"width\":3,\"height\":1,\"cells\":[[0,0,0]]},"
                    + "\"spots\":[{\"brand\":1,\"pos\":1,\"stocks\":2}],"
                    + "\"agents\":[0,2],\"fuelLimits\":8}");
        });
        context("assignment", exchange -> {
            count("assignment");
            assertEquals("[0,1]", body(exchange));
            json(exchange, 200, actionResult(0, "response-day-assignment"));
        });
        context("start", exchange -> {
            count("start");
            json(exchange, 200, "{\"started\":true}");
        });
        context("state", exchange -> {
            count("state");
            int submitted = acceptedDays.get();
            if (submitted >= dayCount) {
                json(exchange, 425, "{}");
                return;
            }
            int reads = readsSinceSubmission.getAndIncrement();
            boolean stillOnSubmittedDay = submitted > 0 && reads < staleReadsPerDay;
            json(exchange, 200, desyncDayState(stillOnSubmittedDay ? submitted - 1 : submitted, 0, 8));
        });
        context("actions", exchange -> {
            int attempt = count("actions");
            actionBodies.add(body(exchange));
            int submittedDay = acceptedDays.getAndIncrement();
            readsSinceSubmission.set(0);
            Integer responseDay =
                    responseDays.get(Math.min(responseDays.size() - 1, submittedDay));
            json(exchange, 200, responseDay == null
                    ? actionResultWithoutDay("response-day-actions-" + attempt)
                    : actionResult(responseDay, "response-day-actions-" + attempt));
        });
        context("result", exchange -> {
            count("result");
            json(exchange, 200, "{\"status\":\"FINAL\",\"score\":0}");
        });
        server.start();
    }

    private void restartForAuthoritativeGapScenario() throws IOException {
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
                    {"daySteps":[3,3],
                     "map":{"width":3,"height":1,"cells":[[0,0,0]]},
                     "spots":[{"brand":1,"pos":1,"stocks":2}],
                     "agents":[0,2],"fuelLimits":8}
                    """);
        });
        context("assignment", exchange -> {
            count("assignment");
            assertEquals("[0,1]", body(exchange));
            json(exchange, 200, actionResult(0, "gap-assignment"));
        });
        context("start", exchange -> {
            count("start");
            json(exchange, 200, "{\"started\":true}");
        });
        context("state", exchange -> {
            count("state");
            int day = actionsAccepted.get() ? 2 : 0;
            json(exchange, 200, desyncDayState(day, 0, 8));
        });
        context("actions", exchange -> {
            count("actions");
            actionBodies.add(body(exchange));
            actionsAccepted.set(true);
            json(exchange, 200, actionResult(2, "gap-actions-1"));
        });
        context("result", exchange -> {
            count("result");
            json(exchange, 200, "{\"status\":\"FINAL\",\"score\":0}");
        });
        server.start();
    }

    private void restartForStaleAuthoritativeStateScenario() throws IOException {
        server.stop(0);
        calls.clear();
        actionBodies.clear();
        assignmentAttempts.set(0);
        stateSuccesses.set(0);
        AtomicBoolean dayZeroAccepted = new AtomicBoolean();
        AtomicBoolean dayOneAccepted = new AtomicBoolean();
        AtomicInteger staleReads = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        context("setup", exchange -> {
            count("setup");
            json(exchange, 200, """
                    {"daySteps":[3,3,3],
                     "map":{"width":3,"height":1,"cells":[[0,0,0]]},
                     "spots":[{"brand":1,"pos":1,"stocks":2}],
                     "agents":[0,2],"fuelLimits":8}
                    """);
        });
        context("assignment", exchange -> {
            count("assignment");
            assertEquals("[0,1]", body(exchange));
            json(exchange, 200, actionResult(0, "stale-assignment"));
        });
        context("start", exchange -> {
            count("start");
            json(exchange, 200, "{\"started\":true}");
        });
        context("state", exchange -> {
            count("state");
            int day;
            if (!dayZeroAccepted.get()) {
                day = 0;
            } else if (!dayOneAccepted.get()) {
                day = 1;
            } else {
                day = staleReads.getAndIncrement() == 0 ? 0 : 2;
            }
            json(exchange, 200, desyncDayState(day, 0, 8));
        });
        context("actions", exchange -> {
            int attempt = count("actions");
            actionBodies.add(body(exchange));
            if (!dayZeroAccepted.get()) {
                dayZeroAccepted.set(true);
            } else {
                dayOneAccepted.set(true);
            }
            json(exchange, 200, actionResult(attempt - 1, "stale-actions-" + attempt));
        });
        context("result", exchange -> {
            count("result");
            json(exchange, 200, "{\"status\":\"FINAL\",\"score\":0}");
        });
        server.start();
    }

    private String desyncDayState(int day, int patrolPosition, int patrolFuel) {
        return "{\"day\":" + day
                + ",\"agents\":[{\"kind\":0,\"pos\":" + patrolPosition
                + ",\"fuel\":" + patrolFuel + "},"
                + "{\"kind\":1,\"pos\":2,\"fuel\":null}],"
                + "\"others\":[],\"traffics\":[]}";
    }

    private MatchRuntime runtimeWith(vn.ptit.procon.planner.DayPlanner planner) {
        return new MatchRuntime(
                "m-fake",
                new ProconHttpClient(
                        "http://localhost:" + server.getAddress().getPort(),
                        "m-fake", "fake-token", Duration.ofSeconds(2), Duration.ofSeconds(2)),
                Duration.ofMillis(200),
                ignored -> { },
                new vn.ptit.procon.protocol.SetupMapper(),
                new vn.ptit.procon.protocol.DayStateMapper(),
                new SmokeAssignmentPolicy(),
                new vn.ptit.procon.engine.PlanValidator(),
                new vn.ptit.procon.engine.DaySimulator(),
                planner,
                new ParityRecorder());
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

    /** Counts planner invocations so a blocked day can be proven never to reach the planner. */
    private static final class CountingPlanner implements vn.ptit.procon.planner.DayPlanner {

        private final vn.ptit.procon.planner.DayPlanner delegate;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingPlanner(vn.ptit.procon.planner.DayPlanner delegate) {
            this.delegate = delegate;
        }

        @Override
        public TeamPlan plan(vn.ptit.procon.engine.DayState state) {
            calls.incrementAndGet();
            return delegate.plan(state);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
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

    private String invalidActionResult(int day, String reason, String submissionId) {
        return """
                {"day":%d,"match_id":"m-fake","protocol_version":"v0.1-draft",
                 "reason":"%s","response_ms":10,"submission_id":"%s",
                 "type":"action_result","valid":false}
                """.formatted(day, reason, submissionId);
    }

    private void restartForInvalidActionScenario(int daySteps, int responseDay, String reason)
            throws IOException {
        server.stop(0);
        calls.clear();
        actionBodies.clear();
        assignmentAttempts.set(0);
        stateSuccesses.set(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        context("setup", exchange -> {
            count("setup");
            json(exchange, 200, "{\"daySteps\":[" + daySteps + "],"
                    + "\"map\":{\"width\":3,\"height\":1,\"cells\":[[0,0,0]]},"
                    + "\"spots\":[{\"brand\":1,\"pos\":1,\"stocks\":2}],"
                    + "\"agents\":[0,2],\"fuelLimits\":8}");
        });
        context("assignment", exchange -> {
            count("assignment");
            assertEquals("[0,1]", body(exchange));
            json(exchange, 200, actionResult(0, "invalid-assignment"));
        });
        context("start", exchange -> {
            count("start");
            json(exchange, 200, "{\"started\":true}");
        });
        context("state", exchange -> {
            count("state");
            json(exchange, 200, desyncDayState(0, 0, 8));
        });
        context("actions", exchange -> {
            count("actions");
            actionBodies.add(body(exchange));
            json(exchange, 200, invalidActionResult(responseDay, reason, "invalid-actions-0"));
        });
        context("result", exchange -> {
            count("result");
            json(exchange, 200, "{\"status\":\"FINAL\",\"score\":0}");
        });
        server.start();
    }

    private void restartFor60StepBoundaryScenario() throws IOException {
        server.stop(0);
        calls.clear();
        actionBodies.clear();
        assignmentAttempts.set(0);
        stateSuccesses.set(0);
        AtomicBoolean actionsAccepted = new AtomicBoolean();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        context("setup", exchange -> {
            count("setup");
            json(exchange, 200, "{\"daySteps\":[60],"
                    + "\"map\":{\"width\":3,\"height\":1,\"cells\":[[0,0,0]]},"
                    + "\"spots\":[{\"brand\":1,\"pos\":1,\"stocks\":2}],"
                    + "\"agents\":[0,2],\"fuelLimits\":60}");
        });
        context("assignment", exchange -> {
            count("assignment");
            assertEquals("[0,1]", body(exchange));
            json(exchange, 200, actionResult(0, "boundary-assignment"));
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
            json(exchange, 200, desyncDayState(0, 0, 60));
        });
        context("actions", exchange -> {
            count("actions");
            actionBodies.add(body(exchange));
            actionsAccepted.set(true);
            json(exchange, 200, actionResult(0, "boundary-actions-0"));
        });
        context("result", exchange -> {
            count("result");
            json(exchange, 200, "{\"status\":\"FINAL\",\"score\":0}");
        });
        server.start();
    }

    /** Live servers do not always report a day; an unobservable day must stay unobservable. */
    private String actionResultWithoutDay(String submissionId) {
        return """
                {"match_id":"m-fake","protocol_version":"v0.1-draft",
                 "reason":"","response_ms":10,"submission_id":"%s",
                 "type":"action_result","valid":true}
                """.formatted(submissionId);
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
