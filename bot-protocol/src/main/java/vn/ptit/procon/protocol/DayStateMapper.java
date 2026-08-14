package vn.ptit.procon.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.protocol.dto.AgentStateDto;
import vn.ptit.procon.protocol.dto.DayStateDto;
import vn.ptit.procon.protocol.dto.TrafficDto;

/** Maps authoritative current-day JSON without deriving traffic or opponent data. */
public final class DayStateMapper {

    private static final String ENDPOINT = "/state";

    public DayState toDomain(
            DayStateDto dto, StaticMatchData matchData, List<AgentKind> assignment) {
        require(dto, "$", "object");
        require(matchData, "<static match data>", "mapped setup");
        require(assignment, "<assignment>", "agent-kind list");
        int day = require(dto.day(), "$.day", "zero-based non-negative integer");
        List<AgentStateDto> wireAgents = require(dto.agents(), "$.agents", "array");
        List<TrafficDto> traffics = require(dto.traffics(), "$.traffics", "array");
        if (wireAgents.size() != assignment.size()) {
            throw new ProtocolMappingException(
                    ENDPOINT, "$.agents", assignment.size() + " assigned agents", wireAgents.size());
        }

        try {
            List<AgentState> agents = new ArrayList<>(wireAgents.size());
            for (int index = 0; index < wireAgents.size(); index++) {
                AgentStateDto agent = require(wireAgents.get(index), "$.agents[" + index + "]", "object");
                int kindCode = require(agent.kind(), "$.agents[" + index + "].kind", "agent kind 0 or 1");
                AgentKind kind = AgentKind.fromCode(kindCode);
                if (kind != assignment.get(index)) {
                    throw new ProtocolMappingException(
                            ENDPOINT,
                            "$.agents[" + index + "].kind",
                            "assigned kind " + assignment.get(index).code(),
                            kindCode);
                }
                int position = require(agent.pos(), "$.agents[" + index + "].pos", "non-negative integer");
                if (kind == AgentKind.PATROL) {
                    int fuel = require(agent.fuel(), "$.agents[" + index + "].fuel", "non-negative integer");
                    agents.add(AgentState.patrol(new AgentId(index), new Position(position), fuel));
                } else {
                    agents.add(AgentState.refuel(new AgentId(index), new Position(position)));
                }
            }

            Map<Position, TrafficStatus> traffic = new LinkedHashMap<>();
            for (int index = 0; index < traffics.size(); index++) {
                TrafficDto entry = require(traffics.get(index), "$.traffics[" + index + "]", "object");
                Position position = new Position(require(
                        entry.pos(), "$.traffics[" + index + "].pos", "ROAD position"));
                TrafficStatus status = TrafficStatus.fromCode(require(
                        entry.status(), "$.traffics[" + index + "].status", "traffic status 0..2"));
                if (traffic.putIfAbsent(position, status) != null) {
                    throw new ProtocolMappingException(
                            ENDPOINT, "$.traffics[" + index + "].pos", "unique ROAD position", position.value());
                }
            }

            Map<Position, Integer> replenishedStock = new LinkedHashMap<>();
            for (UdonSpot spot : matchData.udonSpots()) {
                replenishedStock.put(spot.position(), spot.stockCapacity());
            }
            return new DayState(
                    matchData, new DayIndex(day), agents, traffic, replenishedStock);
        } catch (ProtocolMappingException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new ProtocolMappingException(ENDPOINT, "$", exception.getMessage(), exception);
        }
    }

    private static <T> T require(T value, String path, String expected) {
        if (value == null) {
            throw new ProtocolMappingException(ENDPOINT, path, expected, null);
        }
        return value;
    }
}