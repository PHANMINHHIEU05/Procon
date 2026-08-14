package vn.ptit.procon.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.protocol.dto.SubmissionResult;

class ProconHttpClientTest {

    private HttpServer server;
    private ProconHttpClient client;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> assignmentBody = new AtomicReference<>();
    private final AtomicReference<String> actionsBody = new AtomicReference<>();
    private volatile int startStatus = 200;
    private volatile String assignmentResponse = actionResult(0, true, "", "sub-assignment");
    private volatile String actionsResponse = actionResult(0, true, "", "sub-actions");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/matches/m-test/setup", exchange -> json(exchange, 200,
                "{\"daySteps\":[4],\"map\":{\"width\":1,\"height\":1,\"cells\":[[0]]},"
                        + "\"spots\":[],\"agents\":[0],\"fuelLimits\":8}"));
        server.createContext("/api/v1/matches/m-test/assignment", exchange -> {
            capture(exchange, assignmentBody);
            json(exchange, 200, assignmentResponse);
        });
        server.createContext("/api/v1/matches/m-test/start", exchange -> json(
                exchange,
                startStatus,
                startStatus == 200 ? "{}" : "{\"reason\":\"Bearer fake-secret\"}"));
        server.createContext("/api/v1/matches/m-test/state", exchange -> json(exchange, 200,
                "{\"day\":0,\"agents\":[{\"kind\":0,\"pos\":0,\"fuel\":8}],\"traffics\":[]}"));
        server.createContext("/api/v1/matches/m-test/actions", exchange -> {
            capture(exchange, actionsBody);
            json(exchange, 200, actionsResponse);
        });
        server.createContext("/api/v1/matches/m-test/result", exchange -> json(exchange, 200,
                "{\"status\":\"FINAL\",\"score\":0}"));
        server.start();
        client = new ProconHttpClient(
                "http://localhost:" + server.getAddress().getPort(),
                "m-test", "fake-secret", Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void invokesEveryOfficialEndpointWithBearerAndCorrectPostPayloads() throws Exception {
        assertEquals(4, client.getSetup().daySteps()[0]);
        SubmissionResult assignment = client.postAssignment(List.of(AgentKind.PATROL));
        client.getStart();
        assertEquals(0, client.getState().day());
        SubmissionResult actions = client.postActions(
                new TeamPlan(java.util.Map.of(new AgentId(0), List.of(new WaitAction(4)))), 1);
        assertEquals("FINAL", client.getResult().get("status").textValue());

        assertTrue(assignment.valid());
        assertEquals("action_result", assignment.type());
        assertEquals("", assignment.reason());
        assertEquals(0, assignment.day());
        assertEquals("m-test", assignment.matchId());
        assertEquals("v0.1-draft", assignment.protocolVersion());
        assertEquals(10L, assignment.responseMs());
        assertEquals("sub-assignment", assignment.submissionId());
        assertTrue(actions.valid());
        assertEquals("action_result", actions.type());
        assertEquals("", actions.reason());
        assertEquals(0, actions.day());
        assertEquals("m-test", actions.matchId());
        assertEquals("v0.1-draft", actions.protocolVersion());
        assertEquals(10L, actions.responseMs());
        assertEquals("sub-actions", actions.submissionId());
        assertEquals("Bearer fake-secret", authorization.get());
        assertEquals("[0]", assignmentBody.get());
        assertEquals("[[-4]]", actionsBody.get());
    }

    @Test
    void redactsTokenFromHttpErrorDiagnostics() {
        startStatus = 401;

        HttpStatusException error = assertThrows(HttpStatusException.class, client::getStart);

        assertFalse(error.getMessage().contains("fake-secret"));
        assertTrue(error.getMessage().contains("<redacted>"));
    }

    @Test
    void validFalseIsARejectedSubmissionEvenWithEmptyReason() throws Exception {
        assignmentResponse = actionResult(0, false, "", "sub-rejected");

        SubmissionResult result = client.postAssignment(List.of(AgentKind.PATROL));

        assertFalse(result.valid());
        assertEquals("<empty>", result.diagnosticReason());
        assertEquals(200, result.httpStatus());
    }

    @Test
    void missingValidFailsClosed() {
        assignmentResponse = """
                {"day":0,"match_id":"m-test","protocol_version":"v0.1-draft",
                 "reason":"","response_ms":10,"submission_id":"sub-test","type":"action_result"}
                """;

        ProtocolMappingException error = assertThrows(
                ProtocolMappingException.class,
                () -> client.postAssignment(List.of(AgentKind.PATROL)));

        assertTrue(error.getMessage().contains("$.valid"));
    }

    @Test
    void nonBooleanValidFailsClosed() {
        actionsResponse = """
                {"day":0,"match_id":"m-test","protocol_version":"v0.1-draft",
                 "reason":"","response_ms":10,"submission_id":"sub-test",
                 "type":"action_result","valid":"true"}
                """;

        ProtocolMappingException error = assertThrows(
                ProtocolMappingException.class,
                () -> client.postActions(
                        new TeamPlan(java.util.Map.of(new AgentId(0), List.of(new WaitAction(4)))), 1));

        assertTrue(error.getMessage().contains("$.valid"));
    }

    private void capture(HttpExchange exchange, AtomicReference<String> body) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String actionResult(int day, boolean valid, String reason, String submissionId) {
        return """
                {"day":%d,"match_id":"m-test","protocol_version":"v0.1-draft",
                 "reason":"%s","response_ms":10,"submission_id":"%s",
                 "type":"action_result","valid":%s}
                """.formatted(day, reason, submissionId, valid);
    }
}