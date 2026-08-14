package vn.ptit.procon.runtime;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Validated process configuration; token rendering is always redacted. */
public record RuntimeConfig(
        String baseUrl,
        String matchId,
        String token,
        Duration pollInterval,
        Duration httpTimeout,
        PlannerMode plannerMode) {

    public static final String DEFAULT_BASE_URL = "https://procon.ptit.edu.vn";
    public static final long DEFAULT_POLL_INTERVAL_MS = 250;
    public static final long MINIMUM_POLL_INTERVAL_MS = 200;
    public static final long DEFAULT_HTTP_TIMEOUT_SECONDS = 15;

    public RuntimeConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("PROCON_BASE_URL must not be blank");
        }
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("PROCON_MATCH_ID is required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("PROCON_TOKEN is required");
        }
        Objects.requireNonNull(pollInterval, "Polling interval must not be null");
        Objects.requireNonNull(httpTimeout, "HTTP timeout must not be null");
        Objects.requireNonNull(plannerMode, "Planner mode must not be null");
        if (pollInterval.toMillis() < MINIMUM_POLL_INTERVAL_MS) {
            throw new IllegalArgumentException(
                    "PROCON_POLL_INTERVAL_MS must be at least " + MINIMUM_POLL_INTERVAL_MS);
        }
        if (httpTimeout.isZero() || httpTimeout.isNegative()) {
            throw new IllegalArgumentException("PROCON_HTTP_TIMEOUT_SECONDS must be positive");
        }
    }

    public RuntimeConfig(
            String baseUrl,
            String matchId,
            String token,
            Duration pollInterval,
            Duration httpTimeout) {
        this(baseUrl, matchId, token, pollInterval, httpTimeout, PlannerMode.WAIT);
    }

    public static RuntimeConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "Environment must not be null");
        return new RuntimeConfig(
                valueOrDefault(environment.get("PROCON_BASE_URL"), DEFAULT_BASE_URL),
                environment.get("PROCON_MATCH_ID"),
                environment.get("PROCON_TOKEN"),
                Duration.ofMillis(parsePositiveLong(
                        environment.get("PROCON_POLL_INTERVAL_MS"),
                        DEFAULT_POLL_INTERVAL_MS,
                        "PROCON_POLL_INTERVAL_MS")),
                Duration.ofSeconds(parsePositiveLong(
                        environment.get("PROCON_HTTP_TIMEOUT_SECONDS"),
                        DEFAULT_HTTP_TIMEOUT_SECONDS,
                        "PROCON_HTTP_TIMEOUT_SECONDS")),
                PlannerMode.parse(environment.get("PROCON_PLANNER_MODE")));
    }

    public Duration connectTimeout() {
        return httpTimeout;
    }

    @Override
    public String toString() {
        return "RuntimeConfig[baseUrl=" + baseUrl + ", matchId=" + matchId
                + ", token=<set>, pollInterval=" + pollInterval
                + ", httpTimeout=" + httpTimeout + ", plannerMode=" + plannerMode + "]";
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static long parsePositiveLong(String value, long defaultValue, String name) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }
}