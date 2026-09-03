package vn.ptit.procon.planner.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

class TerminalObjectiveAuditTest {
    private static final AgentId PATROL_0 = new AgentId(0);
    private static final AgentId PATROL_1 = new AgentId(1);
    private static final AgentId REFUEL = new AgentId(9);

    @Test
    void objectiveAuditIsOptInAndCannotChangeSelection() {
        JointTeamBeamConfig base = new JointTeamBeamConfig(48, 64, 24, 4, 16);
        JointTeamBeamResult ordinary = new JointTeamBeamPlanner(base).planWithStats(state());
        JointTeamBeamResult audited = new JointTeamBeamPlanner(base
                .withStageBRecallAuditMode(StageBRecallAuditMode.EXHAUSTIVE)).planWithStats(state());
        assertEquals(StageBRecallAuditMode.OFF, ordinary.terminalObjectiveAudit().mode());
        assertEquals(ordinary.evaluation().base().deterministicSignature(),
                audited.evaluation().base().deterministicSignature());
        assertEquals(ordinary.stats().coupledTerminalEvaluations(), audited.stats().coupledTerminalEvaluations());
        assertNotEquals(0, audited.terminalObjectiveAudit().terminalCount());
    }

    @Test
    void paretoFrontierAndDominanceAreExplicit() {
        TerminalObjectiveAudit audit = audited().terminalObjectiveAudit();
        assertTrue(audit.paretoCount() > 0);
        assertTrue(audit.paretoCount() <= audit.terminalCount());
        assertTrue(audit.productionWinnerOnParetoFrontier());
        assertFalse(audit.productionWinnerDominated());
        assertTrue(audit.productionWinnerParetoRank() > 0);
    }

    @Test
    void weightSensitivityCoversBothSidesOfTheFrozenObjective() {
        TerminalObjectiveAudit audit = audited().terminalObjectiveAudit();
        assertEquals(25, audit.weightConfigurationCount());
        assertEquals(25, audit.weightWinnerSignatures().size());
        assertTrue(audit.weightWinnerSignatures().containsKey("1_1__1_1"));
        assertTrue(audit.weightWinnerSignatures().containsKey("5_1__5_1"));
        assertTrue(audit.distinctWeightWinners() >= 1);
        assertTrue(audit.productionWinnerStability() >= 0.0 && audit.productionWinnerStability() <= 1.0);
    }

    @Test
    void alternativeObjectivesRemainBenchmarkOnly() {
        TerminalObjectiveAudit audit = audited().terminalObjectiveAudit();
        assertEquals(audit.exhaustiveWinnerSignature(), audit.alternativeObjectiveWinners().get("CURRENT_3_1"));
        assertTrue(audit.alternativeObjectiveWinners().keySet().containsAll(
                List.of("CURRENT_3_1", "SEMI_2_1", "SEMI_4_1", "PURE_COUPLED", "ROBUST_MIN")));
    }

    @Test
    void calibrationAndCoupledRegretAreComputedFromTheSameTerminal() {
        TerminalObjectiveAudit audit = audited().terminalObjectiveAudit();
        assertTrue(audit.terminals().stream().allMatch(value -> value.coupledMargin()
                == value.coupledOwnCollections() - value.coupledOpponentCollections()));
        assertEquals(audit.productionCoupledMargin() + audit.selectionRegret(), audit.coupledOracleMargin());
        assertEquals(audit.productionOwnSemiCollections() - audit.productionCoupledOwnCollections(),
                audit.ownCalibrationError());
        assertEquals(audit.productionBaselineOpponentCollections() - audit.productionCoupledOpponentCollections(),
                audit.opponentCalibrationError());
    }

    @Test
    void deterministicAuditUsesStableRowsAndSignatures() {
        TerminalObjectiveAudit first = audited().terminalObjectiveAudit();
        TerminalObjectiveAudit second = audited().terminalObjectiveAudit();
        assertEquals(first.productionWinnerSignature(), second.productionWinnerSignature());
        assertEquals(first.coupledOracleSignature(), second.coupledOracleSignature());
        assertEquals(first.weightWinnerSignatures(), second.weightWinnerSignatures());
        assertEquals(first.terminals(), second.terminals());
    }

    @Test
    void exactPhysicalRowsRetainTheStageARankAndProvenance() {
        TerminalObjectiveAudit audit = audited().terminalObjectiveAudit();
        assertTrue(audit.terminals().stream().allMatch(value -> value.stageARank() > 0));
        assertTrue(audit.terminals().stream().allMatch(value -> value.physicalSignature() != null));
        assertTrue(audit.terminals().stream().allMatch(value -> value.rootFamily() >= 0));
    }

    @Test
    void auditDoesNotInvokeSearchPathfinding() {
        JointTeamBeamResult result = audited();
        assertEquals(0, result.stats().searchPathfindingExecutions());
    }

    private static JointTeamBeamResult audited() {
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
