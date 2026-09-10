package vn.ptit.procon.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import vn.ptit.procon.model.Model.DayState;
import vn.ptit.procon.model.Model.MatchResult;
import vn.ptit.procon.model.Model.Setup;
import vn.ptit.procon.model.Model.SubmissionAck;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class WebSocketTransport implements MatchTransport {
    private final String url;
    private final String token;
    private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private WebSocket socket;
    private volatile Throwable failure;
    private final TextFrameAssembler assembler = new TextFrameAssembler();

    public WebSocketTransport(String baseUrl, String matchId, String token) {
        String wsBase = baseUrl.replaceFirst("^https", "wss").replaceFirst("^http", "ws");
        if (wsBase.endsWith("/")) wsBase = wsBase.substring(0, wsBase.length() - 1);
        this.url = wsBase + "/ws/v1/matches/" + matchId + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        this.token = token;
    }

    private void connect() throws IOException, InterruptedException {
        if (socket != null) return;
        try {
            socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().newWebSocketBuilder()
                    .buildAsync(URI.create(url), new Listener()).get(12, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("WebSocket handshake failed", e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("WebSocket handshake timed out", e);
        }
    }

    @Override public Setup awaitSetup(long deadline) throws IOException, InterruptedException {
        connect();
        while (System.nanoTime() < deadline) {
            JsonNode node = next(deadline);
            if (JsonProtocol.isSetup(node)) return JsonProtocol.setup(node);
        }
        throw new IOException("Timed out waiting for WebSocket setup");
    }

    @Override public SubmissionAck submitAssignment(int[] roles) throws IOException, InterruptedException {
        send(roles);
        return new SubmissionAck(true, "websocket-send", 0);
    }

    @Override public DayState awaitNextDay(int lastDay, long deadline) throws IOException, InterruptedException {
        while (System.nanoTime() < deadline) {
            JsonNode node = next(deadline);
            if (JsonProtocol.isDayState(node)) {
                DayState state = JsonProtocol.state(node);
                if (state.day() > lastDay) return state;
            }
        }
        throw new IOException("Timed out waiting for WebSocket day state");
    }

    @Override public SubmissionAck submitActions(int day, int[][] actions) throws IOException, InterruptedException {
        send(actions);
        return new SubmissionAck(true, "websocket-send", 0);
    }

    @Override public MatchResult awaitResult(long deadline) throws IOException, InterruptedException {
        while (System.nanoTime() < deadline) {
            JsonNode node = next(deadline);
            if (JsonProtocol.isResult(node)) return JsonProtocol.result(node);
        }
        throw new IOException("Timed out waiting for WebSocket result");
    }

    private void send(Object value) throws IOException {
        try { socket.sendText(JsonProtocol.write(value), true).get(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IOException("WebSocket send failed", e); }
    }

    private JsonNode next(long deadline) throws IOException, InterruptedException {
        if (failure != null) throw new IOException("WebSocket failed", failure);
        long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        String text = frames.poll(millis, TimeUnit.MILLISECONDS);
        if (text == null) throw new IOException("WebSocket frame timeout");
        try { return JsonProtocol.MAPPER.readTree(text); }
        catch (Exception e) { throw new IOException("Invalid WebSocket JSON", e); }
    }

    @Override public void close() { if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "done"); }

    private final class Listener implements WebSocket.Listener {
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String message = assembler.accept(data, last);
            if (message != null) frames.offer(message);
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            failure = new IOException("closed " + statusCode + " " + reason); return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket webSocket, Throwable error) { failure = error; }
    }
}
