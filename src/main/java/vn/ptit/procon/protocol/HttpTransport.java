package vn.ptit.procon.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import vn.ptit.procon.model.Model.DayState;
import vn.ptit.procon.model.Model.MatchResult;
import vn.ptit.procon.model.Model.Setup;
import vn.ptit.procon.model.Model.SubmissionAck;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpTransport implements MatchTransport {
    private final HttpClient client;
    private final URI base;
    private final String token;
    private final Duration timeout;

    public HttpTransport(String baseUrl, String matchId, String token, Duration timeout) {
        String normalized = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.base = URI.create(normalized + "api/v1/matches/" + matchId + "/");
        this.token = token;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override public Setup awaitSetup(long deadline) throws IOException, InterruptedException {
        while (System.nanoTime() < deadline) {
            Response response;
            try {
                response = request("GET", "setup", null);
            } catch (IOException transientRequestFailure) {
                // A timeout while polling setup says nothing about the assignment state.  The
                // outer deadline is authoritative, so retry rather than abandoning a healthy
                // match because one reverse-proxy long-poll exceeded its per-request timeout.
                pauseBeforePollRetry(200, deadline);
                continue;
            }
            if (response.status == 200) return JsonProtocol.setup(response.body);
            if (response.status != 425) check(response);
            pauseBeforePollRetry(200, deadline);
        }
        throw new IOException("Timed out waiting for setup");
    }

    @Override public SubmissionAck submitAssignment(int[] roles) throws IOException, InterruptedException {
        return ack(request("POST", "assignment", JsonProtocol.write(roles)));
    }

    @Override public DayState awaitNextDay(int lastDay, long deadline) throws IOException, InterruptedException {
        while (System.nanoTime() < deadline) {
            Response response;
            try {
                response = request("GET", "state", null);
            } catch (IOException transientRequestFailure) {
                // GET is idempotent.  In particular, do not turn one HttpTimeoutException into
                // BOT_FATAL while the server is still advancing the other teams' day.
                pauseBeforePollRetry(250, deadline);
                continue;
            }
            if (response.status == 200) {
                DayState state = JsonProtocol.state(response.body);
                if (state.day() > lastDay) return state;
            } else if (response.status != 425 && response.status != 429) check(response);
            pauseBeforePollRetry(250, deadline);
        }
        throw new IOException("Timed out waiting for next day");
    }

    @Override public SubmissionAck submitActions(int day, int[][] actions) throws IOException, InterruptedException {
        return ack(request("POST", "actions", JsonProtocol.write(actions)));
    }

    @Override public MatchResult awaitResult(long deadline) throws IOException, InterruptedException {
        while (System.nanoTime() < deadline) {
            Response response;
            try {
                response = request("GET", "result", null);
            } catch (IOException transientRequestFailure) {
                // Result reads are also idempotent; retain the same retry semantics as state.
                pauseBeforePollRetry(250, deadline);
                continue;
            }
            if (response.status == 200) return JsonProtocol.result(response.body);
            if (response.status != 425 && response.status != 429) check(response);
            pauseBeforePollRetry(250, deadline);
        }
        throw new IOException("Timed out waiting for result");
    }

    private Response request(String method, String endpoint, String body) throws IOException, InterruptedException {
        URI uri = URI.create(base.toString() + endpoint);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Authorization", "Bearer " + token).header("Accept", "application/json");
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private SubmissionAck ack(Response response) throws IOException {
        if (response.status < 200 || response.status >= 300) throw new IOException("HTTP " + response.status + ": " + sanitize(response.body));
        try {
            JsonNode node = response.body == null || response.body.isBlank() ? JsonProtocol.MAPPER.nullNode() : JsonProtocol.MAPPER.readTree(response.body);
            return new SubmissionAck(!node.has("valid") || node.path("valid").asBoolean(), node.path("reason").asText(""), node.path("response_ms").asLong(0));
        } catch (Exception e) { throw new IOException("Invalid submission response", e); }
    }

    private void check(Response response) throws IOException { throw new IOException("HTTP " + response.status + ": " + sanitize(response.body)); }

    private static void pauseBeforePollRetry(long millis, long deadline) throws InterruptedException {
        long remainingMillis = Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
        if (remainingMillis > 0) Thread.sleep(Math.min(millis, remainingMillis));
    }

    private String sanitize(String body) {
        if (body == null) return "";
        String sanitized = body.replace(token, "<redacted>").replaceAll("[\\r\\n\\t]+", " ");
        return sanitized.substring(0, Math.min(300, sanitized.length()));
    }
    private record Response(int status, String body) {}
    @Override public void close() {}
}
