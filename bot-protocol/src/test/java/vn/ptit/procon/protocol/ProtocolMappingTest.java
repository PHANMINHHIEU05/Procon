package vn.ptit.procon.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.protocol.dto.DayStateDto;
import vn.ptit.procon.protocol.dto.SetupDto;

class ProtocolMappingTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsDocumentedSetupIncludingTwoDimensionalCellsAndOpaqueBrands() throws Exception {
        SetupDto dto = json.readValue("""
                {
                  "daySteps": [4, 5],
                  "map": {"width": 3, "height": 2, "cells": [[0,1,2],[3,0,0]]},
                  "spots": [
                    {"brand": "brand-exact", "pos": 4, "stocks": 3},
                    {"brand": 17, "pos": 5, "stocks": 2}
                  ],
                  "agents": [0, 5],
                  "fuelLimits": 9
                }
                """, SetupDto.class);

        StaticMatchData data = new SetupMapper().toDomain(dto);

        assertEquals(3, data.map().width());
        assertEquals(2, data.map().height());
        assertEquals(Terrain.POND, data.map().terrainAt(new Position(3)));
        assertEquals("brand-exact", data.udonSpots().get(0).brand().value());
        assertEquals("numeric:17", data.udonSpots().get(1).brand().value());
        assertEquals(9, data.patrolFuelCapacity().value());
        assertEquals(5, data.dayStepBudgets().stepsFor(new vn.ptit.procon.domain.match.DayIndex(1)));
    }

    @Test
    void mapsObservedLiveSetupWithoutTrafficThresholdFields() throws Exception {
        SetupDto dto = json.readValue("""
                {
                  "daySteps": [15],
                  "map": {"width": 2, "height": 1, "cells": [[0,1]]},
                  "spots": [],
                  "agents": [0],
                  "fuelLimits": 10
                }
                """, SetupDto.class);

        StaticMatchData data = new SetupMapper().toDomain(dto);

        assertEquals(15, data.dayStepBudgets().stepsFor(new vn.ptit.procon.domain.match.DayIndex(0)));
        assertEquals(1, data.initialAgents().size());
        assertFalse(java.util.Arrays.stream(StaticMatchData.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("trafficThresholds")));
    }

    @Test
    void mapsAuthoritativeOwnAgentsTrafficAndSetupStockCapacity() throws Exception {
        SetupDto setup = json.readValue("""
                {"daySteps":[4],"map":{"width":3,"height":1,"cells":[[0,1,0]]},
                 "spots":[{"brand":1,"pos":2,"stocks":3}],"agents":[0,2],"fuelLimits":8}
                """, SetupDto.class);
        DayStateDto dto = json.readValue("""
                {"day":0,"agents":[{"kind":0,"pos":0,"fuel":0},{"kind":1,"pos":2,"fuel":null}],
                 "others":[{"agents":[]}],"traffics":[{"pos":1,"status":2}]}
                """, DayStateDto.class);

        DayState state = new DayStateMapper().toDomain(
                dto,
                new SetupMapper().toDomain(setup),
                List.of(AgentKind.PATROL, AgentKind.REFUEL));

        assertEquals(0, ((FiniteFuel) state.agents().get(0).fuel()).amount());
        assertEquals(AgentKind.REFUEL, state.agents().get(1).kind());
        assertEquals(TrafficStatus.JAMMED, state.roadTraffic().get(new Position(1)));
        assertEquals(3, state.spotStock().get(new Position(2)));
    }

    @Test
    void rejectsServerKindThatDoesNotMatchAssignment() throws Exception {
        SetupDto setup = json.readValue("""
                {"daySteps":[4],"map":{"width":1,"height":1,"cells":[[0]]},
                 "spots":[],"agents":[0],"fuelLimits":8}
                """, SetupDto.class);
        DayStateDto dto = json.readValue("""
                {"day":0,"agents":[{"kind":1,"pos":0,"fuel":null}],"traffics":[]}
                """, DayStateDto.class);

        ProtocolMappingException error = assertThrows(
                ProtocolMappingException.class,
                () -> new DayStateMapper().toDomain(
                        dto, new SetupMapper().toDomain(setup), List.of(AgentKind.PATROL)));
        assertTrue(error.getMessage().contains("$.agents[0].kind"));
    }

    @Test
    void actionEncoderUsesDirectionCodesNegativeWaitAndAgentIdOrder() {
        TeamPlan plan = new TeamPlan(java.util.Map.of(
                new AgentId(1), List.of(new WaitAction(15)),
                new AgentId(0), List.of(new MoveAction(Direction.RIGHT), new WaitAction(28))));

        assertEquals(List.of(List.of(2, -28), List.of(-15)), new ActionEncoder().encode(plan, 2));
    }
}
