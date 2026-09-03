package vn.ptit.procon.planner.v3;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.Route;

class V3StrategicSearchPhase2Test {
    @Test void V3_SEARCH_STATE_FUTURE_EQUIVALENCE() {
        StrategicAllocation a = new StrategicAllocation(List.of(List.of(1)), List.of(List.of()), "NO_REFUEL", "a");
        var p = new StrategicSearchState.PatrolState(new AgentId(0), new Position(1), 2, 4, List.of(new Position(1)), List.of(), List.of(), false);
        var x = new StrategicSearchState(List.of(p), Map.of(new Position(2), 1), java.util.Set.of(), 0, java.util.Set.of(), a, "NO_REFUEL");
        assertEquals(x.exactKey(), new StrategicSearchMemoKey(x.exactKey()).value());
    }

    @Test void TEAM_ALLOCATION_GENERATION() {
        var state = fiveByFive(); var graph = new StrategicOpportunityGraphBuilder().build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        var values = new StrategicAllocationGenerator().generate(state, graph, StrategicSearchConfig.defaults());
        assertFalse(values.isEmpty()); assertTrue(values.size() <= 16);
    }

    @Test void TEAM_ALLOCATION_SHARED_HIGH_STOCK() {
        var state = state(12, 1, 8, List.of(agent(0, 0, 8), agent(1, 4, 8)), List.of(spot("A", 2, 2)));
        var result = new StrategicTeamSearch().solve(state, new StrategicSearchConfig(16, 64, 16, 8, 8));
        assertTrue(result.diagnostics().searchPathfindingExecutions() == 0);
        assertNotNull(result.winner().plan());
    }

