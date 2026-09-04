package vn.ptit.procon.planner.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.Route;

/**
 * V3 Phase 2.4 — baseline replayability and raw search robustness.
 *
 * <p>Every assertion is anchored on a recorded run of the real bounded search or on the frozen V2/R3
 * planner; none of them re-implements either.  The eight-fixture table is computed once because every
 * audit in it is deterministic.
 */
class V3Phase24RawSearchRobustnessTest {

    private static final String CURRENT_LARGE = "large-6-agent-60-step";
    private static Map<String, V3Phase24FixtureReport> table;

    private static synchronized Map<String, V3Phase24FixtureReport> table() {
        if (table == null) table = new V3Phase24Analysis().table();
        return table;
    }

    private static V3Phase24FixtureReport report(String fixture) {
        V3Phase24FixtureReport value = table().get(fixture);
        assertNotNull(value, "missing fixture " + fixture);
        return value;
    }

    private static V3Phase24FixtureReport currentLarge() { return report(CURRENT_LARGE); }

    @Test
    void V2_PLAN_TO_STRATEGIC_WITNESS() {
        V3Phase24FixtureReport report = currentLarge();
        V2StrategicWitness strategic = report.strategic();
        assertEquals(14, strategic.totalStrategicTransitions());
        assertEquals(14, strategic.totalStrategicCollections());
        assertEquals(report.witness().ownSemiCollections(), strategic.totalStrategicCollections(),
                "the skeleton is derived from the plan, so it must account for every V2 collection");
        for (V2StrategicWitness.PatrolSkeleton patrol : strategic.patrols()) {
            V2BaselineWitness.PatrolWitness source = report.witness().patrol(patrol.patrolId());
            assertEquals(source.start(), patrol.start());
            assertEquals(source.startFuel(), patrol.startFuel());
            assertEquals(source.claimedPositions(), patrol.orderedStrategicCollections());
            int cursor = -1;
            for (Position target : patrol.orderedStrategicCollections()) {
                cursor = source.movementRoute().subList(cursor + 1, source.movementRoute().size())
                        .indexOf(target) + cursor + 1;
                assertTrue(cursor > 0 || target.equals(source.start()),
                        "every strategic target must appear on the real V2 movement route in order");
            }
            assertEquals(patrol.transitions().size(), patrol.orderedStrategicCollections().size());
        }
    }

    @Test
    void CURRENT_LARGE_V2_WITNESS_VALID() {
        V2BaselineWitness witness = currentLarge().witness();
        assertTrue(witness.validatorAccepted(), "PlanValidator must accept the frozen V2/R3 plan");
        assertTrue(witness.simulatorValid(), "DaySimulator must accept the frozen V2/R3 plan");
        assertEquals(witness.ownSemiCollections(), witness.tracedCollections(),
                "the per-patrol trace must add up to the simulated total");
    }

    @Test
    void CURRENT_LARGE_V2_WITNESS_OWN_14() {
        V2BaselineWitness witness = currentLarge().witness();
        assertEquals(14, witness.ownSemiCollections());
        assertEquals(14, witness.coupledOwnCollections());
        assertEquals(4, witness.ownSemiBrands());
        assertEquals(56, witness.hybridMarginScore4());
        assertEquals(14, currentLarge().v2Own());
    }

    @Test
    void V2_WITNESS_GRAPH_REPRESENTABILITY() {
        V3BaselineRepresentability value = currentLarge().representability();
        assertEquals(14, value.totalV2Transitions());
        assertEquals(2, value.representedTransitions());
        assertEquals(12, value.missingTransitions());
        assertEquals(value.totalV2Transitions(),
                value.representedTransitions() + value.missingTransitions());
        assertFalse(value.fullyRepresentable(), "the V2 witness is outside V3's representable space");
        assertEquals("patrol0#0 0->2", value.firstMissingTransition());
        assertEquals(V3RepresentabilityReason.SUPPORT_ROOT_MISSING, value.firstMissingReason());
        assertTrue(value.transitions().stream().allMatch(
                V3BaselineRepresentability.TransitionAudit::graphNodeDestinationPresent),
                "every V2 destination is a graph node: this is not a node problem");
    }

