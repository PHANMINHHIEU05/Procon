package vn.ptit.procon.rules;

import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;

/** Pure official movement-cost rules. Costs are based on the source cell. */
public final class MovementRules {

    private MovementRules() {
    }

    public static Optional<MoveCost> costFromSource(
            HexMap map, Position source, TrafficStatus sourceTraffic) {
        Objects.requireNonNull(map, "Map must not be null");
        Terrain sourceTerrain = map.terrainAt(source);
        return costFromSource(sourceTerrain, sourceTraffic);
    }

    public static Optional<MoveCost> costFromSource(
            Terrain sourceTerrain, TrafficStatus sourceTraffic) {
        Objects.requireNonNull(sourceTerrain, "Source terrain must not be null");
        return switch (sourceTerrain) {
            case PLAIN -> Optional.of(new MoveCost(2, 1));
            case MOUNTAIN -> Optional.of(new MoveCost(3, 2));
            case ROAD -> Optional.of(roadCost(Objects.requireNonNull(
                    sourceTraffic, "Traffic status is required for ROAD movement")));
            case POND -> Optional.empty();
        };
    }

    private static MoveCost roadCost(TrafficStatus trafficStatus) {
        return switch (trafficStatus) {
            case CLEAR -> new MoveCost(1, 2);
            case CONGESTED -> new MoveCost(2, 2);
            case JAMMED -> new MoveCost(4, 2);
        };
    }
}