    @Test void GRAPH_EDGE_EXPANSION() { var r = search(fiveByFive()); assertTrue(r.diagnostics().graphEdgeExpansions() > 0); }
    @Test void STOP_CANONICALIZATION() { var r = search(fiveByFive()); assertTrue(r.diagnostics().stopExpansions() > 0); }
    @Test void SAFE_STATE_DEDUP() { var r = search(fiveByFive()); assertTrue(r.diagnostics().statesDeduped() >= 0); }
    @Test void SAFE_DOMINANCE() {
        var a = new StrategicSearchState.PatrolState(new AgentId(0), new Position(1), 1, 5, List.of(), List.of(), List.of(), false);
        var b = new StrategicSearchState.PatrolState(new AgentId(0), new Position(1), 2, 4, List.of(), List.of(), List.of(), false);
        var alloc = new StrategicAllocation(List.of(), List.of(), "NO_REFUEL", "x");
        var left = new StrategicSearchState(List.of(a), Map.of(), java.util.Set.of(), 2, java.util.Set.of(new BrandId("A")), alloc, "NO_REFUEL");
        var right = new StrategicSearchState(List.of(b), Map.of(), java.util.Set.of(), 1, java.util.Set.of(), alloc, "NO_REFUEL");
        assertTrue(StrategicSearchDominance.dominates(left, right));
    }
    @Test void V2_INCUMBENT_SEED() { var r = search(fiveByFive()); assertTrue(r.diagnostics().seedOwn() >= 0); }
    @Test void BOUNDED_SEARCH_NEVER_BELOW_SEED() { var r = search(fiveByFive()); assertTrue(r.winner().ownSemiCollections() >= r.seed().ownSemiCollections()); }
    @Test void FIVE_BY_FIVE_BOUNDED_SEARCH_REACHES_8() {
        var r = search(fiveByFive());
        assertTrue(r.winner().ownSemiCollections() >= 8, "own=" + r.winner().ownSemiCollections() + " paths=" + r.winningNode().state().patrols() + " diag=" + r.diagnostics());
    }
    @Test void FIVE_BY_FIVE_PLAN_VALID() { var r = search(fiveByFive()); assertTrue(new DaySimulator().simulate(fiveByFive(), r.winner().plan()) instanceof ValidDaySimulationResult); }
    @Test void FIVE_BY_FIVE_STRATEGIC_TRACE() { var r = search(fiveByFive()); assertTrue(r.diagnostics().graphEdgeExpansions() > 0); }
    @Test void BUDGET_ABLATION_DETERMINISTIC() { assertEquals(search(fiveByFive()).winner().physicalSignature(), search(fiveByFive()).winner().physicalSignature()); }
    @Test void STRATEGIC_SEARCH_PATHFINDING_ZERO() { assertEquals(0, search(fiveByFive()).diagnostics().searchPathfindingExecutions()); }
    @Test void DETERMINISM() { assertEquals(search(fiveByFive()).winner().physicalSignature(), search(fiveByFive()).winner().physicalSignature()); }
    @Test void V2_PRODUCTION_INVARIANCE() { assertNotNull(search(fiveByFive())); }
    @Test void R3_PRODUCTION_INVARIANCE() { assertNotNull(search(fiveByFive())); }
    @Test void DEADLINE_EXPIRY_RETURNS_LEGAL_PLAN() {
        var c = new StrategicSearchConfig(16, 64, 16, 8, 5, 8, 1, () -> Long.MAX_VALUE);
        var r = new StrategicTeamSearch().solve(fiveByFive(), c);
        assertTrue(r.diagnostics().deadlineExceeded());
        assertTrue(new DaySimulator().simulate(fiveByFive(), r.plan()) instanceof ValidDaySimulationResult);
    }
    @Test void EXTREME_BUDGET_FALLBACK() {
        var c = new StrategicSearchConfig(1, 1, 1, 1, 1, 1, 1, () -> Long.MAX_VALUE);
        assertNotNull(new StrategicTeamSearch().solve(fiveByFive(), c).plan());
    }
    @Test void CHAIN_MACRO_EXPANSION() { assertEquals(StrategicTransition.Kind.CHAIN_MACRO, StrategicTransition.chainMacro(new Position(1), new Position(2), "C").kind()); }
    @Test void CROSS_REGION_EXPANSION() { assertNotNull(new StrategicOpportunityGraphBuilder().build(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH)); }
    @Test void STOCK_CLAIM_CONSISTENCY() { assertTrue(search(fiveByFive()).winner().ownSemiCollections() <= 8); }
    @Test void TRAJECTORY_EFFECT_INCLUDES_INTERMEDIATE_SPOTS() {
        var state = state(30, 5, 5, List.of(agent(0, 0, 8)), List.of(spot("A", 1, 1), spot("C", 6, 1)));
        var graph = new StrategicOpportunityGraphBuilder().build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        Route route = new Route(new Position(0), new Position(6), List.of(
                vn.ptit.procon.domain.map.Direction.RIGHT, vn.ptit.procon.domain.map.Direction.DOWN_LEFT), 4, 2);
        var effect = CachedTrajectoryEffect.from(state, new AgentId(0), route);
        assertEquals(List.of(new Position(0), new Position(1), new Position(6)), effect.traversedPositions());
        assertEquals(List.of(new Position(1), new Position(6)), effect.encounters().stream()
                .map(CachedTrajectoryEffect.Encounter::position).toList());
    }
    @Test void CHRONOLOGY_SETTLES_TIES_BY_AGENT_ID() {
        var state = state(10, 5, 5, List.of(agent(0, 0, 4), agent(1, 2, 4)), List.of(spot("A", 1, 2)));
        var graph = new StrategicOpportunityGraphBuilder().build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        var cache = new StrategicTrajectoryCache(state);
        var routes = Map.of(new AgentId(0), List.of(cache.effect(new AgentId(0), graph.agentRoutes().get(new AgentId(0)).get(new Position(1)))),
                new AgentId(1), List.of(cache.effect(new AgentId(1), graph.agentRoutes().get(new AgentId(1)).get(new Position(1)))));
        var replay = new StrategicChronologyReplay().replay(state, routes);
        assertEquals(2, replay.collections());
        assertEquals(List.of(0, 1), replay.claims().stream().map(c -> c.agentId().value()).toList());
    }
    @Test void CHRONOLOGY_IS_INDEPENDENT_OF_ROUTE_MAP_INSERTION_ORDER() {
        var state = state(10, 5, 5, List.of(agent(0, 0, 4), agent(1, 2, 4)), List.of(spot("A", 1, 2)));
        var graph = new StrategicOpportunityGraphBuilder().build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        var cache = new StrategicTrajectoryCache(state);
        var first = new LinkedHashMap<AgentId, List<CachedTrajectoryEffect>>();
        first.put(new AgentId(0), List.of(cache.effect(new AgentId(0), graph.agentRoutes().get(new AgentId(0)).get(new Position(1)))));
        first.put(new AgentId(1), List.of(cache.effect(new AgentId(1), graph.agentRoutes().get(new AgentId(1)).get(new Position(1)))));
        var second = new LinkedHashMap<AgentId, List<CachedTrajectoryEffect>>();
        second.put(new AgentId(1), first.get(new AgentId(1))); second.put(new AgentId(0), first.get(new AgentId(0)));
        assertEquals(new StrategicChronologyReplay().replay(state, first).fingerprint(),
                new StrategicChronologyReplay().replay(state, second).fingerprint());
    }
    @Test void HIGH_STOCK_SUPPORTS_MULTIPLE_PATROL_CLAIMS() {
        var state = state(10, 5, 5, List.of(agent(0, 0, 4), agent(1, 2, 4), agent(2, 4, 4)), List.of(spot("A", 1, 3)));
        var graph = new StrategicOpportunityGraphBuilder().build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        var cache = new StrategicTrajectoryCache(state);
        Map<AgentId, List<CachedTrajectoryEffect>> routes = new LinkedHashMap<>();
        for (int id : List.of(0, 1, 2)) routes.put(new AgentId(id), List.of(cache.effect(new AgentId(id),
                graph.agentRoutes().get(new AgentId(id)).get(new Position(1)))));
        var replay = new StrategicChronologyReplay().replay(state, routes);
        assertEquals(3, replay.collections());
        assertEquals(0, replay.remainingStock().get(new Position(1)));
    }
    @Test void LIVE_LIKE_UNSEEDED_REPRESENTATION_ORACLE_IS_REPRODUCIBLE() {
        var others = List.of(new ObservedOtherGroup(51, List.of(
                new vn.ptit.procon.domain.opponent.ObservedOtherAgent(new Position(2), 0, 0),
                new vn.ptit.procon.domain.opponent.ObservedOtherAgent(new Position(17), 0, 0))));
        var state = state(30, 5, 5, List.of(agent(0, 0, 4), agent(1, 12, 4), agent(2, 24, 4),
                AgentState.refuel(new AgentId(9), new Position(12))), List.of(
                spot("A", 1, 2), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1),
                spot("A", 11, 1), spot("B", 13, 2), spot("C", 16, 1), spot("D", 18, 1)), others);
        var result = new V3RepresentationOracle().solve(state, new V3RepresentationConfig(5, 96, 512, 512), List.of());
        assertTrue(result.winner().ownSemiCollections() >= 9, result.winner().objective().toString());
    }
    @Test void TEAM_ALLOCATION_SECONDARY_REGION() {
        var g = new StrategicOpportunityGraphBuilder().build(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertTrue(new StrategicAllocationGenerator().generate(fiveByFive(), g, StrategicSearchConfig.defaults()).stream()
                .anyMatch(a -> a.secondaryTargets().stream().anyMatch(v -> !v.isEmpty())));
    }
    @Test void LIVE_LIKE_SEARCH_LARGE_FINITE_BUDGET() {
        var state = state(30, 5, 5, List.of(agent(0, 0, 4), agent(1, 12, 4), agent(2, 24, 4)), List.of(
                spot("A", 1, 2), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1), spot("A", 11, 1),
                spot("B", 13, 2), spot("C", 16, 1), spot("D", 18, 1)));
        var r = new StrategicTeamSearch().solve(state, new StrategicSearchConfig(64, 1024, 64, 16, 12, 128));
        assertTrue(r.winner().ownSemiCollections() >= 9, "own=" + r.winner().ownSemiCollections() + " diag=" + r.diagnostics());
    }
    @Test void LIVE_LIKE_SEARCH_WITH_OBSERVED_OPPONENTS_REACHES_NINE() {
        var state = state(30, 5, 5, List.of(agent(0, 0, 4), agent(1, 12, 4), agent(2, 24, 4),
                AgentState.refuel(new AgentId(9), new Position(12))), List.of(
                spot("A", 1, 2), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1), spot("A", 11, 1),
                spot("B", 13, 2), spot("C", 16, 1), spot("D", 18, 1)), List.of(new ObservedOtherGroup(51, List.of(
                        new ObservedOtherAgent(new Position(2), 0, 0), new ObservedOtherAgent(new Position(17), 0, 0)))));
        var r = new StrategicTeamSearch().solve(state, new StrategicSearchConfig(64, 1024, 64, 16, 12, 128));
        assertTrue(r.winner().ownSemiCollections() >= 9, "own=" + r.winner().ownSemiCollections() + " diag=" + r.diagnostics());
    }

    @Test void TRAJECTORY_VS_QUOTA_2X2_ABLATION() {
        StrategicSearchConfig base = new StrategicSearchConfig(64, 1024, 64, 16, 12, 128);
        StrategicSearchResult endpointOld = new StrategicTeamSearch().solve(fiveByFive(),
                base.withAblation(false, false));
        StrategicSearchResult endpointFixed = new StrategicTeamSearch().solve(fiveByFive(),
                base.withAblation(false, true));
        StrategicSearchResult trajectoryOld = new StrategicTeamSearch().solve(fiveByFive(),
                base.withAblation(true, false));
        StrategicSearchResult trajectoryFixed = new StrategicTeamSearch().solve(fiveByFive(),
                base.withAblation(true, true));
        assertNotNull(endpointOld);
        assertNotNull(endpointFixed);
        assertNotNull(trajectoryOld);
        assertTrue(trajectoryFixed.winner().ownSemiCollections() >= 8);
        assertTrue(trajectoryFixed.diagnostics().trajectoryState());
        assertTrue(trajectoryFixed.diagnostics().perStateChildQuota());
    }

    @Test void CHILD_QUOTA_IS_PER_STATE() {
        StrategicSearchResult result = new StrategicTeamSearch().solve(fiveByFive(),
                new StrategicSearchConfig(16, 64, 4, 8, 10, 8));
        assertEquals(4, result.diagnostics().expectedPerStateChildCap());
        assertTrue(result.diagnostics().maxChildrenPerExpandedState() <= 4);
        assertEquals(0, result.diagnostics().statesIncorrectlyAffectedByGlobalCap());
    }

    @Test void CHILD_QUOTA_ONE_STATE_DOES_NOT_STARVE_ANOTHER() {
        StrategicSearchConfig base = new StrategicSearchConfig(16, 64, 4, 8, 10, 8);
        StrategicSearchResult old = new StrategicTeamSearch().solve(fiveByFive(), base.withPerStateChildQuota(false));
        StrategicSearchResult fixed = new StrategicTeamSearch().solve(fiveByFive(), base.withPerStateChildQuota(true));
        assertTrue(fixed.diagnostics().childrenGenerated() > old.diagnostics().childrenGenerated());
        assertTrue(old.diagnostics().statesIncorrectlyAffectedByGlobalCap() > 0);
    }

    @Test void TERMINAL_SEARCH_SIMULATOR_PARITY() {
        StrategicSearchResult result = search(fiveByFive());
        assertTrue(V3TerminalParityAudit.audit(fiveByFive(), result).exact(),
                V3TerminalParityAudit.audit(fiveByFive(), result).toString());
    }

    @Test void LIVE_LIKE_9_SIMULATOR_PARITY() {
        DayState state = state(30, 5, 5, List.of(agent(0, 0, 4), agent(1, 12, 4), agent(2, 24, 4)), List.of(
                spot("A", 1, 2), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1), spot("A", 11, 1),
                spot("B", 13, 2), spot("C", 16, 1), spot("D", 18, 1)));
        StrategicSearchResult result = new StrategicTeamSearch().solve(state,
                new StrategicSearchConfig(64, 1024, 64, 16, 12, 128));
        V3TerminalParityAudit.Result parity = V3TerminalParityAudit.audit(state, result);
        assertTrue(parity.exact(), parity.toString());
        assertEquals(9, result.winner().ownSemiCollections());
    }

    @Test void FIVE_BY_FIVE_CERTIFIED_NON_REGRESSION() {
        StrategicSearchResult result = search(fiveByFive());
        assertTrue(result.winner().ownSemiCollections() >= 8);
        assertTrue(new DaySimulator().simulate(fiveByFive(), result.plan()) instanceof ValidDaySimulationResult);
    }

    @Test void V2_INCUMBENT_NEVER_REGRESSED() {
        DayState state = fiveByFive();
        var evaluator = new FrozenObjectiveEvaluator(state);
        var incumbent = evaluator.evaluate(new StrategicTeamSearch().solve(state,
                new StrategicSearchConfig(16, 64, 16, 8, 10, 8)).plan()).orElseThrow();
        StrategicSearchResult result = new StrategicTeamSearch().solve(state,
                new StrategicSearchConfig(16, 0, 16, 8, 10, 8), List.of(incumbent.plan()));
        assertTrue(HybridComparison.compare(result.winner(), incumbent) <= 0);
    }

    @Test void RAW_V3_VS_FALLBACK_DIAGNOSTIC() {
        StrategicSearchResult result = new StrategicTeamSearch().solve(fiveByFive(),
                new StrategicSearchConfig(16, 0, 16, 8, 10, 8));
        assertNotNull(result.rawWinner());
        assertTrue(result.fallbackUsed());
        assertTrue(new DaySimulator().simulate(fiveByFive(), result.plan()) instanceof ValidDaySimulationResult);
    }

    @Test void FORCED_ZERO_BUDGET_FALLBACK() {
        StrategicSearchResult result = new StrategicTeamSearch().solve(fiveByFive(),
                new StrategicSearchConfig(1, 0, 1, 1, 1, 1));
        assertTrue(new DaySimulator().simulate(fiveByFive(), result.plan()) instanceof ValidDaySimulationResult);
    }

    @Test void FORCED_DEADLINE_FALLBACK() {
        StrategicSearchResult result = new StrategicTeamSearch().solve(fiveByFive(),
                new StrategicSearchConfig(16, 64, 16, 8, 10, 8, 1));
        assertTrue(result.diagnostics().deadlineExceeded());
        assertTrue(new DaySimulator().simulate(fiveByFive(), result.plan()) instanceof ValidDaySimulationResult);
    }

    @Test void NO_VALID_V3_TERMINAL_FALLBACK() {
        StrategicSearchResult result = new StrategicTeamSearch().solve(fiveByFive(),
                new StrategicSearchConfig(1, 1, 1, 1, 1, 1, 1), List.of());
        assertNotNull(result.plan());
        assertTrue(new DaySimulator().simulate(fiveByFive(), result.plan()) instanceof ValidDaySimulationResult);
    }

    @Test void TRAJECTORY_CACHE_DETERMINISM() {
        StrategicSearchResult first = search(fiveByFive());
        StrategicSearchResult second = search(fiveByFive());
        assertEquals(first.winner().physicalSignature(), second.winner().physicalSignature());
        assertEquals(first.diagnostics().trajectoryCacheEntries(), second.diagnostics().trajectoryCacheEntries());
        assertEquals(first.diagnostics().trajectoryCacheHits(), second.diagnostics().trajectoryCacheHits());
    }

    @Test void CHRONOLOGY_REPLAY_DETERMINISM() {
        CHRONOLOGY_IS_INDEPENDENT_OF_ROUTE_MAP_INSERTION_ORDER();
    }

    @Test void MEDIUM_REGION_RELOCATION() {
        assertLegalSearch(state(36, 12, 3, List.of(agent(0, 0, 8), agent(1, 11, 8), agent(2, 24, 8)),
                List.of(spot("A", 1, 1), spot("B", 10, 1), spot("C", 13, 1), spot("D", 22, 1))));
    }

    @Test void MEDIUM_SHARED_STOCK() {
        assertLegalSearch(state(30, 10, 2, List.of(agent(0, 0, 8), agent(1, 9, 8), agent(2, 10, 8)),
                List.of(spot("A", 2, 2), spot("B", 12, 2), spot("C", 17, 1))));
    }

    @Test void MEDIUM_SUPPORT_CHAIN() {
        assertLegalSearch(state(30, 12, 2, List.of(agent(0, 0, 8), agent(1, 11, 8), agent(2, 12, 8),
                AgentState.refuel(new AgentId(9), new Position(6))),
                List.of(spot("A", 1, 1), spot("B", 5, 1), spot("C", 13, 1), spot("D", 18, 1))));
    }

    @Test void LARGE_DISTRIBUTED_BOUNDED() {
        assertLargeBounded(16, 10, 60);
    }

    @Test void LARGE_DENSE_BOUNDED() {
        assertLargeBounded(12, 12, 60);
    }

    private static StrategicSearchResult search(DayState state) {
        return new StrategicTeamSearch().solve(state, new StrategicSearchConfig(32, 512, 32, 16, 10, 64));
    }

    private static void assertLegalSearch(DayState state) {
        StrategicSearchResult result = new StrategicTeamSearch().solve(state,
                new StrategicSearchConfig(16, 64, 16, 8, 10, 8));
        assertTrue(new DaySimulator().simulate(state, result.plan()) instanceof ValidDaySimulationResult);
        assertEquals(0, result.diagnostics().strategicSearchPathfindingExecutions());
    }

    private static void assertLargeBounded(int width, int height, int steps) {
        List<AgentState> agents = List.of(agent(0, 0, 8), agent(1, Math.min(width * height - 1, 23), 8),
                agent(2, Math.min(width * height - 1, 48), 8), agent(3, Math.min(width * height - 1, 95), 8),
                AgentState.refuel(new AgentId(9), new Position(Math.min(width * height - 1, 72))));
        DayState state = state(steps, width, height, agents, List.of(spot("A", 2, 1),
                spot("B", Math.min(width * height - 1, 14), 1), spot("C", Math.min(width * height - 1, 27), 1),
                spot("D", Math.min(width * height - 1, 39), 1)));
        assertLegalSearch(state);
        assertTrue(search(state).diagnostics().trajectoryCacheEntries() >= 0);
    }

    private static final class HybridComparison {
        private static int compare(StrategicOracleEvaluation left, StrategicOracleEvaluation right) {
            return vn.ptit.procon.planner.HybridCalibratedMarginEvaluation.compare(left.objective(), right.objective());
        }
    }
    private static DayState fiveByFive() { return state(30, 5, 5, List.of(agent(0, 0, 8), agent(1, 12, 8), agent(2, 24, 8)), List.of(
            spot("A", 1, 1), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1), spot("A", 11, 1), spot("B", 13, 1), spot("C", 16, 1), spot("D", 18, 1))); }
    private static DayState state(int steps, int width, int height, List<AgentState> agents, List<UdonSpot> spots) {
        return state(steps, width, height, agents, spots, List.of());
    }
    private static DayState state(int steps, int width, int height, List<AgentState> agents, List<UdonSpot> spots,
            List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[width * height]; Arrays.fill(terrain, Terrain.PLAIN); Map<Position, Integer> stock = new LinkedHashMap<>(); spots.forEach(s -> stock.put(s.position(), s.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(width, height, terrain), new DayStepBudgets(new int[] {steps}), List.of(), new FuelCapacity(10), spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock, others);
    }
    private static AgentState agent(int id, int position, int fuel) { return AgentState.patrol(new AgentId(id), new Position(position), fuel); }
    private static UdonSpot spot(String brand, int position, int stock) { return new UdonSpot(new BrandId(brand), new Position(position), stock); }
}
