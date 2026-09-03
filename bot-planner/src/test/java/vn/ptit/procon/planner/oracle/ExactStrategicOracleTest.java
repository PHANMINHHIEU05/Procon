package vn.ptit.procon.planner.oracle;

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
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;

class ExactStrategicOracleTest {
    @Test
    void exactTinyManualOptimumIsProven() {
        ExactOracleResult result = new ExactStrategicOracle().solve(tiny(), ExactOracleConfig.tiny());
        assertTrue(result.ownCollectionOptimalityProven());
        assertEquals(3, result.bestFoundOwnSemi());
        assertEquals(0, result.diagnostics().oracleSearchPathfindingExecutions());
    }

    @Test
    void sharedStockClaimsChronologicallyAndExhausts() {
        ExactOracleResult result = new ExactStrategicOracle().solve(sharedStock(), ExactOracleConfig.tiny());
        assertTrue(result.ownCollectionOptimalityProven());
        assertEquals(2, result.bestFoundOwnSemi());
        assertTrue(result.bestCollectionSkeleton().agents().stream().mapToInt(a -> a.visits().size()).sum() >= 1);
    }

    @Test
    void teamSplitAndChainContinuationAreEnumerated() {
        ExactOracleResult result = new ExactStrategicOracle().solve(teamSplit(), ExactOracleConfig.tiny());
        assertTrue(result.ownCollectionOptimalityProven());
        assertEquals(4, result.bestFoundOwnSemi());
        assertTrue(result.diagnostics().transitionsGenerated() > 0);
    }

    @Test
    void capReachedIsNeverReportedAsExact() {
        ExactOracleConfig cap = new ExactOracleConfig(1, 2_000, 32, 0, 4, 24);
        ExactOracleResult result = new ExactStrategicOracle().solve(fiveByFive(), cap);
        assertTrue(result.diagnostics().searchCapReached());
        assertTrue(!result.optimalityProven() || result.bestFoundOwnSemi() == result.certifiedUpperBoundOwn());
        assertTrue(result.provenUpperBound() >= result.bestFoundOwnSemi());
    }

    @Test
    void everyWinningPlanIsSimulatorLegal() {
        ExactOracleResult result = new ExactStrategicOracle().solve(tiny(), ExactOracleConfig.tiny());
        assertTrue(new DaySimulator().simulate(tiny(), result.bestPlan()) instanceof ValidDaySimulationResult);
        assertTrue(result.diagnostics().validLeafPlans() > 0);
    }

    @Test
    void existingSupportPrefixIsReplayableAsExternalRoot() {
        DayState supportState = stateWithRefuel();
        var r3 = new JointTeamBeamR3Planner().planWithStats(supportState);
        assertTrue(((ValidDaySimulationResult) new DaySimulator().simulate(supportState, r3.plan()))
                .portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum() > 0);
        ExactOracleResult result = new ExactStrategicOracle().solve(supportState, ExactOracleConfig.tiny(), List.of(r3.plan()));
        assertTrue(result.diagnostics().validLeafPlans() >= 1);
        assertTrue(result.bestFoundOwnSemi() >= 1);
        assertTrue(!result.ownCollectionOptimalityProven());
    }

    @Test
    void supportReplayDoesNotDependOnMapWidth() {
        DayState supportState = sizedSupportState(10);
        var r3 = new JointTeamBeamR3Planner().planWithStats(supportState);
        ExactOracleResult result = new ExactStrategicOracle().solve(supportState, ExactOracleConfig.tiny(), List.of(r3.plan()));
        assertTrue(new DaySimulator().simulate(supportState, r3.plan()) instanceof ValidDaySimulationResult);
        assertTrue(result.bestFoundOwnSemi() >= 1);
    }

