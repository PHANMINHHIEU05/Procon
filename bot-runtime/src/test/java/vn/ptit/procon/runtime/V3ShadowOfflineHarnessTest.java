package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.StrategicSearchConfig;
import vn.ptit.procon.planner.v3.V3Phase24Fixtures;
import vn.ptit.procon.planner.v3.V3Phase26Analysis;
import vn.ptit.procon.protocol.ActionEncoder;

/**
 * The runtime-level offline shadow harness, run before any live match is considered.
 *
 * <p>Each fixture takes exactly the production route — the frozen {@link JointTeamBeamR3Planner} plans, the
 * plan is validated, encoded and treated as submitted — and only then is the very same immutable
 * {@link DayState} handed to the shadow runner. Every case asserts that the SUBMITTED plan is still V2/R3's,
 * whatever V3 reported.
 *
 * <p>The frozen per-fixture Phase 2.3 budgets are supplied by the harness itself, through
 * {@link V3Phase26Analysis#frozenConfig(String)}. Production code never sees a fixture name: the live shadow
 * profile is shape-free and lives in {@code V3ShadowPlanner}.
 */
final class V3ShadowOfflineHarnessTest {

    /** Wall bound for the harness only. It is deliberately far above the ~1.8 s LIVE_LIKE fixture. */
    private static final long HARNESS_BUDGET_MILLIS = 180_000;

    @Test
    @DisplayName("V3_SHADOW_5X5_OFFLINE")
    void V3_SHADOW_5X5_OFFLINE() {
        Harvest harvest = run("5x5-raw-kind-zero", V3Phase24Fixtures.rawKindZero5x5());
        harvest.assertSubmittedPlanIsV2();
        assertEquals(7, harvest.comparison().v2OwnSemi(), "5x5 frozen V2 own");
        assertEquals(29, harvest.comparison().v2Hybrid4(), "5x5 frozen V2 hybrid4");
        assertEquals(8, harvest.comparison().rawV3OwnSemi(), "5x5 raw V3 own");
        assertEquals(32, harvest.comparison().rawV3Hybrid4(), "5x5 raw V3 hybrid4");
    }

    @Test
    @DisplayName("V3_SHADOW_LIVE_LIKE_OFFLINE")
    void V3_SHADOW_LIVE_LIKE_OFFLINE() {
        Harvest harvest = run("live-like-m6861", V3Phase24Fixtures.liveLike());
        harvest.assertSubmittedPlanIsV2();
        assertEquals(8, harvest.comparison().v2OwnSemi(), "live-like frozen V2 own");
        assertEquals(34, harvest.comparison().v2Hybrid4(), "live-like frozen V2 hybrid4");
        assertEquals(9, harvest.comparison().rawV3OwnSemi(), "live-like raw V3 own");
        assertEquals(37, harvest.comparison().rawV3Hybrid4(), "live-like raw V3 hybrid4");
    }

    @Test
    @DisplayName("V3_SHADOW_CURRENT_LARGE_OFFLINE")
    void V3_SHADOW_CURRENT_LARGE_OFFLINE() {
        Harvest harvest = run("large-6-agent-60-step", V3Phase24Fixtures.currentLarge());
        harvest.assertSubmittedPlanIsV2();
        assertEquals(14, harvest.comparison().v2OwnSemi(), "current large frozen V2 own");
        assertEquals(56, harvest.comparison().v2Hybrid4(), "current large frozen V2 hybrid4");
        assertEquals(14, harvest.comparison().rawV3OwnSemi(), "current large raw V3 own");
        assertEquals(56, harvest.comparison().rawV3Hybrid4(), "current large raw V3 hybrid4");
        assertFalse(harvest.result().fallbackUsedInsideV3(),
                "Phase 2.6 reached CURRENT LARGE parity without the incumbent fallback");
    }