    @Test
    void V2_WITNESS_SUPPORT_REPLAYABILITY() {
        V3BaselineRepresentability.SupportRootReplay root = currentLarge().representability().supportRoot();
        assertEquals("9:2@48/4,0@0/12,3@95/40", root.v2SupportRootSignature());
        assertEquals(3, root.serviceCount());
        assertEquals(List.of(0, 2, 3), root.supportedPatrols());
        assertEquals(List.of(0, 1, 2, 48, 95), root.v2RefuelPositions());
        assertEquals(List.of(72), root.v3RefuelPositions(),
                "V3 can only refuel on the tanker's start cell, which V2 never uses");
        assertFalse(root.rootGeneratedByV3(), "V3 never generates a moving support root");
        assertFalse(root.rootRetainedByV3());
        assertEquals(0, root.v3MovingSupportAgents());
        assertEquals("NO_REFUEL", root.v3SupportStates());
        assertEquals(1, root.v3UniqueSupportClasses());
        assertFalse(root.fullyReplayable());
    }

    @Test
    void V2_WITNESS_POST_SUPPORT_STATE() {
        V3BaselineRepresentability.SupportRootReplay root = currentLarge().representability().supportRoot();
        assertEquals(4, root.postPrefixBoundaryStep(), "V2's first tanker service lands at step 4");
        assertTrue(root.postPrefixPositionMatch(), "no patrol has moved before its first service");
        assertFalse(root.postPrefixFuelMatch(), "V3 cannot reach V2's post-service fuel");
        assertFalse(root.postPrefixTimelineMatch(), "V2 services at step 4/12/40, V3 only at step 0");
        assertFalse(root.postPrefixStockMatch());
        assertFalse(root.postPrefixBrandMatch());
        V3ForcedWitnessReplayResult granted = currentLarge().supportGrantedReplay();
        assertEquals(14, granted.transitionsResolved(),
                "granting exactly the post-service fuel removes every other obstacle");
    }

    @Test
    void V2_WITNESS_ALLOCATION_GENERATED() {
        V3AllocationReplay value = currentLarge().allocation();
        assertEquals(16, value.allocationsGenerated());
        assertEquals(16, value.allocationsRetained());
        assertEquals(16, value.scores().size());
        assertEquals(List.of("SPLIT", "SHARED", "CROSS_REGION", "SECONDARY", "ROTATION"),
                value.generatedSupportClasses());
        assertFalse(value.supportClassRepresented(),
                "V3's allocation vocabulary has no refuel-root axis at all");
        assertFalse(value.exactAllocationGenerated());
        assertEquals("SUPPORT_AXIS_ABSENT_FROM_ALLOCATION_VOCABULARY",
                value.missingAllocationClassification());
        assertEquals("R0={0,2,3,4}", value.sharedRegionPattern(),
                "every current-large opportunity is in one region, so no region split can matter");
        assertEquals(0, value.scores().stream().filter(score -> score.statesGenerated() > 0)
                .filter(score -> score.bestOwn() > 1).count(),
                "no allocation buys more than one collection");
    }

    @Test
    void V2_WITNESS_PREFIX_SURVIVAL_TRACE() {
        V3PrefixSurvivalTrace trace = currentLarge().prefixSurvival();
        assertEquals(14, trace.requiredDepth());
        assertEquals(15, trace.depths().size(), "depth 0 is the root set, then one row per commitment");
        assertTrue(trace.depths().get(0).survived(), "the root set is always V2-compatible");
        assertTrue(trace.depths().get(1).survived(), "patrol 4's only V2 commitment does survive");
        assertFalse(trace.depths().get(2).survived());
        assertEquals(1, trace.deepestSurvivingDepth());
        assertEquals(2, trace.firstDivergenceDepth());
        assertFalse(trace.fullPrefixSurvived());
        assertEquals(0, trace.depths().get(2).generated(),
                "at depth 2 no V2-compatible child is generated at all");
        assertEquals(0, trace.depths().get(2).rejectedCandidates(),
                "and none is rejected either: the edge is never offered, so this is not a filter");
        trace.depths().forEach(depth -> assertEquals(depth.retainedAfterDedup(),
                depth.retainedAfterDominance(), "V3 has no dominance stage"));
    }

