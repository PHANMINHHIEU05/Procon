package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.protocol.ProconHttpClient;

class MatchRuntimeIntegrationTest {

    private HttpServer server;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final List<String> actionBodies = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger assignmentAttempts = new AtomicInteger();
    private final AtomicInteger stateSuccesses = new AtomicInteger();

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
                         "map":{"width":3,"height":1,"cells":[[0,1,0]]},
                         "spots":[{"brand":1,"pos":2,"stocks":2}],
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
                    + "\"others\":[{}],\"traffics\":[{\"pos\":1,\"status\":0}]}");
        });
        context("actions", exchange -> {
            count("actions");
            actionBodies.add(body(exchange));
            json(exchange, 200, actionResult(Math.min(3, calls.get("actions").get() - 1),
                    "actions-test-" + calls.get("actions").get()));
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
        assertEquals(List.of("[[2,2],[-3]]", "[[2,2],[-3]]", "[[2,2],[-3]]", "[[2,2],[-3]]"), actionBodies);
        assertTrue(actionBodies.stream().allMatch(body -> body.contains("2")));
        assertEquals("FINAL", result.authoritativeResult().get("status").textValue());
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

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}