    @Test
    @DisplayName("V3_SHADOW_LARGE_DENSE_OFFLINE")
    void V3_SHADOW_LARGE_DENSE_OFFLINE() {
        Harvest harvest = run("LARGE_DENSE", V3Phase24Fixtures.largeDense());
        harvest.assertSubmittedPlanIsV2();
        assertEquals(13, harvest.comparison().v2OwnSemi(), "large dense frozen V2 own");
        assertEquals(52, harvest.comparison().v2Hybrid4(), "large dense frozen V2 hybrid4");
        assertEquals(15, harvest.comparison().rawV3OwnSemi(), "large dense raw V3 own");
        assertEquals(60, harvest.comparison().rawV3Hybrid4(), "large dense raw V3 hybrid4");
        assertTrue(harvest.comparison().hybridDelta4() > 0, "large dense is the strongest raw V3 fixture");
        assertFalse(harvest.comparison().samePhysicalPlan(),
                "a raw hybrid gain must come from a different physical plan");
    }

    @Test
    @DisplayName("V3_SHADOW_PATHFINDING_ZERO")
    void V3_SHADOW_PATHFINDING_ZERO() {
        for (String fixture : List.of("5x5-raw-kind-zero", "large-6-agent-60-step", "LARGE_DENSE")) {
            Harvest harvest = run(fixture, V3Phase24Fixtures.phase24Table().get(fixture));
            assertEquals(0, harvest.result().pathfindingExecutions(),
                    fixture + ": strategic search must execute no pathfinding");
            assertTrue(harvest.result().evaluation().orElseThrow().pathfindingFree(), fixture);
        }
    }

    @Test
    @DisplayName("V3_SHADOW_DETERMINISTIC")
    void V3_SHADOW_DETERMINISTIC() {
        Harvest first = run("LARGE_DENSE", V3Phase24Fixtures.largeDense());
        Harvest second = run("LARGE_DENSE", V3Phase24Fixtures.largeDense());
        assertEquals(first.comparison().rawV3OwnSemi(), second.comparison().rawV3OwnSemi());
        assertEquals(first.comparison().rawV3Hybrid4(), second.comparison().rawV3Hybrid4());
        assertEquals(first.result().v3SupportRoot(), second.result().v3SupportRoot());
        assertEquals(first.result().v3PhysicalSignature(), second.result().v3PhysicalSignature());
        assertEquals(first.result().v3StrategicSignature(), second.result().v3StrategicSignature());
        assertNotEquals("NA", first.result().v3StrategicSignature());
    }

    @Test
    @DisplayName("V2_PRODUCTION_INVARIANCE")
    void V2_PRODUCTION_INVARIANCE() {
        V3Phase24Fixtures.phase24Table().forEach((fixture, state) -> {
            String before = wire(new JointTeamBeamR3Planner().plan(state), state);
            Harvest harvest = run(fixture, state);
            assertEquals(before, harvest.submittedWireSignature(),
                    fixture + ": the submitted wire payload must not depend on the shadow");
            assertEquals(V3ShadowSubmissionAuthority.V2_R3, harvest.authority().submittedPlanner());
            assertTrue(harvest.authority().submittedMatchesV2(), fixture);
        });
    }

    @Test
    @DisplayName("R3_PRODUCTION_INVARIANCE")
    void R3_PRODUCTION_INVARIANCE() {
        DayState state = V3Phase24Fixtures.liveLike();
        JointTeamBeamR3Planner planner = new JointTeamBeamR3Planner();
        String beforeShadow = wire(planner.planWithStats(state).plan(), state);
        Harvest harvest = run("live-like-m6861", state);
        String afterShadow = wire(planner.planWithStats(state).plan(), state);
        assertEquals(beforeShadow, afterShadow,
                "R3 must produce the identical plan before and after a shadow evaluation");
        assertEquals(beforeShadow, harvest.submittedWireSignature(), "the submitted payload is R3's");
        assertTrue(harvest.result().completed());
        assertTrue(harvest.result().evaluation().orElseThrow().v2SupportRoot().length() > 0,
                "the incumbent support root is named from R3's own exported roots");
    }

    private static String wire(TeamPlan plan, DayState state) {
        return V3ShadowSubmissionAuthority.wireSignature(encode(plan, state));
    }

