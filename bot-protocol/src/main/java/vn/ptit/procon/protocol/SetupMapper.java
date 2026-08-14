package vn.ptit.procon.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.agent.InitialAgent;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.protocol.dto.MapDto;
import vn.ptit.procon.protocol.dto.SetupDto;
import vn.ptit.procon.protocol.dto.SpotDto;

/** Maps the documented setup JSON into the existing immutable domain. */
public final class SetupMapper {

    private static final String ENDPOINT = "/setup";

    public StaticMatchData toDomain(SetupDto setup) {
        require(setup, "$", "object");
        HexMap map = map(setup.map());
        int[] daySteps = require(setup.daySteps(), "$.daySteps", "non-empty integer array");
        List<Integer> agentPositions = require(setup.agents(), "$.agents", "array");
        Integer fuelLimit = require(setup.fuelLimits(), "$.fuelLimits", "positive integer");
        List<SpotDto> spots = require(setup.spots(), "$.spots", "array");

        try {
            List<InitialAgent> agents = new ArrayList<>(agentPositions.size());
            for (int index = 0; index < agentPositions.size(); index++) {
                Integer position = require(
                        agentPositions.get(index), "$.agents[" + index + "]", "non-negative integer");
                agents.add(new InitialAgent(new AgentId(index), new Position(position)));
            }

            List<UdonSpot> udonSpots = new ArrayList<>(spots.size());
            for (int index = 0; index < spots.size(); index++) {
                SpotDto spot = require(spots.get(index), "$.spots[" + index + "]", "object");
                int position = require(spot.pos(), "$.spots[" + index + "].pos", "non-negative integer");
                int stock = require(spot.stocks(), "$.spots[" + index + "].stocks", "non-negative integer");
                udonSpots.add(new UdonSpot(
                        brand(spot.brand(), "$.spots[" + index + "].brand"),
                        new Position(position),
                        stock));
            }

            return new StaticMatchData(
                    map,
                    new DayStepBudgets(daySteps),
                    agents,
                    new FuelCapacity(fuelLimit),
                    udonSpots);
        } catch (ProtocolMappingException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new ProtocolMappingException(ENDPOINT, "$", exception.getMessage(), exception);
        }
    }

    private HexMap map(MapDto map) {
        require(map, "$.map", "object");
        int width = require(map.width(), "$.map.width", "positive integer");
        int height = require(map.height(), "$.map.height", "positive integer");
        int[][] rows = require(map.cells(), "$.map.cells", "2D integer array");
        if (rows.length != height) {
            throw new ProtocolMappingException(ENDPOINT, "$.map.cells", height + " rows", rows.length);
        }

        Terrain[] cells = new Terrain[Math.multiplyExact(width, height)];
        for (int row = 0; row < height; row++) {
            if (rows[row] == null || rows[row].length != width) {
                throw new ProtocolMappingException(
                        ENDPOINT,
                        "$.map.cells[" + row + "]",
                        width + " integer cells",
                        rows[row] == null ? null : rows[row].length);
            }
            for (int column = 0; column < width; column++) {
                try {
                    cells[row * width + column] = Terrain.fromCode(rows[row][column]);
                } catch (IllegalArgumentException exception) {
                    throw new ProtocolMappingException(
                            ENDPOINT,
                            "$.map.cells[" + row + "][" + column + "]",
                            "terrain code 0..3",
                            rows[row][column]);
                }
            }
        }
        return new HexMap(width, height, cells);
    }

    private BrandId brand(JsonNode node, String path) {
        if (node == null || node.isNull()) {
            throw new ProtocolMappingException(ENDPOINT, path, "string or integer", node);
        }
        if (node.isTextual() && !node.textValue().isBlank()) {
            return new BrandId(node.textValue());
        }
        if (node.isIntegralNumber()) {
            return new BrandId("numeric:" + node.bigIntegerValue());
        }
        throw new ProtocolMappingException(ENDPOINT, path, "string or integer", node.getNodeType());
    }

    private static <T> T require(T value, String path, String expected) {
        if (value == null) {
            throw new ProtocolMappingException(ENDPOINT, path, expected, null);
        }
        return value;
    }
}