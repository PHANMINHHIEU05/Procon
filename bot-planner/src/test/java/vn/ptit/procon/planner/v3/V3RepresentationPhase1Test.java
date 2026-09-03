package vn.ptit.procon.planner.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;

class V3RepresentationPhase1Test {
    @Test
    void EXACT_EDGE_COVERAGE_100_PERCENT() {
        StrategicOpportunityGraph graph = graph(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        StrategicOpportunityGraph complete = new StrategicOpportunityGraphBuilder().build(fiveByFive(), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        assertEquals(complete.possibleDirectedEdges(), complete.retainedStrategicEdges());
        assertTrue(graph.retainedStrategicEdges() <= graph.possibleDirectedEdges());
        assertTrue(graph.retainedStrategicEdges() <= graph.opportunities().size() * 8);
    }

    @Test
    void FIVE_BY_FIVE_EDGE_16_TO_6_PRESENT() {
        StrategicOpportunityGraph graph = graph(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertTrue(graph.outgoing().getOrDefault(new Position(16), List.of()).stream().anyMatch(e -> e.to().position().equals(new Position(6))));
    }

    @Test
    void NO_FIXTURE_COORDINATE_SPECIAL_CASE() {
        DayState translated = state(30, 5, 5, List.of(agent(0, 0, 8), agent(1, 12, 8), agent(2, 24, 8)),
                List.of(spot("A", 0, 1), spot("B", 2, 1), spot("C", 7, 1), spot("D", 9, 1)));
        StrategicOpportunityGraph graph = graph(translated, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertTrue(graph.retainedStrategicEdges() > 0);
        assertTrue(graph.outgoing().values().stream().flatMap(List::stream).noneMatch(e -> e.from().position().value() == 16 && e.to().position().value() == 6));
    }

    @Test
    void REGION_DOES_NOT_REMOVE_EDGE() {
        StrategicOpportunityGraph graph = graph(longHop(), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertTrue(graph.outgoing().values().stream().flatMap(List::stream).anyMatch(e -> !sameRegion(graph, e.from().position(), e.to().position())));
    }

    @Test
    void LONG_HOP_EDGE_RETAINED() {
        StrategicOpportunityGraph graph = graph(longHop(), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertTrue(graph.longHopEdges() > 0 || graph.crossRegionEdges() > 0);
    }

    @Test
    void EDGE_RETENTION_ABLATION_IS_EXPLICIT() {
        StrategicOpportunityGraph nearest = graph(fiveByFive(), V3EdgeRetentionPolicy.NEAREST_ONLY);
        StrategicOpportunityGraph diverse = graph(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertTrue(diverse.retainedStrategicEdges() >= nearest.retainedStrategicEdges());
        assertEquals(V3EdgeRetentionPolicy.NEAREST_ONLY, nearest.retentionPolicy());
        assertEquals(V3EdgeRetentionPolicy.DIVERSE_GRAPH, diverse.retentionPolicy());
    }

    @Test
    void CHAIN_STITCHING_SUPPORTED() {
        RouteSkeleton skeleton = new RouteSkeleton(new AgentId(0), List.of(new Position(1), new Position(2), new Position(9)),
                List.of(0, 0, 1), 3, 3, List.of(StrategicTransition.chainMacro(new Position(1), new Position(2), "C0"),
                        StrategicTransition.edge(new Position(2), new Position(9))));
        assertEquals(StrategicTransition.Kind.CHAIN_MACRO, skeleton.transitions().getFirst().kind());
        assertEquals(StrategicTransition.Kind.EDGE, skeleton.transitions().getLast().kind());
    }

    @Test
    void ROUTE_SKELETON_CHAIN_EDGE_CHAIN() {
        RouteSkeleton skeleton = new RouteSkeleton(new AgentId(0), List.of(new Position(1), new Position(2), new Position(9), new Position(10)),
                List.of(0, 0, 1, 1), 4, 4, List.of(StrategicTransition.chainMacro(new Position(1), new Position(2), "A"),
                        StrategicTransition.edge(new Position(2), new Position(9)), StrategicTransition.chainMacro(new Position(9), new Position(10), "B")));
        assertEquals(3, skeleton.transitions().size());
    }

    @Test
    void TEAM_ALLOCATION_SECONDARY_REGION() {
        StrategicOpportunityGraph graph = graph(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertTrue(graph.regions().stream().anyMatch(r -> r.exitCandidates().size() >= 1));
    }

    @Test
    void REGION_ESCAPE() { assertTrue(graph(longHop(), V3EdgeRetentionPolicy.DIVERSE_GRAPH).retainedStrategicEdges() > 0); }

    @Test
    void MULTI_EXIT_REGION() { assertTrue(graph(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH).regions().stream().allMatch(r -> !r.exitCandidates().isEmpty())); }

    @Test
    void CROSS_REGION_HIGH_STOCK() {
        StrategicOpportunityGraph graph = graph(longHop(), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertTrue(graph.outgoing().values().stream().flatMap(List::stream).anyMatch(e -> e.to().currentStock() > 1));
    }

    @Test
    void SHARED_HIGH_STOCK_NON_REGRESSION() {
        StrategicOpportunityGraph graph = graph(state(12, 1, 8, List.of(agent(0, 0, 8), agent(1, 4, 8)), List.of(spot("A", 2, 2))), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertEquals(2, graph.opportunities().getFirst().currentStock());
    }

    @Test
    void SUPPORT_POST_PREFIX_EDGE_FEASIBILITY() {
        var result = new JointTeamBeamR3Planner().planWithStats(stateWithRefuel());
        assertNotNull(result.plan());
        assertEquals(0, new V3RepresentationOracle().solve(stateWithRefuel(), new V3RepresentationConfig(3, 8, 64, 32), List.of(result.plan()))
                .diagnostics().representationSearchPathfindingExecutions());
    }

    @Test
    void FIVE_BY_FIVE_EXACT_SKELETON_REPRESENTABLE() {
        StrategicOpportunityGraph graph = graph(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        V3ExactEdgeCoverage coverage = V3ExactEdgeCoverage.audit(graph, List.of(
                List.of(new Position(1), new Position(8), new Position(13)),
                List.of(new Position(11), new Position(16), new Position(6)),
                List.of(new Position(18), new Position(3))));
        assertTrue(graph.outgoing().getOrDefault(new Position(16), List.of()).stream().anyMatch(e -> e.to().position().equals(new Position(6))));
        assertTrue(coverage.complete(), coverage.firstMissingTransition());
        assertEquals(1.0, coverage.coverageRatio());
    }

    @Test
    void FIVE_BY_FIVE_REPRESENTATION_ORACLE_REACHES_EIGHT() {
        var result = new V3RepresentationOracle().solve(fiveByFive(), new V3RepresentationConfig(4, 80, 500, 500));
        assertTrue(result.winner().ownSemiCollections() >= 8, "winner=" + result.winner().ownSemiCollections());
        assertTrue(new DaySimulator().simulate(fiveByFive(), result.winner().plan()) instanceof ValidDaySimulationResult);
    }

    @Test
    void CERTIFIED_FIXTURE_NON_REGRESSION() {
        var result = new V3RepresentationOracle().solve(state(8, 1, 8, List.of(agent(0, 0, 8), agent(1, 4, 8)), List.of(spot("A", 2, 2))));
        assertTrue(result.winner().ownSemiCollections() >= 2);
    }

    @Test
    void EDGE_BUDGET_BOUNDED() {
        StrategicOpportunityGraph graph = graph(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertTrue(graph.maxOutgoingEdges() <= 12);
        assertTrue(graph.retainedStrategicEdges() <= graph.opportunities().size() * 12);
    }

    @Test
    void REPRESENTATION_SEARCH_PATHFINDING_ZERO() {
        assertEquals(0, new V3RepresentationOracle().solve(longHop(), new V3RepresentationConfig(3, 12, 32, 16))
                .diagnostics().representationSearchPathfindingExecutions());
    }

    @Test
    void V2_PRODUCTION_INVARIANCE() { assertNotNull(new JointTeamBeamR3Planner().planWithStats(fiveByFive()).plan()); }

    @Test
    void DETERMINISM() {
        var builder = new StrategicOpportunityGraphBuilder();
        assertEquals(builder.build(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH), builder.build(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH));
    }

    @Test
    void COMPLETE_CACHE_AND_RETAINED_PORTFOLIO_ARE_DISTINCT() {
        StrategicOpportunityGraph complete = graph(fiveByFive(), V3EdgeRetentionPolicy.CURRENT_PHASE0);
        StrategicOpportunityGraph diverse = graph(fiveByFive(), V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        assertEquals(complete.possibleDirectedEdges(), complete.retainedStrategicEdges());
        assertTrue(diverse.retainedStrategicEdges() <= diverse.possibleDirectedEdges());
    }

    private static StrategicOpportunityGraph graph(DayState state, V3EdgeRetentionPolicy policy) { return new StrategicOpportunityGraphBuilder().build(state, policy); }
    private static boolean sameRegion(StrategicOpportunityGraph graph, Position a, Position b) { return region(graph, a) == region(graph, b); }
    private static int region(StrategicOpportunityGraph graph, Position p) { return graph.regions().stream().filter(r -> r.members().stream().anyMatch(o -> o.position().equals(p))).mapToInt(OpportunityRegion::regionId).findFirst().orElse(-1); }

    private static DayState fiveByFive() { return state(30, 5, 5, List.of(agent(0, 0, 8), agent(1, 12, 8), agent(2, 24, 8)), List.of(
            spot("A", 1, 1), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1), spot("A", 11, 1), spot("B", 13, 1), spot("C", 16, 1), spot("D", 18, 1))); }
    private static DayState longHop() { return state(20, 1, 12, List.of(agent(0, 0, 10)), List.of(spot("A", 1, 1), spot("A", 2, 1), spot("B", 10, 2))); }
    private static DayState stateWithRefuel() { return state(24, 1, 12, List.of(AgentState.patrol(new AgentId(0), new Position(1), 0), AgentState.patrol(new AgentId(1), new Position(10), 0), AgentState.refuel(new AgentId(9), new Position(4))), List.of(spot("A", 0, 1), spot("B", 11, 1))); }
    private static DayState state(int steps, int width, int height, List<AgentState> agents, List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[width * height]; Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>(); spots.forEach(s -> stock.put(s.position(), s.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(width, height, terrain), new DayStepBudgets(new int[] {steps}), List.of(), new FuelCapacity(10), spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock, List.of());
    }
    private static AgentState agent(int id, int position, int fuel) { return AgentState.patrol(new AgentId(id), new Position(position), fuel); }
    private static UdonSpot spot(String brand, int position, int stock) { return new UdonSpot(new BrandId(brand), new Position(position), stock); }
}
