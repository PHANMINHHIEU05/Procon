package vn.ptit.procon.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.protocol.dto.DayStateDto;
import vn.ptit.procon.protocol.dto.SetupDto;
import vn.ptit.procon.protocol.dto.SubmissionResult;

/** Focused official HTTP boundary for one match. */
public final class ProconHttpClient {

    public static final int HTTP_TOO_EARLY = 425;
    public static final int HTTP_TOO_MANY_REQUESTS = 429;

    private static final int MAX_DIAGNOSTIC_BODY_LENGTH = 2_000;

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final URI matchUri;
    private final String secretToken;
    private final String bearerValue;
    private final Duration requestTimeout;
    private final ActionEncoder actionEncoder;

    public ProconHttpClient(
            String baseUrl, String matchId, String token, Duration connectTimeout, Duration requestTimeout) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(requirePositive(connectTimeout, "Connect timeout"))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                new ObjectMapper(),
                baseUrl,
                matchId,
                token,
                requestTimeout,
                new ActionEncoder());
    }

    ProconHttpClient(
            HttpClient client,
            ObjectMapper objectMapper,
            String baseUrl,
            String matchId,
            String token,
            Duration requestTimeout,
            ActionEncoder actionEncoder) {
        this.client = Objects.requireNonNull(client, "HTTP client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper must not be null");
        this.matchUri = matchUri(baseUrl, matchId);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token must not be blank");
        }
        this.secretToken = token;
        this.bearerValue = "Bearer " + token;
        this.requestTimeout = requirePositive(requestTimeout, "Request timeout");
        this.actionEncoder = Objects.requireNonNull(actionEncoder, "Action encoder must not be null");
    }

    public SetupDto getSetup() throws IOException, InterruptedException {
        return read(get("/setup"), "/setup", SetupDto.class);
    }

    public SubmissionResult postAssignment(List<AgentKind> assignment)
            throws IOException, InterruptedException {
        Objects.requireNonNull(assignment, "Assignment must not be null");
        return submissionResult(
                post("/assignment", assignment.stream().map(AgentKind::code).toList()),
                "/assignment");
    }

    public JsonNode getStart() throws IOException, InterruptedException {
        return readTree(get("/start"), "/start");
    }

    public DayStateDto getState() throws IOException, InterruptedException {
        return read(get("/state"), "/state", DayStateDto.class);
    }

    public SubmissionResult postActions(TeamPlan plan, int agentCount)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(postRequest(
                "/actions", write(actionEncoder.encode(plan, agentCount), "/actions")));
        return submissionResult(response, "/actions");
    }

    public JsonNode getResult() throws IOException, InterruptedException {
        return readTree(get("/result"), "/result");
    }

    private HttpResponse<String> get(String endpoint) throws IOException, InterruptedException {
        return send(baseRequest(endpoint).GET().build());
    }

    private HttpResponse<String> post(String endpoint, Object body) throws IOException, InterruptedException {
        return send(postRequest(endpoint, write(body, endpoint)));
    }

    private HttpRequest postRequest(String endpoint, String json) {
        return baseRequest(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private HttpRequest.Builder baseRequest(String endpoint) {
        return HttpRequest.newBuilder(matchUri.resolve("." + endpoint))
                .timeout(requestTimeout)
                .header("Authorization", bearerValue)
                .header("Accept", "application/json");
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private <T> T read(HttpResponse<String> response, String endpoint, Class<T> type) {
        ensureSuccess(response, endpoint);
        try {
            return objectMapper.readValue(response.body(), type);
        } catch (JsonProcessingException exception) {
            throw new ProtocolMappingException(endpoint, "$", "valid " + type.getSimpleName(), exception);
        }
    }

    private JsonNode readTree(HttpResponse<String> response, String endpoint) {
        ensureSuccess(response, endpoint);
        return parseOptional(response.body(), endpoint);
    }

    private JsonNode parseOptional(String body, String endpoint) {
        if (body == null || body.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new ProtocolMappingException(endpoint, "$", "valid JSON response", exception);
        }
    }

    private String write(Object value, String endpoint) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not encode request for " + endpoint, exception);
        }
    }

    private void ensureSuccess(HttpResponse<String> response, String endpoint) {
        if (!isSuccess(response.statusCode())) {
            throw status(endpoint, response);
        }
    }

    private HttpStatusException status(String endpoint, HttpResponse<String> response) {
        return new HttpStatusException(endpoint, response.statusCode(), sanitize(response.body()));
    }

    private SubmissionResult submissionResult(HttpResponse<String> response, String endpoint) {
        ensureSuccess(response, endpoint);
        JsonNode body = parseOptional(response.body(), endpoint);
        if (!body.isObject()) {
            throw new ProtocolMappingException(endpoint, "$", "action_result object", body.getNodeType());
        }

        JsonNode valid = body.get("valid");
        if (valid == null || valid.isNull() || !valid.isBoolean()) {
            throw new ProtocolMappingException(
                    endpoint,
                    "$.valid",
                    "boolean",
                    valid == null || valid.isNull() ? null : valid.getNodeType());
        }

        return new SubmissionResult(
                response.statusCode(),
                optionalText(body, "type", endpoint),
                valid.booleanValue(),
                optionalText(body, "reason", endpoint),
                optionalInteger(body, "day", endpoint),
                optionalText(body, "match_id", endpoint),
                optionalText(body, "protocol_version", endpoint),
                optionalLong(body, "response_ms", endpoint),
                optionalText(body, "submission_id", endpoint));
    }

    private String optionalText(JsonNode body, String field, String endpoint) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ProtocolMappingException(
                    endpoint, "$." + field, "string", value.getNodeType());
        }
        return sanitize(value.textValue());
    }

    private Integer optionalInteger(JsonNode body, String field, String endpoint) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new ProtocolMappingException(
                    endpoint, "$." + field, "integer", value.getNodeType());
        }
        return value.intValue();
    }

    private Long optionalLong(JsonNode body, String field, String endpoint) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ProtocolMappingException(
                    endpoint, "$." + field, "integer", value.getNodeType());
        }
        return value.longValue();
    }

    private static URI matchUri(String baseUrl, String matchId) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Base URL must not be blank");
        }
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("Match ID must not be blank");
        }
        URI base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        return base.resolve("api/v1/matches/" + encodePathSegment(matchId) + "/");
    }

    private static String encodePathSegment(String value) {
        if (!value.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("Match ID contains unsupported path characters");
        }
        return value;
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private String sanitize(String body) {
        if (body == null) {
            return "";
        }
        String singleLine = body
                .replace(secretToken, "<redacted>")
                .replaceAll("(?i)Bearer\\s+[^\\s\\\"]+", "Bearer <redacted>")
                .replaceAll("[\\r\\n\\t]+", " ")
                .trim();
        return singleLine.length() <= MAX_DIAGNOSTIC_BODY_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_DIAGNOSTIC_BODY_LENGTH) + "…";
    }
}