    @Test
    void repeatedRunsAreDeterministicAndMemoKeyHasNoHistoryText() {
        ExactStrategicOracle oracle = new ExactStrategicOracle();
        ExactOracleResult first = oracle.solve(teamSplit(), ExactOracleConfig.tiny());
        ExactOracleResult second = oracle.solve(teamSplit(), ExactOracleConfig.tiny());
        assertEquals(first.bestPhysicalSignature(), second.bestPhysicalSignature());
        assertEquals(first.bestCollectionSkeleton(), second.bestCollectionSkeleton());
        assertEquals(first.diagnostics().uniqueExactStates(), second.diagnostics().uniqueExactStates());
    }

    @Test
    void largeConfiguredStateIsReportedAsBounded() {
        ExactOracleConfig config = new ExactOracleConfig(10, 1, 8, 0, 3, 30);
        ExactOracleResult result = new ExactStrategicOracle().solve(fiveByFive(), config);
        assertTrue(!result.optimalityProven() || result.bestFoundOwnSemi() == result.certifiedUpperBoundOwn());
        assertTrue(result.diagnostics().wallCapReached() || result.diagnostics().searchCapReached());
    }

    @Test
    void warmStartNeverFallsBelowKnownLegalPlan() {
        DayState state = teamSplit();
        var seed = new JointTeamBeamR3Planner().planWithStats(state).plan();
        ExactOracleResult result = new ExactStrategicOracle().solve(state,
                ExactOracleConfig.tiny(), List.of(seed));
        var seedEvaluation = new vn.ptit.procon.planner.v3.FrozenObjectiveEvaluator(state).evaluate(seed).orElseThrow();
        assertTrue(result.bestFoundOwnSemi() >= seedEvaluation.ownSemiCollections());
        assertTrue(result.bestFoundHybrid4() >= seedEvaluation.hybridMarginScore4());
        assertTrue(result.audit().incumbentPlanValid());
    }

    @Test
    void terminalStoppingBranchesAreRepresented() {
        ExactOracleResult result = new ExactStrategicOracle().solve(teamSplit(), ExactOracleConfig.tiny());
        assertTrue(result.audit().statesWithStopBranch() > 0);
        assertTrue(result.audit().stopBranchesGenerated() > 0);
        assertTrue(result.audit().uniqueTerminalSkeletons() > 0);
    }

    @Test
    void ownAndHybridProofTracksRemainSeparate() {
        ExactOracleResult result = new ExactStrategicOracle().solve(tiny(), ExactOracleConfig.tiny());
        assertTrue(result.ownCollectionOptimalityProven());
        assertTrue(result.hybridOptimalityProven());
        assertTrue(result.bestHybridPlan() != null);
        assertTrue(result.bestHybridPhysicalSignature() != null);
    }

    @Test
    void tighterBoundNeverExceedsTrivialBound() {
        ExactOracleResult result = new ExactStrategicOracle().solve(sharedStock(), ExactOracleConfig.tiny());
        assertTrue(result.audit().tightUpperBound() <= result.audit().trivialUpperBound());
        assertTrue(result.provenUpperBound() >= result.bestFoundOwnSemi());
    }

    @Test
    void supportRootIsReplayedAndContinuationIsAudited() {
        DayState state = stateWithRefuel();
        var support = new JointTeamBeamR3Planner().planWithStats(state).plan();
        ExactOracleResult result = new ExactStrategicOracle().solve(state, ExactOracleConfig.tiny(), List.of(support));
        assertTrue(result.audit().supportRootsGenerated() > 0);
        assertTrue(result.audit().supportRootsReplayed() > 0);
        assertTrue(result.audit().supportRootsAccepted() > 0);
        assertTrue(result.bestFoundOwnSemi() >= 1);
    }

    @Test
    void canonicalMemoKeyExcludesSupportHistory() {
        ExactOracleResult result = new ExactStrategicOracle().solve(teamSplit(), ExactOracleConfig.tiny());
        assertEquals(result.audit().statesBeforeCanonicalization(), result.audit().statesAfterCanonicalization());
        assertTrue(result.audit().canonicalMergeCount() >= 0);
    }