    @Test
    void FIRST_DIVERGENCE_CLASSIFICATION() {
        assertEquals(V3FirstDivergence.SUPPORT_ROOT, currentLarge().prefixSurvival().classification());
        assertEquals(V3FirstDivergence.SUPPORT_ROOT, currentLarge().firstDivergence());
        assertTrue(currentLarge().prefixSurvival().firstDivergenceDetail().contains("SUPPORT_ROOT_MISSING"));
        assertEquals(1, table().values().stream()
                .map(V3Phase24FixtureReport::firstDivergence).distinct()
                .filter(value -> value == V3FirstDivergence.SUPPORT_ROOT).count(),
                "exactly one classification is reported per fixture");
        assertEquals(Set.of(V3FirstDivergence.SUPPORT_ROOT, V3FirstDivergence.BEAM),
                table().values().stream().map(V3Phase24FixtureReport::firstDivergence)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                "BEAM appears only where raw V3 already matches or beats V2 with a different plan");
    }

    @Test
    void TRAJECTORY_CACHE_UNIQUE_EDGE_KEYS() {
        V3SearchCoverage coverage = currentLarge().coverage();
        assertEquals(coverage.trajectoryCacheHits() + coverage.trajectoryCacheMisses(),
                coverage.trajectoryCacheRequests());
        assertEquals(coverage.trajectoryCacheMisses(), coverage.trajectoryCacheEntries(),
                "one cache entry per miss means no key ever aliased two different trajectories");
        assertEquals(2, coverage.trajectoryCacheEntries());
        assertEquals(60, coverage.trajectoryCacheRequests());
        assertEquals(58, coverage.trajectoryCacheHits());
        assertEquals(1, coverage.uniqueAgentContexts(),
                "only patrol 4 ever commits an edge, which is why two entries suffice");
        table().values().forEach(report -> assertEquals(report.coverage().trajectoryCacheMisses(),
                report.coverage().trajectoryCacheEntries(), report.fixture()));
    }

