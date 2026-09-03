package vn.ptit.procon.planner.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class StrategicOracleTest {
    @Test
    void opportunityGraphIsDeterministic() {
        StrategicOpportunityGraphBuilder builder = new StrategicOpportunityGraphBuilder();
        assertEquals(builder.build(state()), builder.build(state()));
    }

    @Test
    void regionsAreSoftGroupsAndAllNodesRemainRepresentable() {
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder().build(state());
        assertTrue(graph.regions().size() >= 1);
        assertEquals(graph.opportunities().size(), graph.regions().stream().mapToInt(r -> r.members().size()).sum());
    }

    @Test
    void chainGenerationHasFiniteDeterministicDominance() {
        StrategicOracleResult result = new StrategicOracle().solve(state(), new V3Phase0Config(8, 3, 8, 1, 64, 32));
        assertTrue(result.diagnostics().generatedChains() >= result.diagnostics().retainedChains());
        assertEquals(0, result.diagnostics().oracleSearchPathfindingExecutions());
        assertTrue(result.diagnostics().coverageRatio() > 0);
    }

    @Test
    void oracleMaterializesOnlyLegalPlans() {
        StrategicOracleResult result = new StrategicOracle().solve(state());
        assertTrue(new DaySimulator().simulate(state(), result.winner().plan()) instanceof ValidDaySimulationResult);
        assertTrue(result.diagnostics().allocationsMaterialized() <= 128);
    }

    @Test
    void allocationDoesNotUseUnjustifiedHardOneRegionRule() {
        StrategicOracleResult result = new StrategicOracle().solve(state());
        assertTrue(result.allocations().stream().anyMatch(value -> value.coverageOverlap() > 0)
                || result.graph().regions().size() > 1);
    }

    @Test
    void highStockCanSupportSharedOpportunity() {
        DayState highStock = state(List.of(spot("A", 3, 2), spot("B", 6, 2)));
        StrategicOracleResult result = new StrategicOracle().solve(highStock);
        assertTrue(result.graph().opportunities().stream().anyMatch(value -> value.currentStock() == 2));
    }

    @Test
    void noRefuelPlanIsAlwaysAValidSeed() {
        StrategicOracleResult result = new StrategicOracle().solve(state());
        assertTrue(result.winner().plan() != null);
        assertEquals(0, result.diagnostics().materializationPathfindingExecutions());
    }

    @Test
    void existingRefuelPlanCanBeSeededWithoutChangingOracleSearch() {
        var support = new JointTeamBeamR3Planner().planWithStats(state()).plan();
        StrategicOracleResult result = new StrategicOracle().solve(state(), V3Phase0Config.defaults(), List.of(support));
        assertTrue(result.winner().plan() != null);
        assertTrue(result.diagnostics().oracleSearchPathfindingExecutions() == 0);
    }

    @Test
    void capsAreVisible() {
        V3Phase0Config config = new V3Phase0Config(4, 4, 2, 0, 2, 1);
        StrategicOracleResult result = new StrategicOracle().solve(state(), config);
        assertTrue(result.diagnostics().allocationsConsidered() <= 2);
        assertTrue(result.diagnostics().allocationsMaterialized() <= 1);
        assertTrue(result.diagnostics().oracleCapReached());
    }

    @Test
    void repeatedRunsHaveTheSameWinner() {
        StrategicOracleResult first = new StrategicOracle().solve(state());
        StrategicOracleResult second = new StrategicOracle().solve(state());
        assertEquals(first.winner().physicalSignature(), second.winner().physicalSignature());
        assertEquals(first.chains(), second.chains());
    }

    @Test
    void v2PlannerRemainsSeparate() {
        var result = new JointTeamBeamR3Planner().planWithStats(state());
        assertTrue(result.plan() != null);
        assertEquals(0, result.stats().beamSearchPathfindingExecutions());
    }

    private static DayState state() { return state(List.of(spot("A", 1, 1), spot("B", 4, 1), spot("C", 7, 1))); }

    private static DayState state(List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[12]; Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>(); spots.forEach(value -> stock.put(value.position(), value.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(12, 1, terrain), new DayStepBudgets(new int[] {12}),
                List.of(), new FuelCapacity(10), spots);
        return new DayState(match, new DayIndex(0), List.of(AgentState.patrol(new AgentId(0), new Position(0), 6),
                AgentState.patrol(new AgentId(1), new Position(8), 6), AgentState.refuel(new AgentId(9), new Position(3))),
                Map.of(), stock, List.of());
    }

    private static UdonSpot spot(String brand, int position, int stock) { return new UdonSpot(new BrandId(brand), new Position(position), stock); }
}
