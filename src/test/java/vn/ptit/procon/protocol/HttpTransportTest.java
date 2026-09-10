package vn.ptit.procon.protocol;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.model.Model;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTransportTest {
    @Test void statePollingRetriesAnIdempotentRequestTimeout() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/v1/matches/m-retry/state", exchange -> {
            try {
                // Deliberately exceed the first request's timeout. The retry must still obtain
                // the same authoritative state before the outer awaitNextDay deadline.
                if (requests.incrementAndGet() == 1) Thread.sleep(150);
                byte[] body = "{\"day\":0,\"agents\":[{\"kind\":0,\"pos\":0,\"fuel\":60}],\"traffics\":[]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // The timed-out first client may close before this handler responds.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
            HttpTransport transport = new HttpTransport(base, "m-retry", "test-token", Duration.ofMillis(50));

            Model.DayState state = transport.awaitNextDay(-1,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(2));

            assertEquals(0, state.day());
            assertTrue(requests.get() >= 2, "the timed-out GET must be retried before giving up");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