    @Test
    void TRAJECTORY_CACHE_NO_COLLISION() {
        for (Direction direction : Direction.values()) {
            assertTrue(direction.code() >= 0 && direction.code() <= 5,
                    "the cache key concatenates direction codes, so each code must be one digit");
        }
        DayState state = V3Phase24Fixtures.currentLarge();
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder()
                .build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        StrategicTrajectoryCache cache = new StrategicTrajectoryCache(state);
        Set<String> keys = new LinkedHashSet<>();
        List<CachedTrajectoryEffect> effects = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            graph.agentRoutes().forEach((agentId, routes) -> routes.values().forEach(route -> {
                keys.add(key(agentId, route));
                effects.add(cache.effect(agentId, route));
            }));
            AgentId probe = new AgentId(0);
            graph.opportunityRoutes().values().forEach(routes -> routes.values().forEach(route -> {
                keys.add(key(probe, route));
                effects.add(cache.effect(probe, route));
            }));
        }
        AgentId probe = new AgentId(0);
        assertEquals(keys.size(), cache.size(),
                "distinct keys must never share a cache slot and equal keys must never split one");
        assertEquals(2 * keys.size(), effects.size(), "the probe really did exercise repeated lookups");
        Map<String, String> fingerprints = new java.util.LinkedHashMap<>();
        graph.opportunityRoutes().forEach((from, routes) -> routes.forEach((to, route) -> {
            String existing = fingerprints.put(key(probe, route), from.value() + "->" + to.value());
            assertTrue(existing == null || existing.equals(from.value() + "->" + to.value()),
                    "two different strategic edges collided on one cache key: " + existing);
        }));
    }

    private static String key(AgentId agentId, Route route) {
        StringBuilder value = new StringBuilder(agentId.value() + ":" + route.start().value() + ":"
                + route.goal().value() + ":");
        route.directions().forEach(direction -> value.append(direction.code()));
        return value.toString();
    }

    @Test
    void LARGE_GRAPH_EDGE_EXPANSION_AUDIT() {
        V3SearchCoverage coverage = currentLarge().coverage();
        assertEquals(116, coverage.retainedGraphEdges());
        assertEquals(118, coverage.strategicEdgesAvailable(), "116 strategic edges plus 2 entry routes");
        assertEquals(2, coverage.edgesActuallyExpanded());
        assertTrue(coverage.graphCoverageDuringSearch() < 2.0,
                "under 2% of the retained graph is ever committed: " + coverage.graphCoverageDuringSearch());
        assertEquals(Map.of(0, 0, 1, 0, 2, 0, 3, 0, 4, 2), coverage.graphEntryPointsByPatrol(),
                "four of five patrols have no graph entry point at all");
        assertEquals(Map.of(4, 2), coverage.committedTargetsByPatrol());
        assertEquals(0, coverage.quotaTruncations(), "no cap was ever reached, so no cap can be blamed");
    }

    @Test
    void ALLOCATION_REGION_RESTRICTION_AUDIT() {
        V3SearchCoverage coverage = currentLarge().coverage();
        assertEquals(0, coverage.edgesRejectedOnlyBecauseOutsideAssignedRegion(),
                "allocation is a sort preference in StrategicRouteSkeletonSearch, never a hard filter");
        assertEquals(0, coverage.rejectedByAllocation());
        assertTrue(coverage.allocationPreferredCandidates() > 0,
                "the preference is genuinely exercised, it simply cannot reject anything");
        assertEquals(0, currentLarge().allocation().scores().stream()
                .filter(score -> score.coverageRatio() >= 1.0).count(),
                "no allocation covers the whole V2 target vector, and none needs to");
    }

    @Test
    void VISITED_STOCK_REJECTION_AUDIT() {
        V3SearchCoverage coverage = currentLarge().coverage();
        assertEquals(0, coverage.rejectedByStock(), "V3 never rejects an edge for stock");
        assertEquals(0, coverage.candidatesWithZeroStock(), "and no candidate had empty stock anyway");
        assertEquals(0, coverage.candidatesAlreadyClaimed());
        assertEquals(0, coverage.rejectedByVisited());
        assertEquals(0, coverage.rejectedByTime());
        assertEquals(0, coverage.rejectedByTrajectory());
        assertEquals(0, coverage.rejectedByOther());
        assertEquals(213, coverage.rejectedByFuel(), "fuel is the only rejection reason that ever fires");
        assertEquals(58, coverage.rejectedByDedup());
    }

    @Test
    void RAW_TERMINAL_DISTRIBUTION() {
        V3SearchCoverage coverage = currentLarge().coverage();
        assertEquals(16, coverage.terminalCount());
        assertEquals(1, coverage.ownMin());
        assertEquals(1, coverage.ownMedian());
        assertEquals(1, coverage.ownP90());
        assertEquals(1, coverage.ownMax(), "the best raw terminal really is 1, so ranking is not at fault");
        assertEquals(4, coverage.hybridMin());
        assertEquals(4, coverage.hybridMax());
        assertEquals(16, coverage.terminalsWithOwnAtLeast1());
        assertEquals(0, coverage.terminalsWithOwnAtLeast5());
        assertEquals(0, coverage.terminalsWithOwnAtLeast10());
        assertEquals(0, coverage.terminalsWithOwnAtLeast14());
        assertEquals(List.of("0:/1:/2:/3:/4:130"), coverage.distinctTerminalRouteVectors(),
                "every terminal is the same single-commitment skeleton");
        assertEquals(1, coverage.maxPredictedOwn());
        assertEquals(0, coverage.terminalsWithPredictionMismatch(),
                "predicted own equals materialized own on every terminal");
        assertEquals(1, currentLarge().rawV3Own());
        assertEquals(1, currentLarge().oracleOwn(),
                "the uncapped-within-cap representation oracle also reaches only 1");
    }

    @Test
    void FORCED_BASELINE_WITNESS_REPLAY() {
        V3ForcedWitnessReplayResult strict = currentLarge().strictReplay();
        assertEquals(V3ForcedWitnessReplayResult.Mode.STRICT, strict.mode());
        assertEquals(14, strict.transitionsAttempted());
        assertEquals(1, strict.transitionsResolved());
        assertEquals(1, strict.reproducedOwn());
        assertEquals("patrol0#0 0->2", strict.firstMismatch());
        assertEquals("NO_GRAPH_ENTRY_ROUTE_FOR_PATROL", strict.firstMismatchReason());
        assertEquals(1, currentLarge().forcedWitnessReplayOwn());

        V3ForcedWitnessReplayResult granted = currentLarge().supportGrantedReplay();
        assertEquals(V3ForcedWitnessReplayResult.Mode.SUPPORT_GRANTED, granted.mode());
        assertEquals(14, granted.transitionsAttempted());
        assertEquals(14, granted.transitionsResolved());
        assertEquals(14, granted.reproducedOwn());
        assertEquals(56, granted.reproducedHybrid4());
        assertEquals("NONE", granted.firstMismatch(),
                "with V2's delivered fuel every V3 primitive resolves the whole witness");
        assertTrue(granted.notes().contains("PATROL_FUEL_GRANTED 0:10,1:2,2:10,3:10,4:2"), granted.notes());
        assertTrue(granted.notes().contains("ORIGINAL 0:0,1:2,2:1,3:0,4:2"), granted.notes());
    }

    @Test
    void FORCED_REPLAY_MATCHES_SIMULATOR() {
        V3ForcedWitnessReplayResult granted = currentLarge().supportGrantedReplay();
        assertTrue(granted.planMaterialized(), "the forced skeleton must materialize into a real TeamPlan");
        assertTrue(granted.validatorAccepted(), "PlanValidator must accept the forced plan");
        assertEquals(granted.reproducedOwn(), granted.simulatorOwn(),
                "the replayed chronology count must equal what DaySimulator actually collects");
        assertEquals(currentLarge().witness().ownSemiCollections(), granted.simulatorOwn(),
                "and it must equal the frozen V2/R3 baseline exactly");
        assertEquals(14, granted.resolvedRoutes().size(),
                "one resolved route entry per replayed V2 transition");
    }

    @Test
    @DisplayName("5X5_NON_REGRESSION")
    void _5X5_NON_REGRESSION() {
        V3Phase24FixtureReport report = report("5x5-raw-kind-zero");
        assertEquals(7, report.v2Own());
        assertEquals(8, report.rawV3Own(), "the Phase 2.3 raw gain 7->8 must survive Phase 2.4");
        assertEquals(8, report.selectedOwn());
        assertEquals(32, report.selectedHybrid4());
        assertEquals("WIN", report.rawOutcome());
        assertFalse(report.fallbackUsed(), "this win is genuinely the raw planner's, not the incumbent's");
    }

    @Test
    void LIVE_LIKE_NON_REGRESSION() {
        V3Phase24FixtureReport report = report("live-like-m6861");
        assertEquals(8, report.v2Own());
        assertEquals(9, report.rawV3Own(), "the Phase 2.3 raw gain 8->9 must survive Phase 2.4");
        assertEquals(9, report.selectedOwn());
        assertEquals(37, report.selectedHybrid4());
        assertEquals("WIN", report.rawOutcome());
        assertFalse(report.v2WitnessRepresentable(),
                "V3 wins here without being able to represent the V2 witness: representability is not necessary");
    }

    @Test
    void LARGE_DENSE_NON_REGRESSION() {
        V3Phase24FixtureReport report = report("LARGE_DENSE");
        assertEquals(13, report.v2Own());
        assertEquals(15, report.rawV3Own(), "the Phase 2.3 raw gain 13->15 must survive Phase 2.4");
        assertEquals(15, report.selectedOwn());
        assertEquals(60, report.selectedHybrid4());
        assertEquals("WIN", report.rawOutcome());
        assertEquals(12, report.forcedWitnessReplayOwn(),
                "forced witness replay reaches only 12 here, yet raw V3 finds 15 on its own");
    }

    @Test
    void MEDIUM_NON_REGRESSION() {
        Map<String, Integer> expected = Map.of("MEDIUM_REGION_RELOCATION", 5,
                "MEDIUM_SHARED_STOCK", 6, "MEDIUM_SUPPORT_CHAIN", 5);
        expected.forEach((fixture, own) -> {
            V3Phase24FixtureReport report = report(fixture);
            assertEquals(own, report.v2Own(), fixture);
            assertEquals(own, report.rawV3Own(), fixture + " must still tie, untuned");
            assertEquals(own, report.selectedOwn(), fixture);
            assertEquals("TIE", report.rawOutcome(), fixture);
        });
        assertEquals(V3FirstDivergence.SUPPORT_ROOT, report("MEDIUM_SUPPORT_CHAIN").firstDivergence());
        assertEquals(V3FirstDivergence.BEAM, report("MEDIUM_SHARED_STOCK").firstDivergence());
    }

    @Test
    void FALLBACK_NON_REGRESSION() {
        table().forEach((fixture, report) -> {
            assertTrue(report.selectedOwn() >= report.v2Own(),
                    fixture + " selected=" + report.selectedOwn() + " v2=" + report.v2Own());
            assertTrue(report.selectedOwn() >= report.rawV3Own(), fixture);
            assertTrue(report.selectedHybrid4() >= report.v2Hybrid4(), fixture);
        });
        V3Phase24Analysis.Scorecard card = V3Phase24Analysis.scorecard(table());
        assertEquals(0, card.selectedLosses(), "the V2 incumbent fallback still admits no selected loss");
        assertEquals(3, card.selectedWins());
        assertEquals(5, card.selectedTies());
        assertEquals(3, card.rawWins());
        assertEquals(4, card.rawTies());
        assertEquals(1, card.rawLosses(), "the raw planner is scored on its own and does lose once");
        assertEquals(List.of("large-6-agent-60-step(-13)"), card.rawCatastrophicRegressions(),
                "the absolute delta is reported, never a magic percentage");
        assertEquals(14, currentLarge().selectedOwn(), "current large is rescued only by the incumbent");
        assertTrue(currentLarge().fallbackUsed());
    }

    @Test
    void STRATEGIC_SEARCH_PATHFINDING_ZERO() {
        table().forEach((fixture, report) -> assertEquals(0, report.searchPathfindingExecutions(),
                fixture + " executed pathfinding inside the strategic search"));
        assertEquals(0, currentLarge().searchPathfindingExecutions());
    }

    @Test
    void PRODUCTION_INVARIANCE() {
        table().forEach((fixture, report) -> {
            DayState state = V3Phase24Fixtures.phase24Table().get(fixture);
            StrategicSearchConfig config = V3Phase24Analysis.frozenConfig(fixture);
            StrategicSearchResult plain = new StrategicTeamSearch()
                    .solve(state, config, List.of(report.witness().plan()), 0, 0);
            assertEquals(report.rawV3Own(), plain.rawWinner().ownSemiCollections(),
                    fixture + ": observing the search must not change its raw outcome");
            assertEquals(report.selectedOwn(), plain.winner().ownSemiCollections(), fixture);
            assertEquals(report.selectedHybrid4(), plain.winner().hybridMarginScore4(), fixture);
            assertEquals(report.statesExpanded(), plain.diagnostics().statesExpanded(), fixture);
            assertEquals(report.trajectoryCacheEntries(), plain.diagnostics().trajectoryCacheEntries(), fixture);
            assertEquals(report.fallbackUsed(), plain.fallbackUsed(), fixture);
            assertEquals(0, plain.diagnostics().searchPathfindingExecutions(), fixture);
            assertTrue(report.parityMatch(), fixture + " terminal parity must hold");
        });
    }

    @Test
    void DETERMINISM() {
        V3Phase24Analysis analysis = new V3Phase24Analysis();
        DayState state = V3Phase24Fixtures.currentLarge();
        V3Phase24FixtureReport first = analysis.analyse(CURRENT_LARGE, state);
        V3Phase24FixtureReport second = analysis.analyse(CURRENT_LARGE, state);
        assertEquals(stableRow(first), stableRow(second), "every audited row must be reproducible");
        assertEquals(first.coverage().distinctTerminalRouteVectors(),
                second.coverage().distinctTerminalRouteVectors());
        assertEquals(first.representability().firstMissingTransition(),
                second.representability().firstMissingTransition());
        assertEquals(first.prefixSurvival().firstDivergenceDepth(),
                second.prefixSurvival().firstDivergenceDepth());
        assertEquals(first.allocation().generatedSupportClasses(),
                second.allocation().generatedSupportClasses());
        assertEquals(first.strictReplay().firstMismatch(), second.strictReplay().firstMismatch());
        assertEquals(first.supportGrantedReplay().notes(), second.supportGrantedReplay().notes());
        assertEquals(first.oracleOwn(), second.oracleOwn());
        assertEquals(stableRow(currentLarge()), stableRow(first),
                "and it must match the shared table used by every other assertion");
    }

    /**
     * The mandated table row minus its {@code searchMillis} column: wall-clock duration is the one
     * reported field that is measured rather than derived, so it is excluded from equality instead of
     * being pretended deterministic.
     */
    private static List<String> stableRow(V3Phase24FixtureReport report) {
        List<String> row = new ArrayList<>(report.tableRow());
        row.remove(13);
        return row;
    }
}
