package vn.ptit.procon.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.StrategicSearchConfig;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;
import vn.ptit.procon.planner.v3.V3ShadowPlanner;
import vn.ptit.procon.protocol.ActionEncoder;

/**
 * The deferred offline replay (PART 4) and corpus index (PART 5).
 *
 * <p>Nothing here talks to the network. It reads captured live days from disk, rebuilds each pre-submit
 * {@link DayState} with the production mappers, re-plans it with the production V2/R3 planner and asserts
 * the re-encoded wire actions equal the bytes the live loop actually submitted. Only after that parity
 * proof does it run the V3 shadow evaluation, so a V3 figure is never attributed to a state the corpus
 * failed to reproduce.
 *
 * <p>The V3 budget defaults to UNCAPPED ({@link #UNCAPPED_BUDGET_MILLIS}). The live 4000 ms shadow cutoff
 * is deliberately not applied during replay: a truncated search would hide how expensive V3 really is,
 * which is exactly the quantity the optimization loop has to measure. Days that would have blown the live
 * budget are counted instead, by {@link V3CorpusScorecard}.
 */
public final class V3CorpusReplay {

    /** Effectively no deadline: 10 minutes is far beyond any observed V3 search. */
    public static final long UNCAPPED_BUDGET_MILLIS = 600_000;

    /**
     * ITERATION 6: the authoritative corpus lives under {@code $HOME}, never under {@code /tmp}. A mid-session
     * {@code /tmp} wipe destroyed an entire captured corpus together with its baseline logs and JFR profiles,
     * so the default no longer points at volatile storage. {@code --corpus} still overrides it.
     */
    public static final String DEFAULT_CORPUS_DIRECTORY =
            System.getProperty("user.home") + "/.procon-autotune/corpus";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper PRETTY =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final long budgetMillis;

    public V3CorpusReplay(long budgetMillis) {
        this.budgetMillis = budgetMillis;
    }

    /** Replays one captured day: V2/R3 parity first, then the RAW/SAFE V3 comparison. */
    public V3CorpusReplayResult replay(V3CorpusDay day) {
        DayState state = day.rebuild();
        TeamPlan incumbent = new JointTeamBeamR3Planner().plan(state);
        List<List<Integer>> reEncoded = new ActionEncoder().encode(incumbent, state.agents().size());
        String replayedFingerprint = fingerprint(reEncoded);
        boolean parityOk = reEncoded.equals(day.capturedActions())
                && replayedFingerprint.equals(day.capturedFingerprint());
        StrategicSearchConfig config = V3ShadowPlanner.shadowConfig(budgetMillis);
        V3ShadowEvaluation evaluation = new V3ShadowPlanner().evaluate(state, incumbent, config);
        return new V3CorpusReplayResult(day.matchId(), day.day(), day.sizeClass(), day.mapWidth(),
                day.mapHeight(), day.spotCount(), day.agentCount(), day.stepBudget(),
                day.plannerAuthority(), parityOk, day.capturedFingerprint(), replayedFingerprint,
                evaluation);
    }

    public List<V3CorpusReplayResult> replayAll(List<V3CorpusDay> days) {
        return replayAll(days, null);
    }

    /**
     * Replays every day, handing each result to {@code progress} as soon as it exists. A 24-state corpus with
     * 24x24/8-agent days takes tens of minutes uncapped, so a run that only spoke at the end would be
     * indistinguishable from a hung one.
     */
    public List<V3CorpusReplayResult> replayAll(List<V3CorpusDay> days,
            java.util.function.Consumer<V3CorpusReplayResult> progress) {
        List<V3CorpusReplayResult> results = new ArrayList<>();
        for (V3CorpusDay day : days) {
            V3CorpusReplayResult result = replay(day);
            results.add(result);
            if (progress != null) {
                progress.accept(result);
            }
        }
        return results;
    }

    /** The same derivation {@code MatchRuntime} uses, so a mismatch means a real reproduction failure. */
    static String fingerprint(List<List<Integer>> encodedActions) {
        try {
            return String.format(Locale.ROOT, "%08x",
                    MAPPER.writeValueAsString(encodedActions).hashCode());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return "ffffffff";
        }
    }

    /**
     * PART 5: the corpus index. It describes only the shape of each captured day — identifiers, map size,
     * agent count, step budget, acceptance — never a credential and never a planner figure.
     */
    public static void writeIndex(Path root, List<V3CorpusDay> days) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        int large = 0;
        Map<String, Integer> cohorts = new LinkedHashMap<>();
        cohorts.put("SMALL", 0);
        cohorts.put("MEDIUM", 0);
        cohorts.put("LARGE", 0);
        for (V3CorpusDay day : days) {
            large += day.large() ? 1 : 0;
            cohorts.merge(day.cohort(), 1, Integer::sum);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("matchId", day.matchId());
            entry.put("day", day.day());
            entry.put("accepted", day.accepted());
            entry.put("sizeClass", day.sizeClass());
            entry.put("cohort", day.cohort());
            entry.put("mapWidth", day.mapWidth());
            entry.put("mapHeight", day.mapHeight());
            entry.put("spots", day.spotCount());
            entry.put("agentCount", day.agentCount());
            entry.put("stepBudget", day.stepBudget());
            entry.put("plannerAuthority", day.plannerAuthority());
            entry.put("actionFingerprint", day.capturedFingerprint());
            entry.put("directory", root.relativize(day.directory()).toString());
            entries.add(entry);
        }
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("corpusRoot", root.toString());
        index.put("totalDays", days.size());
        index.put("largeDays", large);
        index.put("smallDays", days.size() - large);
        index.put("cohortCounts", cohorts);
        index.put("days", entries);
        Path target = root.resolve("index.json");
        Files.createDirectories(root);
        Files.writeString(target, PRETTY.writeValueAsString(index), StandardCharsets.UTF_8);
        System.out.println("CORPUS_INDEX_WRITTEN file=" + target + " days=" + days.size()
                + " large=" + large + " small=" + (days.size() - large));
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(DEFAULT_CORPUS_DIRECTORY);
        long budget = UNCAPPED_BUDGET_MILLIS;
        boolean acceptedOnly = true;
        boolean indexOnly = false;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--corpus" -> root = Path.of(args[++index]);
                case "--budget-millis" -> budget = Long.parseLong(args[++index]);
                case "--include-rejected" -> acceptedOnly = false;
                case "--index-only" -> indexOnly = true;
                default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }

        List<V3CorpusDay> days = V3CorpusDay.loadAll(root, acceptedOnly);
        System.out.println("CORPUS_LOADED root=" + root + " days=" + days.size()
                + " acceptedOnly=" + acceptedOnly + " budgetMillis=" + budget);
        for (V3CorpusDay day : days) {
            System.out.println("CORPUS_ENTRY " + day + " accepted=" + day.accepted());
        }
        writeIndex(root, days);
        if (indexOnly || days.isEmpty()) {
            if (days.isEmpty()) {
                System.out.println("CORPUS_EMPTY no captured day found under " + root);
            }
            return;
        }
        new V3CorpusScorecard(new V3CorpusReplay(budget)
                .replayAll(days, result -> System.out.println("CORPUS_DAY_DONE " + result.line())))
                .print(System.out);
    }
}
