package vn.ptit.procon.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.benchmark.V3CorpusProfiler.Phase;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;
import vn.ptit.procon.protocol.ActionEncoder;
import vn.ptit.procon.protocol.DayStateMapper;
import vn.ptit.procon.protocol.SetupMapper;
import vn.ptit.procon.protocol.dto.DayStateDto;
import vn.ptit.procon.protocol.dto.SetupDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The offline corpus loader, replay and scorecard, exercised on a synthetic corpus written in exactly the
 * layout {@code V3CorpusCapture} produces.
 *
 * <p>No network, no live match and no captured live data are involved: the fixtures below are built here
 * from hand-written payloads, then run through the same production mappers, the same V2/R3 planner and the
 * same V3 shadow evaluation the real corpus replay uses. That keeps the tooling provable before any
 * practice match exists.
 */
final class V3CorpusReplayToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A 3x1 SMALL day: 2 agents, 3 steps — the shape the runtime capture test already submits. */
    private static final String SMALL_SETUP = """
            {"daySteps":[3],"map":{"width":3,"height":1,"cells":[[0,0,0]]},
             "spots":[{"brand":1,"pos":1,"stocks":2}],"agents":[0,2],"fuelLimits":8}
            """;

    private static final String SMALL_STATE = """
            {"day":0,"agents":[{"kind":0,"pos":0,"fuel":8},{"kind":1,"pos":2,"fuel":null}],
             "others":[{}],"traffics":[]}
            """;

    /** A 6x2 LARGE day: 6 agents and a 60-step budget, i.e. the mandate's large-state shape. */
    private static final String LARGE_SETUP = """
            {"daySteps":[60],"map":{"width":6,"height":2,"cells":[[0,0,0,0,0,0],[0,0,0,0,0,0]]},
             "spots":[{"brand":1,"pos":3,"stocks":3},{"brand":2,"pos":8,"stocks":3}],
             "agents":[0,1,2,6,7,11],"fuelLimits":12}
            """;

    private static final String LARGE_STATE = """
            {"day":0,"agents":[{"kind":0,"pos":0,"fuel":12},{"kind":1,"pos":1,"fuel":null},
              {"kind":0,"pos":2,"fuel":12},{"kind":1,"pos":6,"fuel":null},
              {"kind":0,"pos":7,"fuel":12},{"kind":1,"pos":11,"fuel":null}],
             "others":[{}],"traffics":[]}
            """;

    @TempDir
    Path corpus;

    /**
     * Writes one captured day exactly as the runtime would: setup once per match, then the day payload, the
     * V2/R3 wire actions with their fingerprint, the metadata, and the acceptance marker when accepted.
     */
    private void write(String matchId, int day, String setupJson, String stateJson,
            boolean accepted, boolean corruptActions) throws IOException {
        Path matchDirectory = corpus.resolve(matchId);
        Path dayDirectory = matchDirectory.resolve("day-" + day);
        Files.createDirectories(dayDirectory);

        SetupDto setup = MAPPER.readValue(setupJson, SetupDto.class);
        DayStateDto stateDto = MAPPER.readValue(stateJson, DayStateDto.class);
        List<AgentKind> assignment = new ArrayList<>();
        for (var agent : stateDto.agents()) {
            assignment.add(AgentKind.fromCode(agent.kind()));
        }
        DayState state = new DayStateMapper().toDomain(stateDto, new SetupMapper().toDomain(setup),
                assignment);
        TeamPlan plan = new JointTeamBeamR3Planner().plan(state);
        List<List<Integer>> actions = new ActionEncoder().encode(plan, state.agents().size());
        String fingerprint = V3CorpusReplay.fingerprint(actions);
        List<List<Integer>> stored = corruptActions ? List.of(List.of(999)) : actions;

        Files.writeString(matchDirectory.resolve("setup.json"), setupJson, StandardCharsets.UTF_8);
        Files.writeString(dayDirectory.resolve("state.json"), stateJson, StandardCharsets.UTF_8);
        Files.writeString(dayDirectory.resolve("actions.json"), MAPPER.writeValueAsString(
                Map.of("fingerprint", fingerprint, "actions", stored)), StandardCharsets.UTF_8);
        List<String> kindNames = assignment.stream().map(AgentKind::name).toList();
        Files.writeString(dayDirectory.resolve("meta.json"), MAPPER.writeValueAsString(Map.of(
                "matchId", matchId, "day", day, "agentCount", state.agents().size(),
                "stepBudget", state.stepBudget(), "plannerAuthority", "V2_R3",
                "actionFingerprint", fingerprint, "assignment", kindNames)), StandardCharsets.UTF_8);
        if (accepted) {
            Files.writeString(dayDirectory.resolve("accepted.json"), MAPPER.writeValueAsString(
                    Map.of("matchId", matchId, "day", day, "accepted", true)), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("LOADER_ADMITS_ONLY_ACCEPTED_DAYS")
    void LOADER_ADMITS_ONLY_ACCEPTED_DAYS() throws IOException {
        write("m-small", 0, SMALL_SETUP, SMALL_STATE, true, false);
        write("m-small", 1, SMALL_SETUP, SMALL_STATE, false, false);

        List<V3CorpusDay> accepted = V3CorpusDay.loadAll(corpus, true);
        assertEquals(1, accepted.size(), "a day the server never accepted is not admissible evidence");
        assertEquals(0, accepted.get(0).day());
        assertTrue(accepted.get(0).accepted());
        assertEquals("SMALL", accepted.get(0).sizeClass());
        assertEquals(2, accepted.get(0).agentCount());
        assertEquals(3, accepted.get(0).stepBudget());
        assertEquals(3, accepted.get(0).mapWidth());
        assertEquals(1, accepted.get(0).spotCount());

        assertEquals(2, V3CorpusDay.loadAll(corpus, false).size(),
                "--include-rejected still sees the rejected day");
        assertEquals(List.of(), V3CorpusDay.loadAll(corpus.resolve("absent"), true));
    }

    @Test
    @DisplayName("REPLAY_PROVES_V2_PARITY")
    void REPLAY_PROVES_V2_PARITY() throws IOException {
        write("m-honest", 0, SMALL_SETUP, SMALL_STATE, true, false);
        write("m-tampered", 0, SMALL_SETUP, SMALL_STATE, true, true);

        List<V3CorpusReplayResult> results =
                new V3CorpusReplay(V3CorpusReplay.UNCAPPED_BUDGET_MILLIS)
                        .replayAll(V3CorpusDay.loadAll(corpus, true));
        assertEquals(2, results.size());

        V3CorpusReplayResult honest = results.stream()
                .filter(result -> result.matchId().equals("m-honest")).findFirst().orElseThrow();
        assertTrue(honest.parityOk(),
                "the rebuilt state must re-plan to the exact wire actions that were captured");
        assertEquals(honest.capturedFingerprint(), honest.replayedFingerprint());
        assertEquals("V2_R3", honest.plannerAuthority());
        assertTrue(honest.evaluation().pathfindingFree(),
                "global rule 17: strategic search performs no pathfinding");
        assertTrue(honest.line().startsWith("CORPUS_DAY match=m-honest day=0 size=SMALL"), honest.line());

        V3CorpusReplayResult tampered = results.stream()
                .filter(result -> result.matchId().equals("m-tampered")).findFirst().orElseThrow();
        assertFalse(tampered.parityOk(),
                "a day whose stored actions are not what V2/R3 plans must be reported as a mismatch");
        assertEquals(1, new V3CorpusScorecard(results).overall().parityMismatches());
    }

    @Test
    @DisplayName("REPLAY_IS_UNCAPPED_BY_DEFAULT")
    void REPLAY_IS_UNCAPPED_BY_DEFAULT() throws IOException {
        write("m-large", 0, LARGE_SETUP, LARGE_STATE, true, false);
        List<V3CorpusDay> days = V3CorpusDay.loadAll(corpus, true);
        assertEquals("MEDIUM", days.get(0).cohort(),
                "6 agents and a 60-step budget is the MEDIUM cohort under the three-way taxonomy");
        assertTrue(days.get(0).large(),
                "but it stays inside the wider heavy predicate that drives the large-p90 gate");

        V3CorpusReplayResult result =
                new V3CorpusReplay(V3CorpusReplay.UNCAPPED_BUDGET_MILLIS).replay(days.get(0));
        assertTrue(result.parityOk());
        assertFalse(result.evaluation().deadlineBudgetExceeded(),
                "the replay must not truncate the search it is supposed to measure");
        assertFalse(result.exceedsLiveBudget(V3CorpusScorecard.LIVE_BUDGET_MILLIS),
                "this fixture is small enough to fit the live budget: " + result.line());
    }

    @Test
    @DisplayName("SCORECARD_KEEPS_RAW_AND_SAFE_APART")
    void SCORECARD_KEEPS_RAW_AND_SAFE_APART() throws IOException {
        write("m-small", 0, SMALL_SETUP, SMALL_STATE, true, false);
        write("m-large", 0, LARGE_SETUP, LARGE_STATE, true, false);

        V3CorpusScorecard scorecard = new V3CorpusScorecard(
                new V3CorpusReplay(V3CorpusReplay.UNCAPPED_BUDGET_MILLIS)
                        .replayAll(V3CorpusDay.loadAll(corpus, true)));
        V3CorpusScorecard.Cohort all = scorecard.overall();

        assertEquals(2, all.states());
        assertEquals(all.states(), all.rawWins() + all.rawTies() + all.rawLosses(),
                "every day is exactly one raw verdict");
        assertEquals(all.states(), all.safeWins() + all.safeTies() + all.safeLosses(),
                "and exactly one safe verdict, counted separately (global rule 9)");
        assertEquals(0, all.safeLosses(),
                "the V2-incumbent-seeded fallback can never score below its own incumbent");
        assertEquals(0, all.parityMismatches());
        assertEquals(0, all.pathfindingDirty());
        assertEquals(1, scorecard.sizeClass("SMALL").states());
        assertEquals(1, scorecard.sizeClass("MEDIUM").states(),
                "the 6-agent/60-step fixture is MEDIUM, not LARGE");
        assertEquals(0, scorecard.sizeClass("LARGE").states(),
                "LARGE now means the 8-agent/100-step shape and no fixture has it");
        assertEquals(1, scorecard.heavy().states(),
                "the heavy set that carries the large-p90 gate still contains the MEDIUM fixture");
        assertEquals(3, scorecard.bySizeClass().size());
        assertEquals(2, scorecard.byMatch().size());
        assertEquals(2, scorecard.byAgentCount().size());
        assertEquals(2, scorecard.byStepBudget().size());
        assertTrue(all.line().contains("rawNonLossRate="), all.line());

        // A two-day corpus cannot satisfy PART 20: the gates must say so rather than round up.
        Map<String, String> gates = scorecard.gates();
        assertTrue(gates.get("COVERAGE_TOTAL_STATES").startsWith("FAIL"), gates.toString());
        assertTrue(gates.get("COVERAGE_SMALL_STATES").startsWith("FAIL"), gates.toString());
        assertTrue(gates.get("COVERAGE_MEDIUM_STATES").startsWith("FAIL"), gates.toString());
        assertTrue(gates.get("COVERAGE_LARGE_STATES").startsWith("FAIL"), gates.toString());
        assertTrue(gates.get("SAFE_ZERO_LOSSES").startsWith("PASS"), gates.toString());
        assertTrue(gates.get("V2_PARITY").startsWith("PASS"), gates.toString());
        assertTrue(gates.get("PATHFINDING_FREE_SEARCH").startsWith("PASS"), gates.toString());
        assertEquals(11, gates.size());
        assertEquals(1, scorecard.largeStateClassification().values().stream()
                .mapToInt(List::size).sum(), "the one heavy day is classified exactly once");
        assertEquals(2, scorecard.stateClassification().values().stream()
                .mapToInt(List::size).sum(), "every day is classified exactly once across all cohorts");
    }

    @Test
    @DisplayName("INDEX_DESCRIBES_SHAPE_AND_NO_CREDENTIAL")
    void INDEX_DESCRIBES_SHAPE_AND_NO_CREDENTIAL() throws IOException {
        write("m-small", 0, SMALL_SETUP, SMALL_STATE, true, false);
        write("m-large", 0, LARGE_SETUP, LARGE_STATE, true, false);
        List<V3CorpusDay> days = V3CorpusDay.loadAll(corpus, true);

        V3CorpusReplay.writeIndex(corpus, days);
        String index = Files.readString(corpus.resolve("index.json"), StandardCharsets.UTF_8);
        var parsed = MAPPER.readTree(index);

        assertEquals(2, parsed.get("totalDays").asInt());
        assertEquals(1, parsed.get("largeDays").asInt());
        assertEquals(1, parsed.get("smallDays").asInt());
        assertEquals(2, parsed.get("days").size());
        assertEquals(1, parsed.get("cohortCounts").get("MEDIUM").asInt(),
                "the corpus index must carry the three-way cohort counts section 15 asks for");
        assertEquals(1, parsed.get("cohortCounts").get("SMALL").asInt());
        assertEquals(0, parsed.get("cohortCounts").get("LARGE").asInt());
        assertEquals("m-large", parsed.get("days").get(0).get("matchId").asText());
        assertEquals("MEDIUM", parsed.get("days").get(0).get("sizeClass").asText(),
                "6 agents / 60 steps is MEDIUM: LARGE is reserved for the 8-agent/100-step shape");
        assertEquals("MEDIUM", parsed.get("days").get(0).get("cohort").asText());
        assertEquals("m-large/day-0", parsed.get("days").get(0).get("directory").asText());
        assertEquals(60, parsed.get("days").get(0).get("stepBudget").asInt());
        assertEquals(6, parsed.get("days").get(0).get("agentCount").asInt());

        String lowered = index.toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("token", "cookie", "hexsession", "bearer", "authorization",
                "session", "secret")) {
            assertFalse(lowered.contains(forbidden), "the index must never carry " + forbidden);
        }
    }

    @Test
    @DisplayName("EVALUATION_FIELDS_ARE_ALL_RECORDED")
    void EVALUATION_FIELDS_ARE_ALL_RECORDED() throws IOException {
        write("m-small", 0, SMALL_SETUP, SMALL_STATE, true, false);
        V3CorpusReplayResult result = new V3CorpusReplay(V3CorpusReplay.UNCAPPED_BUDGET_MILLIS)
                .replay(V3CorpusDay.loadAll(corpus, true).get(0));
        V3ShadowEvaluation value = result.evaluation();

        // PART 4 demands every one of these per replayed day; the line is the report's own source.
        String line = result.line();
        for (String field : List.of("v2Own=", "v2Hybrid4=", "rawOwn=", "rawHybrid4=", "safeOwn=",
                "safeHybrid4=", "ownDelta=", "hybridDelta4=", "rawVerdict=", "safeVerdict=",
                "fallback=", "samePhysical=", "v3Millis=", "deadlineExceeded=", "states=", "teams=",
                "materialized=", "coupled=", "pathfinding=", "v2Root=", "v3Root=")) {
            assertTrue(line.contains(field), field + " missing from " + line);
        }
        assertEquals(value.rawV3OwnSemi() - value.v2OwnSemi(), value.ownDelta());
        assertEquals(value.rawV3Hybrid4() - value.v2Hybrid4(), value.hybridDelta4());
        assertTrue(value.planningMillis() >= 0);
    }

    @Test
    @DisplayName("PROFILER_ATTRIBUTES_TIME_WITHOUT_CHANGING_THE_SEARCH")
    void PROFILER_ATTRIBUTES_TIME_WITHOUT_CHANGING_THE_SEARCH() throws IOException {
        write("m-large", 0, LARGE_SETUP, LARGE_STATE, true, false);
        V3CorpusDay day = V3CorpusDay.loadAll(corpus, true).get(0);

        V3CorpusProfiler.Profile profile =
                new V3CorpusProfiler(V3CorpusReplay.UNCAPPED_BUDGET_MILLIS).profile(day);
        V3CorpusReplayResult unprofiled =
                new V3CorpusReplay(V3CorpusReplay.UNCAPPED_BUDGET_MILLIS).replay(day);

        // Observation cannot change the search: the same state produces the same work counters whether or
        // not the phase timer is attached.
        assertEquals(unprofiled.evaluation().completeTeamCandidates(), profile.completeTeams());
        assertEquals(unprofiled.evaluation().materializedPlans(), profile.materialized());
        assertEquals(unprofiled.evaluation().coupledEvaluations(), profile.coupledEvaluations());
        assertEquals(unprofiled.evaluation().statesExpanded(), profile.statesExpanded());

        assertTrue(profile.events() > 0, "the search must have emitted observable events");
        long phaseSum = 0;
        for (V3CorpusProfiler.Phase phase : V3CorpusProfiler.Phase.values()) {
            assertTrue(profile.phase(phase) >= 0, phase + " must not be negative");
            phaseSum += profile.phase(phase);
        }
        assertTrue(phaseSum <= profile.compositionMillis() + 5,
                "the phases partition the composition interval: " + profile.line());
        assertTrue(profile.singleEvaluationMicros() > 0, "one objective evaluation has a measurable cost");
        assertTrue(profile.coupledSharePercent() >= 0.0);

        // The search's own stopwatches must corroborate the estimate rather than replace it: both stages
        // are non-negative, neither exceeds the interval that contains them, and the coupled stopwatch
        // cannot claim more time than the terminal phase it lives inside (plus timer granularity).
        assertTrue(profile.measuredMaterializationMillis() >= 0, profile.line());
        assertTrue(profile.measuredCoupledMillis() >= 0, profile.line());
        assertTrue(profile.measuredSearchMillis() >= 0, profile.line());
        assertTrue(profile.measuredMaterializationMillis() + profile.measuredCoupledMillis()
                <= profile.compositionMillis() + 5, profile.line());
        assertTrue(profile.measuredCoupledMillis() <= profile.phase(Phase.TERMINAL) + 5, profile.line());
        assertTrue(profile.measuredCoupledSharePercent() >= 0.0);
        assertTrue(profile.line().contains("measuredCoupledMillis="), profile.line());
        assertTrue(V3CorpusProfiler.bottleneck(List.of(profile)).contains("measuredDominant="),
                V3CorpusProfiler.bottleneck(List.of(profile)));
        assertTrue(V3CorpusProfiler.bottleneck(List.of(profile)).startsWith("BOTTLENECK cohort=HEAVY"),
                "the bottleneck cohort is the heavy predicate, and it must never silently widen to ALL: "
                        + V3CorpusProfiler.bottleneck(List.of(profile)));
        assertEquals("BOTTLENECK cohort=NONE states=0", V3CorpusProfiler.bottleneck(List.of()));
    }
}
