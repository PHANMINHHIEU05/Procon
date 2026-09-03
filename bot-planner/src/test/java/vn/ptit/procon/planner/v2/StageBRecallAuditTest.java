package vn.ptit.procon.planner.v2;

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
import vn.ptit.procon.engine.DayState;

class StageBRecallAuditTest {
    private static final AgentId PATROL_0 = new AgentId(0);
    private static final AgentId PATROL_1 = new AgentId(1);
    private static final AgentId REFUEL = new AgentId(9);

    @Test
    void stageBRecallTrapCapturesTheCompletePhysicalTerminalUniverse() {
        StageBRecallAudit audit = audit().stageBRecallAudit();
        assertEquals(StageBRecallAuditMode.EXHAUSTIVE, audit.mode());
        assertTrue(audit.uniquePhysicalTerminalCount() >= audit.productionEvaluatedCount());
        assertEquals(audit.uniquePhysicalTerminalCount(), audit.exhaustiveEvaluatedCount());
        assertFalse(audit.terminals().isEmpty());
    }

    @Test
    void k16SufficientFixtureKeepsTheExhaustiveWinnerInTheProductionShortlist() {
        StageBRecallAudit audit = audit().stageBRecallAudit();
        assertTrue(audit.exhaustiveWinnerPresentInK16());
        assertEquals(0, audit.hybridGapScore4());
    }

    @Test
    void semiVsCoupledTradeoffIsRepresentedWithoutChangingTheEvaluator() {
        StageBRecallAudit audit = audit().stageBRecallAudit();
        assertTrue(audit.terminals().stream().allMatch(value -> value.ownSemiCollections() >= 0));
        assertTrue(audit.terminals().stream().anyMatch(value -> value.coupledOwnCollections() != null));
    }

    @Test
    void physicalDiversityAuditReportsUniquePhysicalPlans() {
        StageBRecallAudit audit = audit().stageBRecallAudit();
        assertTrue(audit.productionUniquePhysicalSignatures() > 0);
        assertTrue(audit.productionUniqueEndPositionVectors() > 0);
        assertTrue(audit.productionDuplicateOrNearDuplicateCount() >= 0);
    }

    @Test
    void rootFamilyMixIsVisibleButHasNoQuota() {
        StageBRecallAudit audit = audit().stageBRecallAudit();
        assertTrue(audit.productionFamilyCounts().containsKey(0));
        assertTrue(audit.productionFamilyCounts().keySet().stream().allMatch(value -> value >= 0));
    }

    @Test
    void liveLikeM6861RecallAuditExposesStageARankAndGap() {
        StageBRecallAudit audit = audit().stageBRecallAudit();
        assertTrue(audit.exhaustiveWinner().stageARank() > 0);
        assertTrue(audit.k16BestHybrid() <= audit.exhaustiveBestHybrid());
    }

    @Test
    void k8K16K24K32AuditUsesTheSameStageAOrder() {
        StageBRecallAudit audit = audit().stageBRecallAudit();
        assertTrue(audit.k8BestHybrid() <= audit.k16BestHybrid());
        assertTrue(audit.k16BestHybrid() <= audit.k24BestHybrid());
        assertTrue(audit.k24BestHybrid() <= audit.k32BestHybrid());
        assertTrue(audit.k32BestHybrid() <= audit.exhaustiveBestHybrid());
        assertTrue(audit.winnerFirstAppearsAt() > 0);
    }

    @Test
    void exhaustiveWinnerStageARankIsRetainedOnEveryTerminalRecord() {
        StageBRecallAudit audit = audit().stageBRecallAudit();
        assertEquals(audit.exhaustiveWinner().stageARank(), audit.winnerFirstAppearsAt());
        assertTrue(audit.bestCoupledStageARank() > 0);
        assertTrue(audit.worstStageARankOfTop5Coupled() >= audit.bestCoupledStageARank());
    }

    @Test
    void auditOnOffSelectionIsInvariant() {
        DayState state = state();
        JointTeamBeamConfig base = new JointTeamBeamConfig(48, 64, 24, 4, 16);
        JointTeamBeamResult ordinary = new JointTeamBeamPlanner(base).planWithStats(state);
        JointTeamBeamResult audited = new JointTeamBeamPlanner(base
                .withStageBRecallAuditMode(StageBRecallAuditMode.EXHAUSTIVE)).planWithStats(state);
        assertEquals(ordinary.evaluation().base().deterministicSignature(),
                audited.evaluation().base().deterministicSignature());
        assertEquals(ordinary.evaluation().hybrid().hybridMarginScore4(),
                audited.evaluation().hybrid().hybridMarginScore4());
    }

    @Test
    void exhaustiveShadowDoesNotChangeProductionStageBEvaluationCount() {
        JointTeamBeamResult result = audit();
        assertTrue(result.stats().coupledTerminalEvaluations() <= 16);
        assertEquals(result.stats().stageBRequested(), result.stats().coupledTerminalEvaluations());
        assertTrue(result.stageBRecallAudit().exhaustiveEvaluatedCount()
                >= result.stageBRecallAudit().productionEvaluatedCount());
    }

    @Test
    void targetPortfolioNonRegressionKeepsCurrentPolicy() {
        JointTeamBeamResult result = audit();
        assertEquals(CompetitiveTargetPolicy.CURRENT, result.competitiveAudit().policy());
        assertEquals(0, result.stats().searchPathfindingExecutions());
    }

    @Test
    void stageBRecallIsDeterministic() {
        JointTeamBeamResult first = audit();
        JointTeamBeamResult second = audit();
        assertEquals(first.evaluation().base().deterministicSignature(), second.evaluation().base().deterministicSignature());
        assertEquals(first.stageBRecallAudit().exhaustiveWinner().physicalSignature(),
                second.stageBRecallAudit().exhaustiveWinner().physicalSignature());
        assertEquals(first.stageBRecallAudit().top5Recall(), second.stageBRecallAudit().top5Recall());
    }

    @Test
    void searchPathfindingRemainsZeroDuringRecallAudit() {
        JointTeamBeamResult result = audit();
        assertEquals(0, result.stats().searchPathfindingExecutions());
        assertNotNull(result.stageBRecallAudit().productionWinner());
    }

    private static JointTeamBeamResult audit() {
        return new JointTeamBeamPlanner(new JointTeamBeamConfig(48, 64, 24, 4, 16)
                .withStageBRecallAuditMode(StageBRecallAuditMode.EXHAUSTIVE)).planWithStats(state());
    }

    private static DayState state() {
        Terrain[] terrain = new Terrain[12];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<UdonSpot> spots = List.of(spot("A", 1, 1), spot("B", 4, 1), spot("C", 7, 1));
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(value -> stock.put(value.position(), value.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(12, 1, terrain),
                new DayStepBudgets(new int[] {12}), List.of(), new FuelCapacity(10), spots);
        return new DayState(match, new DayIndex(0), List.of(
                AgentState.patrol(PATROL_0, new Position(0), 6),
                AgentState.patrol(PATROL_1, new Position(8), 6),
                AgentState.refuel(REFUEL, new Position(3))), Map.of(), stock, List.of());
    }

    private static UdonSpot spot(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }
}
