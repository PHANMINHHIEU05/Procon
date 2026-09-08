package vn.ptit.procon.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.protocol.dto.DayStateDto;
import vn.ptit.procon.protocol.dto.SetupDto;

/**
 * Deferred live-state capture: the write half of the offline V3 corpus.
 *
 * <p><strong>Why DTOs and not a {@code DayState}.</strong> {@code DayStateMapper} re-derives every
 * per-day quantity — including {@code replenishedStock} — from the setup payload plus the day payload
 * plus the kind assignment. Persisting exactly those three inputs therefore reproduces the identical
 * pre-submit {@code DayState} offline through the production mappers, with no bespoke serializer to
 * drift out of sync with the domain model.
 *
 * <p><strong>Why deferred.</strong> Nothing here evaluates V2 or V3. The live loop stays
 * {@code state -> V2/R3 -> validate -> POST}; the only added work is one small JSON write per day, so
 * the corpus costs the match no planning CPU and cannot contend with the production planner.
 *
 * <p><strong>Containment.</strong> Capture is OFF unless {@code PROCON_V3_CAPTURE_STATES=true}. It never
 * touches the plan, the encoded actions or the submission, and every method swallows its own failures
 * into a diagnostic line — a corpus problem can never fail a live match. Files land outside the
 * repository ({@link RuntimeConfig#DEFAULT_V3_CAPTURE_DIRECTORY}) and hold match payloads only: no
 * token, no cookie, no {@code Authorization} header, no session value is ever passed in or written.
 *
 * <p><strong>Ordering.</strong> {@link #captureBeforeSubmission} runs before the POST, so the recorded
 * state is provably the state V2/R3 planned from. {@link #markAccepted} runs after
 * {@code action_result.valid}, so the offline replay can restrict itself to days the server actually
 * accepted.
 */
public final class V3CorpusCapture {

    /** Marker file name written only after the server accepted the captured day. */
    static final String ACCEPTED_MARKER = "accepted.json";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final boolean enabled;
    private final Path root;

    private V3CorpusCapture(boolean enabled, Path root) {
        this.enabled = enabled;
        this.root = root;
    }

    /** The OFF capture: no directory is created, no file is written, every method is a no-op. */
    public static V3CorpusCapture disabled() {
        return new V3CorpusCapture(false, null);
    }

    public static V3CorpusCapture enabled(Path directory) {
        return new V3CorpusCapture(true, Objects.requireNonNull(directory, "Directory must not be null"));
    }

    /** Builds the capture the configuration asks for; OFF is the default and needs no directory. */
    public static V3CorpusCapture from(RuntimeConfig config) {
        Objects.requireNonNull(config, "Config must not be null");
        return config.v3CaptureStates()
                ? enabled(Path.of(config.v3CaptureDirectory()))
                : disabled();
    }

    public boolean enabled() {
        return enabled;
    }

    /** The match-scoped corpus directory, absent when capture is OFF. */
    Path matchDirectory(String matchId) {
        return root == null ? null : root.resolve(sanitize(matchId));
    }

    Path dayDirectory(String matchId, int day) {
        Path match = matchDirectory(matchId);
        return match == null ? null : match.resolve("day-" + day);
    }

    /**
     * Records one pre-submission day. Called on the action thread immediately before the POST, which is
     * why it does exactly one bounded set of small writes and reports rather than throws.
     *
     * @return true when the day was fully written; false when capture is OFF or the write failed
     */
    public boolean captureBeforeSubmission(
            String matchId,
            int day,
            SetupDto setup,
            DayStateDto stateDto,
            List<AgentKind> assignment,
            List<List<Integer>> encodedActions,
            String actionFingerprint,
            String plannerAuthority,
            int agentCount,
            int stepBudget,
            V3ShadowRunner.Log log) {
        if (!enabled) {
            return false;
        }
        try {
            Path dayDirectory = dayDirectory(matchId, day);
            Files.createDirectories(dayDirectory);
            // The setup payload is day-invariant, so it is stored once per match, not once per day.
            Path setupFile = matchDirectory(matchId).resolve("setup.json");
            if (!Files.exists(setupFile)) {
                writeJson(setupFile, setup);
            }
            writeJson(dayDirectory.resolve("state.json"), stateDto);
            writeJson(dayDirectory.resolve("actions.json"),
                    new CapturedActions(actionFingerprint, encodedActions));
            writeJson(dayDirectory.resolve("meta.json"), new CapturedMeta(
                    matchId, day, agentCount, stepBudget, plannerAuthority, actionFingerprint,
                    kindNames(assignment)));
            log.log("V3_CAPTURE_WRITTEN",
                    "day", day,
                    "agents", agentCount,
                    "stepBudget", stepBudget,
                    "fingerprint", actionFingerprint,
                    "directory", dayDirectory);
            return true;
        } catch (RuntimeException | IOException failure) {
            // A corpus write is evidence collection, never a live dependency.
            log.log("V3_CAPTURE_FAILED",
                    "day", day,
                    "reason", failure.getClass().getSimpleName(),
                    "detail", String.valueOf(failure.getMessage()));
            return false;
        }
    }

    /**
     * Confirms the captured day after {@code action_result.valid} came back true. Only days carrying
     * this marker are admissible corpus states: the offline replay must never score a day whose V2/R3
     * actions the server did not accept.
     */
    public boolean markAccepted(String matchId, int day, V3ShadowRunner.Log log) {
        if (!enabled) {
            return false;
        }
        try {
            Path dayDirectory = dayDirectory(matchId, day);
            if (!Files.isDirectory(dayDirectory)) {
                return false;
            }
            writeJson(dayDirectory.resolve(ACCEPTED_MARKER), new CapturedAcceptance(matchId, day, true));
            return true;
        } catch (RuntimeException | IOException failure) {
            log.log("V3_CAPTURE_FAILED",
                    "day", day,
                    "reason", failure.getClass().getSimpleName(),
                    "detail", String.valueOf(failure.getMessage()));
            return false;
        }
    }

    private static void writeJson(Path target, Object payload) throws IOException {
        // Written via a sibling temporary file and moved into place, so a reader can never observe a
        // half-written capture even if the process is killed mid-match.
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<String> kindNames(List<AgentKind> assignment) {
        List<String> names = new ArrayList<>();
        if (assignment != null) {
            for (AgentKind kind : assignment) {
                names.add(kind == null ? "null" : kind.name());
            }
        }
        return names;
    }

    /** Keeps a server-supplied identifier from ever escaping the corpus root as a path. */
    private static String sanitize(String matchId) {
        return matchId == null || matchId.isBlank()
                ? "unknown-match"
                : matchId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** The exact wire actions V2/R3 submitted, so the offline replay can assert encoding parity. */
    public record CapturedActions(String fingerprint, List<List<Integer>> actions) {
    }

    /** Everything the offline replay needs that is not part of the two payloads. */
    public record CapturedMeta(
            String matchId,
            int day,
            int agentCount,
            int stepBudget,
            String plannerAuthority,
            String actionFingerprint,
            List<String> assignment) {
    }

    /** Written only after the server accepted the day. */
    public record CapturedAcceptance(String matchId, int day, boolean accepted) {
    }
}
