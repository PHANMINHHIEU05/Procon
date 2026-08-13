package vn.ptit.procon.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;

class MovementRulesTest {

    @ParameterizedTest
    @MethodSource("terrainCosts")
    void calculatesEveryOfficialTerrainCost(
            Terrain terrain, TrafficStatus traffic, MoveCost expected) {
        assertEquals(expected, MovementRules.costFromSource(terrain, traffic).orElseThrow());
    }

    @Test
    void pondMovementIsExplicitlyImpossible() {
        assertEquals(Optional.empty(), MovementRules.costFromSource(Terrain.POND, TrafficStatus.CLEAR));
    }

    @ParameterizedTest
    @EnumSource(TrafficStatus.class)
    void nonRoadTrafficDoesNotChangePlainOrMountainCost(TrafficStatus traffic) {
        assertEquals(new MoveCost(2, 1), MovementRules.costFromSource(Terrain.PLAIN, traffic).orElseThrow());
        assertEquals(new MoveCost(3, 2), MovementRules.costFromSource(Terrain.MOUNTAIN, traffic).orElseThrow());
    }

    @Test
    void roadRequiresAuthoritativeTrafficStatus() {
        assertThrows(
                NullPointerException.class,
                () -> MovementRules.costFromSource(Terrain.ROAD, null));
    }

    @Test
    void calculatesCostFromSourceCellNotDestinationCell() {
        HexMap map = new HexMap(2, 1, new Terrain[] {Terrain.MOUNTAIN, Terrain.PLAIN});
        HexMap reverseMap = new HexMap(2, 1, new Terrain[] {Terrain.PLAIN, Terrain.MOUNTAIN});

        assertEquals(
                new MoveCost(3, 2),
                MovementRules.costFromSource(map, new Position(0), TrafficStatus.CLEAR).orElseThrow());
        assertEquals(
                new MoveCost(2, 1),
                MovementRules.costFromSource(reverseMap, new Position(0), TrafficStatus.CLEAR).orElseThrow());
    }

    @Test
    void jammedRoadSourceRetainsRoadCostWhenDestinationIsPlain() {
        HexMap map = new HexMap(2, 1, new Terrain[] {Terrain.ROAD, Terrain.PLAIN});

        assertEquals(
                new MoveCost(4, 2),
                MovementRules.costFromSource(map, new Position(0), TrafficStatus.JAMMED).orElseThrow());
    }

    @Test
    void mapBasedRuleRejectsInvalidSourcePosition() {
        HexMap map = new HexMap(1, 1, new Terrain[] {Terrain.PLAIN});

        assertThrows(
                IllegalArgumentException.class,
                () -> MovementRules.costFromSource(map, new Position(1), TrafficStatus.CLEAR));
    }

    private static Stream<Arguments> terrainCosts() {
        return Stream.of(
                Arguments.of(Terrain.PLAIN, TrafficStatus.JAMMED, new MoveCost(2, 1)),
                Arguments.of(Terrain.MOUNTAIN, TrafficStatus.CONGESTED, new MoveCost(3, 2)),
                Arguments.of(Terrain.ROAD, TrafficStatus.CLEAR, new MoveCost(1, 2)),
                Arguments.of(Terrain.ROAD, TrafficStatus.CONGESTED, new MoveCost(2, 2)),
                Arguments.of(Terrain.ROAD, TrafficStatus.JAMMED, new MoveCost(4, 2)));
    }
}