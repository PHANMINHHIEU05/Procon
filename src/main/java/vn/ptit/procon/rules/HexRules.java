package vn.ptit.procon.rules;

import vn.ptit.procon.model.Model;
import vn.ptit.procon.model.Model.MapData;
import vn.ptit.procon.model.Model.MoveCost;
import vn.ptit.procon.model.Model.Position;
import vn.ptit.procon.model.Model.Terrain;
import vn.ptit.procon.model.Model.Traffic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class HexRules {
    private HexRules() {}

    public static List<Integer> neighbors(int flat, MapData map) {
        Position p = Position.fromFlat(flat, map.width());
        boolean even = (p.row() & 1) == 0;
        int[][] deltas = even
                ? new int[][]{{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 0}}
                : new int[][]{{-1, -1}, {0, -1}, {1, 0}, {0, 1}, {-1, 1}, {-1, 0}};
        List<Integer> result = new ArrayList<>(6);
        for (int[] d : deltas) {
            int row = p.row() + d[1], col = p.col() + d[0];
            if (row >= 0 && row < map.height() && col >= 0 && col < map.width()) {
                result.add(row * map.width() + col);
            } else result.add(-1);
        }
        return result;
    }

    public static MoveCost moveCost(MapData map, int source, Traffic[] trafficByCell) {
        Terrain terrain = map.terrain(source);
        return switch (terrain) {
            case PLAIN -> new MoveCost(2, 1);
            case MOUNTAIN -> new MoveCost(3, 2);
            case ROAD -> switch (trafficByCell[source] == null ? Traffic.CLEAR : trafficByCell[source]) {
                case CLEAR -> new MoveCost(1, 2);
                case CONGESTED -> new MoveCost(2, 2);
                case JAMMED -> new MoveCost(4, 2);
            };
            case POND -> null;
        };
    }

    public static Traffic[] traffic(int cells, List<Model.TrafficCell> entries) {
        Traffic[] result = new Traffic[cells];
        Arrays.fill(result, Traffic.CLEAR);
        for (Model.TrafficCell entry : entries) {
            if (entry.position() >= 0 && entry.position() < cells) result[entry.position()] = entry.traffic();
        }
        return result;
    }
}