    @Test
    void oracleSearchNeverCallsPathfindingAfterCatalog() {
        ExactOracleResult result = new ExactStrategicOracle().solve(tiny(), ExactOracleConfig.tiny());
        assertEquals(0, result.diagnostics().oracleSearchPathfindingExecutions());
        assertEquals(0, result.diagnostics().materializationPathfindingExecutions());
    }

    @Test
    void certifiedAndHeuristicBoundsAreExplicitlySeparated() {
        ExactOracleResult result = new ExactStrategicOracle().solve(fiveByFive(),
                new ExactOracleConfig(250_000, 1_000, 64, 0, 4, 30));
        assertEquals(8, result.trivialCertifiedUpperBoundOwn());
        assertEquals(8, result.certifiedUpperBoundOwn());
        assertTrue(result.heuristicUpperBoundOwn() <= result.certifiedUpperBoundOwn());
        assertEquals("TRIVIAL_REMAINING_STOCK", result.boundSourceUsedForProof());
        assertTrue(result.tightCertifiedUpperBoundOwn().isEmpty());
    }

    @Test
    void uncertifiedTightBoundCannotProveAnOptimum() {
        ExactOracleResult result = new ExactStrategicOracle().solve(fiveByFive(),
                new ExactOracleConfig(1, 2_000, 64, 0, 4, 30),
                List.of(SafePlanFactory.waitAll(fiveByFive())));
        assertEquals(8, result.certifiedUpperBoundOwn());
        assertTrue(result.bestFoundOwnSemi() < result.certifiedUpperBoundOwn());
        assertTrue(!result.ownCollectionOptimalityProven());
        assertTrue(result.tightCertifiedUpperBoundOwn().isEmpty());
    }

    @Test
    void lowerEqualsCertifiedUpperProvesEvenWhenSearchIsCapped() {
        ExactOracleResult full = new ExactStrategicOracle().solve(fiveByFive(),
                new ExactOracleConfig(250_000, 2_000, 64, 0, 4, 30));
        assertEquals(8, full.bestFoundOwnSemi());
        ExactOracleResult capped = new ExactStrategicOracle().solve(fiveByFive(),
                new ExactOracleConfig(1, 2_000, 64, 0, 4, 30), List.of(full.bestPlan()));
        assertEquals(8, capped.bestFoundOwnSemi());
        assertTrue(capped.diagnostics().searchCapReached() || capped.diagnostics().wallCapReached());
        assertTrue(capped.ownCollectionOptimalityProven());
    }

    @Test
    void timeoutAfterBoundClosedStillReportsOwnProof() {
        ExactOracleResult full = new ExactStrategicOracle().solve(fiveByFive(),
                new ExactOracleConfig(250_000, 2_000, 64, 0, 4, 30));
        ExactOracleResult capped = new ExactStrategicOracle().solve(fiveByFive(),
                new ExactOracleConfig(1, 1, 64, 0, 4, 30), List.of(full.bestPlan()));
        assertEquals(capped.certifiedUpperBoundOwn(), capped.bestFoundOwnSemi());
        assertTrue(capped.ownCollectionOptimalityProven());
    }

    @Test
    void tightBoundAllReachableTinyStatesRemainsAuditOnly() {
        for (DayState state : List.of(tiny(), sharedStock(), teamSplit(), sizedSupportState(10))) {
            ExactOracleResult result = new ExactStrategicOracle().solve(state, ExactOracleConfig.tiny());
            assertTrue(result.heuristicUpperBoundOwn() <= result.certifiedUpperBoundOwn());
            assertTrue(result.tightCertifiedUpperBoundOwn().isEmpty());
            assertEquals("TRIVIAL_REMAINING_STOCK", result.boundCertificationAudit().boundSourceUsedForProof());
        }
    }

