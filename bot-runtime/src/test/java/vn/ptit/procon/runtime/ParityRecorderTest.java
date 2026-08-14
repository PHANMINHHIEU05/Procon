package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

class ParityRecorderTest {

    @Test
    void recordsSanitizedPerAgentMismatchValuesAndLiveSemanticStatus() {
        AgentId patrolId = new AgentId(0);
        AgentId refuelId = new AgentId(1);
        StaticMatchData matchData = new StaticMatchData(
                new HexMap(2, 1, new Terrain[] {Terrain.PLAIN, Terrain.PLAIN}),
                new DayStepBudgets(new int[] {1, 1}),
                List.of(),
                new FuelCapacity(5),
                List.of());
        DayState beginning = new DayState(
                matchData,
                new DayIndex(0),
                List.of(
                        AgentState.patrol(patrolId, new Position(0), 5),
                        AgentState.refuel(refuelId, new Position(1))),
                Map.of(),
                Map.of());
        TeamPlan plan = SafePlanFactory.waitAll(beginning);
        ValidDaySimulationResult predicted = assertInstanceOf(
                ValidDaySimulationResult.class,
                new DaySimulator().simulate(beginning, plan));
        DayState authoritative = new DayState(
                matchData,
                new DayIndex(1),
                List.of(
                        AgentState.patrol(patrolId, new Position(1), 3),
                        AgentState.refuel(refuelId, new Position(1))),
                Map.of(),
                Map.of());
        ParityRecorder recorder = new ParityRecorder();
        recorder.record(new ParityObservation(beginning, plan, predicted));

        ParityComparison comparison = recorder.observeNextState(authoritative).orElseThrow();

        assertEquals(ParityStatus.MISMATCH, comparison.position());
        assertEquals(ParityStatus.MISMATCH, comparison.patrolFuel());
        assertEquals(List.of(new AgentParityMismatch(
                patrolId, new Position(0), new Position(1), 5, 3)),
                comparison.agentMismatches());
        assertEquals(SemanticParityStatus.LIVE_MATCHED_FOR_TESTED_SCENARIO,
                recorder.semanticChecklist().get("REFUEL_SELECTED_RENDEZVOUS"));
        assertEquals(SemanticParityStatus.CORRECTED_AND_LIVE_SUPPORTED,
                recorder.semanticChecklist().get("REFUEL_DURING_ACTIVE_MOVEMENT"));
        assertEquals(SemanticParityStatus.LIVE_OBSERVED,
                recorder.semanticChecklist().get("REFUEL_ON_PATROL_ARRIVAL"));
        assertEquals(SemanticParityStatus.CORRECTED_LOCALLY,
                recorder.semanticChecklist().get("REFUEL_ON_PATROL_ARRIVAL_LOCAL_MODEL"));
        assertEquals(SemanticParityStatus.LIVE_OBSERVED,
                recorder.semanticChecklist().get("INCIDENTAL_REFUEL"));
        assertEquals(SemanticParityStatus.PARTIALLY_LIVE_VERIFIED,
                recorder.semanticChecklist().get("REFUEL_TIMING"));
        assertEquals(SemanticParityStatus.NOT_FULLY_VERIFIED,
                recorder.semanticChecklist().get("MULTI_STEP_INTERMEDIATE_POSITION"));
    }
}
