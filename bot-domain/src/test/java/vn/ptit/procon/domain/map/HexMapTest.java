package vn.ptit.procon.domain.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class HexMapTest {

    @ParameterizedTest
    @MethodSource("invalidDimensions")
    void rejectsNonPositiveDimensions(int width, int height) {
        assertThrows(IllegalArgumentException.class, () -> new HexMap(width, height, new Terrain[0]));
    }

    @Test
    void rejectsDimensionsWhoseCellCountExceedsIntegerRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HexMap(Integer.MAX_VALUE, 2, new Terrain[0]));
    }

    @Test
    void rejectsNullCells() {
        assertThrows(NullPointerException.class, () -> new HexMap(1, 1, null));
    }

    @Test
    void rejectsWrongCellCount() {
        assertThrows(IllegalArgumentException.class, () -> new HexMap(2, 2, plainCells(3)));
        assertThrows(IllegalArgumentException.class, () -> new HexMap(2, 2, plainCells(5)));
    }

    @Test
    void rejectsNullTerrainEntry() {
        Terrain[] cells = plainCells(4);
        cells[2] = null;

        assertThrows(IllegalArgumentException.class, () -> new HexMap(2, 2, cells));
    }

    @Test
    void exposesDimensionsAndCellCount() {
        HexMap map = plainMap(4, 3);

        assertEquals(4, map.width());
        assertEquals(3, map.height());
        assertEquals(12, map.cellCount());
    }

    @ParameterizedTest
    @MethodSource("flatPositionConversions")
    void convertsFlatPositionsToRowsAndColumns(int value, int expectedRow, int expectedColumn) {
        HexMap map = plainMap(4, 3);
        Position position = new Position(value);

        assertEquals(expectedRow, map.rowOf(position));
        assertEquals(expectedColumn, map.columnOf(position));
    }

    @ParameterizedTest
    @MethodSource("flatPositionConversions")
    void convertsRowsAndColumnsToFlatPositions(int expectedValue, int row, int column) {
        HexMap map = plainMap(4, 3);

        assertEquals(new Position(expectedValue), map.positionOf(row, column));
    }

    @ParameterizedTest
    @MethodSource("invalidCoordinates")
    void rejectsInvalidRowsAndColumns(int row, int column) {
        HexMap map = plainMap(4, 3);

        assertThrows(IllegalArgumentException.class, () -> map.positionOf(row, column));
    }

    @Test
    void containsOnlyNonNullPositionsWithinFlatBounds() {
        HexMap map = plainMap(4, 3);

        assertTrue(map.contains(new Position(0)));
        assertTrue(map.contains(new Position(11)));
        assertFalse(map.contains(new Position(12)));
        assertFalse(map.contains(null));
    }

    @Test
    void methodsRequiringAValidPositionRejectInvalidInput() {
        HexMap map = plainMap(4, 3);
        Position outside = new Position(12);

        assertThrows(IllegalArgumentException.class, () -> map.rowOf(outside));
        assertThrows(IllegalArgumentException.class, () -> map.columnOf(outside));
        assertThrows(IllegalArgumentException.class, () -> map.terrainAt(outside));
        assertThrows(IllegalArgumentException.class, () -> map.isTraversable(outside));
        assertThrows(IllegalArgumentException.class, () -> map.neighbor(outside, Direction.RIGHT));
        assertThrows(IllegalArgumentException.class, () -> map.neighbors(outside));
        assertThrows(IllegalArgumentException.class, () -> map.rowOf(null));
    }

    @Test
    void neighborRejectsNullDirection() {
        HexMap map = plainMap(1, 1);

        assertThrows(NullPointerException.class, () -> map.neighbor(new Position(0), null));
    }

    @ParameterizedTest
    @MethodSource("evenRowNeighbors")
    void computesEveryEvenRowNeighbor(Direction direction, int expectedPosition) {
        HexMap map = plainMap(5, 5);

        assertEquals(
                Optional.of(new Position(expectedPosition)),
                map.neighbor(new Position(12), direction));
    }

    @ParameterizedTest
    @MethodSource("oddRowNeighbors")
    void computesEveryOddRowNeighbor(Direction direction, int expectedPosition) {
        HexMap map = plainMap(5, 5);

        assertEquals(
                Optional.of(new Position(expectedPosition)),
                map.neighbor(new Position(7), direction));
    }

    @ParameterizedTest
    @MethodSource("cornerNeighbors")
    void computesCornerNeighborsInOfficialDirectionOrder(int source, List<Position> expected) {
        HexMap map = plainMap(5, 5);

        assertEquals(expected, map.neighbors(new Position(source)));
        assertTrue(expected.size() < 6);
    }

    @ParameterizedTest
    @MethodSource("edgeNeighbors")
    void computesRepresentativeEdgeNeighborsInOfficialDirectionOrder(
            int source, List<Position> expected) {
        HexMap map = plainMap(5, 5);

        assertEquals(expected, map.neighbors(new Position(source)));
    }

    @Test
    void doesNotWrapAcrossRowsOrMapEdges() {
        HexMap map = plainMap(5, 5);

        assertEquals(Optional.empty(), map.neighbor(new Position(4), Direction.RIGHT));
        assertEquals(Optional.empty(), map.neighbor(new Position(5), Direction.LEFT));
        assertEquals(Optional.empty(), map.neighbor(new Position(0), Direction.UP_LEFT));
        assertEquals(Optional.empty(), map.neighbor(new Position(24), Direction.DOWN_RIGHT));
    }

    @Test
    void centerNeighborsAreUniqueOrderedAndInsideTheMap() {
        HexMap map = plainMap(5, 5);
        List<Position> neighbors = map.neighbors(new Position(12));

        assertEquals(positions(7, 8, 13, 18, 17, 11), neighbors);
        assertEquals(6, neighbors.size());
        assertEquals(neighbors.size(), new HashSet<>(neighbors).size());
        assertTrue(neighbors.stream().allMatch(map::contains));
    }

    @Test
    void returnedNeighborListIsImmutable() {
        HexMap map = plainMap(5, 5);
        List<Position> neighbors = map.neighbors(new Position(12));

        assertThrows(UnsupportedOperationException.class, () -> neighbors.add(new Position(0)));
    }

    @ParameterizedTest
    @EnumSource(Terrain.class)
    void reportsTerrainAndTraversability(Terrain terrain) {
        HexMap map = new HexMap(1, 1, new Terrain[] {terrain});

        assertEquals(terrain, map.terrainAt(new Position(0)));
        assertEquals(terrain != Terrain.POND, map.isTraversable(new Position(0)));
    }

    @Test
    void pondRemainsAGeometricNeighbor() {
        Terrain[] cells = plainCells(4);
        cells[1] = Terrain.POND;
        HexMap map = new HexMap(2, 2, cells);

        Optional<Position> neighbor = map.neighbor(new Position(0), Direction.RIGHT);

        assertEquals(Optional.of(new Position(1)), neighbor);
        assertFalse(map.isTraversable(neighbor.orElseThrow()));
        assertTrue(map.neighbors(new Position(0)).contains(new Position(1)));
    }

    @Test
    void constructorDefensivelyCopiesTerrainCells() {
        Terrain[] input = new Terrain[] {Terrain.PLAIN};
        HexMap map = new HexMap(1, 1, input);

        input[0] = Terrain.POND;

        assertEquals(Terrain.PLAIN, map.terrainAt(new Position(0)));
        assertTrue(map.isTraversable(new Position(0)));
    }

    private static Stream<Arguments> invalidDimensions() {
        return Stream.of(
                Arguments.of(0, 1),
                Arguments.of(-1, 1),
                Arguments.of(1, 0),
                Arguments.of(1, -1));
    }

    private static Stream<Arguments> flatPositionConversions() {
        return Stream.of(
                Arguments.of(0, 0, 0),
                Arguments.of(3, 0, 3),
                Arguments.of(4, 1, 0),
                Arguments.of(6, 1, 2),
                Arguments.of(11, 2, 3));
    }

    private static Stream<Arguments> invalidCoordinates() {
        return Stream.of(
                Arguments.of(-1, 0),
                Arguments.of(3, 0),
                Arguments.of(0, -1),
                Arguments.of(0, 4));
    }

    private static Stream<Arguments> evenRowNeighbors() {
        return Stream.of(
                Arguments.of(Direction.UP_LEFT, 7),
                Arguments.of(Direction.UP_RIGHT, 8),
                Arguments.of(Direction.RIGHT, 13),
                Arguments.of(Direction.DOWN_RIGHT, 18),
                Arguments.of(Direction.DOWN_LEFT, 17),
                Arguments.of(Direction.LEFT, 11));
    }

    private static Stream<Arguments> oddRowNeighbors() {
        return Stream.of(
                Arguments.of(Direction.UP_LEFT, 1),
                Arguments.of(Direction.UP_RIGHT, 2),
                Arguments.of(Direction.RIGHT, 8),
                Arguments.of(Direction.DOWN_RIGHT, 12),
                Arguments.of(Direction.DOWN_LEFT, 11),
                Arguments.of(Direction.LEFT, 6));
    }

    private static Stream<Arguments> cornerNeighbors() {
        return Stream.of(
                Arguments.of(0, positions(1, 6, 5)),
                Arguments.of(4, positions(9, 3)),
                Arguments.of(20, positions(15, 16, 21)),
                Arguments.of(24, positions(19, 23)));
    }

    private static Stream<Arguments> edgeNeighbors() {
        return Stream.of(
                Arguments.of(2, positions(3, 8, 7, 1)),
                Arguments.of(22, positions(17, 18, 23, 21)),
                Arguments.of(5, positions(0, 6, 10)),
                Arguments.of(9, positions(3, 4, 14, 13, 8)));
    }

    private static HexMap plainMap(int width, int height) {
        return new HexMap(width, height, plainCells(width * height));
    }

    private static Terrain[] plainCells(int count) {
        Terrain[] cells = new Terrain[count];
        Arrays.fill(cells, Terrain.PLAIN);
        return cells;
    }

    private static List<Position> positions(int... values) {
        return Arrays.stream(values).mapToObj(Position::new).toList();
    }
}