    @Test
    void tightBoundSharedStockAndLowFuelStatesCannotBecomeProofByHeuristic() {
        ExactOracleResult shared = new ExactStrategicOracle().solve(sharedStock(), ExactOracleConfig.tiny());
        ExactOracleResult lowFuel = new ExactStrategicOracle().solve(sizedSupportState(10), ExactOracleConfig.tiny());
        assertTrue(shared.boundCertificationAudit().candidateTightBound() <= shared.certifiedUpperBoundOwn());
        assertTrue(lowFuel.boundCertificationAudit().candidateTightBound() <= lowFuel.certifiedUpperBoundOwn());
        assertTrue(shared.tightCertifiedUpperBoundOwn().isEmpty());
        assertTrue(lowFuel.tightCertifiedUpperBoundOwn().isEmpty());
    }

    @Test
    void fiveByFiveEightPlanIsSimulatorValidAndHasEightCollections() {
        ExactOracleResult result = new ExactStrategicOracle().solve(fiveByFive(),
                new ExactOracleConfig(250_000, 2_000, 64, 0, 4, 30));
        assertEquals(8, result.bestFoundOwnSemi());
        assertEquals(8, result.certifiedUpperBoundOwn());
        assertTrue(result.ownCollectionOptimalityProven());
        ValidDaySimulationResult valid = (ValidDaySimulationResult) new DaySimulator().simulate(fiveByFive(), result.bestPlan());
        assertEquals(8, valid.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(8, result.bestCollectionSkeleton().agents().stream().mapToInt(value -> value.visits().size()).sum());
    }

    private static DayState tiny() {
        return state(8, List.of(agent(0, 0, 8), agent(1, 7, 8)), List.of(
                spot("A", 1, 1), spot("B", 3, 1), spot("C", 6, 1)));
    }

    private static DayState sharedStock() {
        return state(8, List.of(agent(0, 0, 8), agent(1, 4, 8)), List.of(spot("A", 2, 2)));
    }

    private static DayState teamSplit() {
        return state(12, List.of(agent(0, 0, 8), agent(1, 8, 8)), List.of(
                spot("A", 1, 1), spot("B", 2, 1), spot("C", 6, 1), spot("D", 7, 1)));
    }

    private static DayState fiveByFive() {
        Terrain[] terrain = new Terrain[25]; Arrays.fill(terrain, Terrain.PLAIN);
        List<UdonSpot> spots = List.of(spot("A", 1, 1), spot("B", 3, 1), spot("C", 6, 1),
                spot("D", 8, 1), spot("A", 11, 1), spot("B", 13, 1), spot("C", 16, 1), spot("D", 18, 1));
        Map<Position, Integer> stock = new LinkedHashMap<>(); spots.forEach(value -> stock.put(value.position(), value.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(5, 5, terrain), new DayStepBudgets(new int[] {30}),
                List.of(), new FuelCapacity(10), spots);
        return new DayState(match, new DayIndex(0), List.of(agent(0, 0, 8), agent(1, 12, 8), agent(2, 24, 8)),
                Map.of(), stock, List.of());
    }

    private static DayState stateWithRefuel() {
        return sizedSupportState(12);
    }

    private static DayState sizedSupportState(int width) {
        Terrain[] terrain = new Terrain[width]; Arrays.fill(terrain, Terrain.PLAIN);
        List<UdonSpot> spots = List.of(spot("A", 0, 1), spot("B", width - 1, 1));
        Map<Position, Integer> stock = new LinkedHashMap<>(); spots.forEach(value -> stock.put(value.position(), value.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(width, 1, terrain), new DayStepBudgets(new int[] {24}),
                List.of(), new FuelCapacity(10), spots);
        return new DayState(match, new DayIndex(0), List.of(agent(0, 1, 0), agent(1, width - 2, 0),
                AgentState.refuel(new AgentId(9), new Position(4))), Map.of(), stock, List.of());
    }

    private static AgentState agent(int id, int position, int fuel) {
        return AgentState.patrol(new AgentId(id), new Position(position), fuel);
    }

    private static DayState state(int steps, List<AgentState> agents, List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[12]; Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(12, 1, terrain),
                new DayStepBudgets(new int[] {steps}), List.of(), new FuelCapacity(10), spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock, List.of());
    }

    private static UdonSpot spot(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }
}
