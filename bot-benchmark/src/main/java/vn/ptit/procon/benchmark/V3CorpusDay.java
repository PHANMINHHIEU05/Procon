package vn.ptit.procon.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.protocol.DayStateMapper;
import vn.ptit.procon.protocol.SetupMapper;
import vn.ptit.procon.protocol.dto.DayStateDto;
import vn.ptit.procon.protocol.dto.SetupDto;

/**
 * One captured live day, reloaded from disk and rebuilt into the exact pre-submit {@link DayState}.
 *
 * <p>The rebuild goes through {@link SetupMapper} and {@link DayStateMapper} and nothing else: every
 * per-day quantity the planner reads — including {@code replenishedStock} — is re-derived by production
 * code from the two captured payloads plus the captured kind assignment. There is no corpus-specific
 * state serializer that could drift away from the domain model.
 */
public record V3CorpusDay(
        String matchId,
        int day,
        Path directory,
        boolean accepted,
        SetupDto setup,
        DayStateDto state,
        List<AgentKind> assignment,
        List<List<Integer>> capturedActions,
        String capturedFingerprint,
        String plannerAuthority,
        int agentCount,
        int stepBudget) {

    /**
     * The latency-gate predicate: a state is "heavy" from at least the "6-agent/60-step" shape upwards,
     * i.e. MEDIUM and LARGE both count. Deliberately wider than {@link #cohort()} == LARGE so that
     * narrowing the reporting taxonomy to three cohorts can never shrink the set of states the
     * {@code large p90 <= 3000 ms} promotion gate is evaluated over.
     */
    public boolean large() {
        return agentCount >= 6 && stepBudget >= 60;
    }

    /**
     * SUPER SESSION section 8 cohort taxonomy: SMALL ~4-agent/30-step, MEDIUM ~6-agent/60-step,
     * LARGE ~8-agent/100-step. Used for coverage counting and per-cohort reporting.
     */
    public String cohort() {
        if (agentCount >= 8 && stepBudget >= 100) return "LARGE";
        if (agentCount >= 6 && stepBudget >= 60) return "MEDIUM";
        return "SMALL";
    }

    public String sizeClass() {
        return cohort();
    }

    public int mapWidth() {
        return setup.map() == null || setup.map().width() == null ? 0 : setup.map().width();
    }

    public int mapHeight() {
        return setup.map() == null || setup.map().height() == null ? 0 : setup.map().height();
    }

    public int spotCount() {
        return setup.spots() == null ? 0 : setup.spots().size();
    }

    public StaticMatchData matchData() {
        return new SetupMapper().toDomain(setup);
    }

    /** The authoritative pre-submit state, rebuilt by production mappers only. */
    public DayState rebuild() {
        return new DayStateMapper().toDomain(state, matchData(), assignment);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s/day-%d(%s %dx%d agents=%d steps=%d)",
                matchId, day, sizeClass(), mapWidth(), mapHeight(), agentCount, stepBudget);
    }

    /**
     * Loads every captured day under {@code root}, sorted by match then day.
     *
     * @param acceptedOnly when true, a day without its acceptance marker is skipped: the offline replay
     *        must never score a state whose V2/R3 actions the server did not take
     */
    public static List<V3CorpusDay> loadAll(Path root, boolean acceptedOnly) throws IOException {
        List<V3CorpusDay> days = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return days;
        }
        try (var matches = Files.list(root)) {
            for (Path matchDirectory : matches.filter(Files::isDirectory).sorted().toList()) {
                days.addAll(loadMatch(matchDirectory, acceptedOnly));
            }
        }
        days.sort(Comparator.comparing(V3CorpusDay::matchId).thenComparingInt(V3CorpusDay::day));
        return days;
    }

    private static List<V3CorpusDay> loadMatch(Path matchDirectory, boolean acceptedOnly)
            throws IOException {
        List<V3CorpusDay> days = new ArrayList<>();
        Path setupFile = matchDirectory.resolve("setup.json");
        if (!Files.isRegularFile(setupFile)) {
            return days;
        }
        SetupDto setup = MAPPER.readValue(setupFile.toFile(), SetupDto.class);
        try (var entries = Files.list(matchDirectory)) {
            for (Path dayDirectory : entries.filter(Files::isDirectory).sorted().toList()) {
                if (!dayDirectory.getFileName().toString().startsWith("day-")) {
                    continue;
                }
                V3CorpusDay day = load(matchDirectory.getFileName().toString(), setup, dayDirectory);
                if (day != null && (day.accepted() || !acceptedOnly)) {
                    days.add(day);
                }
            }
        }
        return days;
    }

    private static V3CorpusDay load(String matchId, SetupDto setup, Path dayDirectory)
            throws IOException {
        Path stateFile = dayDirectory.resolve("state.json");
        Path metaFile = dayDirectory.resolve("meta.json");
        Path actionsFile = dayDirectory.resolve("actions.json");
        if (!Files.isRegularFile(stateFile) || !Files.isRegularFile(metaFile)
                || !Files.isRegularFile(actionsFile)) {
            return null;
        }
        DayStateDto state = MAPPER.readValue(stateFile.toFile(), DayStateDto.class);
        Meta meta = MAPPER.readValue(metaFile.toFile(), Meta.class);
        Actions actions = MAPPER.readValue(actionsFile.toFile(), Actions.class);
        List<AgentKind> assignment = new ArrayList<>();
        for (String kind : meta.assignment()) {
            assignment.add(AgentKind.valueOf(kind));
        }
        return new V3CorpusDay(matchId, meta.day(), dayDirectory,
                Files.isRegularFile(dayDirectory.resolve("accepted.json")), setup, state,
                List.copyOf(assignment), actions.actions(), actions.fingerprint(),
                meta.plannerAuthority(), meta.agentCount(), meta.stepBudget());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The capture's own metadata file, read back with the same field names the runtime wrote. */
    private record Meta(String matchId, int day, int agentCount, int stepBudget,
            String plannerAuthority, String actionFingerprint, List<String> assignment) {
    }

    private record Actions(String fingerprint, List<List<Integer>> actions) {
    }
}
