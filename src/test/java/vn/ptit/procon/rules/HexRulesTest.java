package vn.ptit.procon.rules;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.model.Model.MapData;
import vn.ptit.procon.model.Model.Terrain;
import vn.ptit.procon.model.Model.Traffic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HexRulesTest {
    @Test void evenRowUsesOfficialDirectionOrder() {
        MapData map = new MapData(3, 3, new int[][]{{0,0,0},{0,0,0},{0,0,0}});
        assertEquals(List.of(-1, -1, 2, 5, 4, 0), HexRules.neighbors(1, map));
    }

    @Test void oddRowUsesOfficialDirectionOrder() {
        MapData map = new MapData(3, 3, new int[][]{{0,0,0},{0,0,0},{0,0,0}});
        assertEquals(List.of(0, 1, 5, 7, 6, 3), HexRules.neighbors(4, map));
    }

    @Test void movementCostComesFromSourceTerrain() {
        MapData map = new MapData(2, 2, new int[][]{{Terrain.PLAIN.code(), Terrain.ROAD.code()},
                {Terrain.MOUNTAIN.code(), Terrain.POND.code()}});
        Traffic[] traffic = new Traffic[4]; traffic[1] = Traffic.JAMMED;
        assertEquals(1, HexRules.moveCost(map, 0, traffic).fuel());
        assertEquals(4, HexRules.moveCost(map, 1, traffic).steps());
        assertNull(HexRules.moveCost(map, 3, traffic));
    }
}