    /**
     * The offline analogue of {@link ActionEncoder}. The Phase 2.3 fixtures identify their REFUEL agent as
     * {@code AgentId(9)}, so their ids are not the contiguous protocol indices the wire encoder requires;
     * this helper applies exactly the encoder's command mapping — a move becomes its direction code, a wait
     * becomes its negated step count — over the state's own agent order instead.
     */
    private static List<List<Integer>> encode(TeamPlan plan, DayState state) {
        List<List<Integer>> payload = new ArrayList<>();
        for (AgentState agent : state.agents()) {
            List<AgentAction> actions = plan.actionsFor(agent.id());
            assertNotNull(actions, "Every agent must be planned for: " + agent.id());
            payload.add(actions.stream()
                    .map(action -> action instanceof MoveAction move ? move.direction().code()
                            : -((WaitAction) action).steps())
                    .toList());
        }
        return List.copyOf(payload);
    }

    /** One fixture's whole production-plus-shadow observation. */
    private record Harvest(String fixture, V3ShadowResult result, V3ShadowComparison comparison,
            String submittedWireSignature, V3ShadowSubmissionAuthority authority,
            V3ShadowStateSnapshotAudit snapshotAudit, V3ShadowAggregate.Snapshot summary,
            List<String> events) {

        /** The one invariant every offline case shares: the plan on the wire is still V2/R3's. */
        private void assertSubmittedPlanIsV2() {
            assertTrue(authority.submittedMatchesV2(), fixture + ": submitted payload must be V2/R3's");
            assertEquals(V3ShadowSubmissionAuthority.V2_R3, authority.submittedPlanner(), fixture);
            assertEquals(authority.v2PhysicalSignature(), authority.submittedPhysicalSignature(), fixture);
            assertTrue(result.completed(), fixture + ": " + result);
            assertTrue(snapshotAudit.same(), fixture + ": V3 must read the state V2 planned from");
            assertTrue(summary.accountedFor() && summary.settled(), fixture + ": " + summary);
            assertEquals(0, result.pathfindingExecutions(), fixture);
        }
    }

    /**
     * The production route, offline: V2/R3 plans, the plan is validated and encoded as if POSTed, and only
     * afterwards is the shadow scheduled on the same immutable state.
     */
    private static Harvest run(String fixture, DayState state) {
        Objects.requireNonNull(state, "Fixture state must not be null");
        TeamPlan submitted = new JointTeamBeamR3Planner().plan(state);
        assertTrue(new PlanValidator().validate(state, submitted).valid(),
                fixture + ": the frozen production plan must validate");
        List<List<Integer>> payload = encode(submitted, state);
        String submittedWire = V3ShadowSubmissionAuthority.wireSignature(payload);
        String reEncodedWire = V3ShadowSubmissionAuthority.wireSignature(encode(submitted, state));
        String v2Fingerprint = V3ShadowStateSnapshotAudit.fingerprint(state);
        StrategicSearchConfig frozen = V3Phase26Analysis.frozenConfig(fixture);
        List<String> events = new CopyOnWriteArrayList<>();
        try (V3ShadowRunner runner = V3ShadowRunner.enabled(HARNESS_BUDGET_MILLIS, true,
                new V3ShadowPlannerEvaluator(frozen))) {
            V3ShadowSubmissionAuthority authority = new V3ShadowSubmissionAuthority(state.day().value(),
                    V3ShadowSubmissionAuthority.V2_R3, reEncodedWire, submittedWire,
                    runner.lastV3PhysicalSignature(), reEncodedWire.equals(submittedWire));
            runner.schedule(state.day().value(), state, submitted, v2Fingerprint, "offline-" + fixture,
                    authority, (event, fields) -> events.add(event));
            assertTrue(runner.awaitIdle(HARNESS_BUDGET_MILLIS), fixture + ": shadow did not settle");
            List<V3ShadowResult> results = new ArrayList<>(runner.results());
            assertEquals(1, results.size(), fixture);
            List<V3ShadowComparison> comparisons = new ArrayList<>(runner.comparisons());
            assertEquals(1, comparisons.size(), fixture + ": " + results.get(0));
            return new Harvest(fixture, results.get(0), comparisons.get(0), submittedWire, authority,
                    runner.snapshotAudits().get(0), runner.summary(), List.copyOf(events));
        }
    }
}
