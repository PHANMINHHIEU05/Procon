package vn.ptit.procon.domain.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable terrain map using EVEN-R offset hex coordinates. */
public final class HexMap {

    private static final List<Direction> DIRECTIONS_IN_CODE_ORDER = List.of(Direction.values());

    private final int width;
    private final int height;
    private final Terrain[] cells;

    public HexMap(int width, int height, Terrain[] cells) {
        if (width <= 0) {
            throw new IllegalArgumentException("Map width must be positive: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("Map height must be positive: " + height);
        }
        Objects.requireNonNull(cells, "Terrain cells must not be null");

        long expectedCellCount = (long) width * height;
        if (expectedCellCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Map dimensions exceed the supported cell count: " + width + "x" + height);
        }
        if (cells.length != expectedCellCount) {
            throw new IllegalArgumentException(
                    "Expected " + expectedCellCount + " terrain cells but got " + cells.length);
        }

        Terrain[] copiedCells = cells.clone();
        for (int index = 0; index < copiedCells.length; index++) {
            if (copiedCells[index] == null) {
                throw new IllegalArgumentException("Terrain cell must not be null at index " + index);
            }
        }

        this.width = width;
        this.height = height;
        this.cells = copiedCells;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int cellCount() {
        return cells.length;
    }

    public boolean contains(Position position) {
        return position != null && position.value() < cells.length;
    }

    public int rowOf(Position position) {
        requireContained(position);
        return position.value() / width;
    }

    public int columnOf(Position position) {
        requireContained(position);
        return position.value() % width;
    }

    public Position positionOf(int row, int column) {
        if (row < 0 || row >= height) {
            throw new IllegalArgumentException(
                    "Row must be between 0 and " + (height - 1) + ": " + row);
        }
        if (column < 0 || column >= width) {
            throw new IllegalArgumentException(
                    "Column must be between 0 and " + (width - 1) + ": " + column);
        }
        return new Position(row * width + column);
    }

    public Terrain terrainAt(Position position) {
        requireContained(position);
        return cells[position.value()];
    }

    public boolean isTraversable(Position position) {
        return terrainAt(position).isTraversable();
    }

    public Optional<Position> neighbor(Position position, Direction direction) {
        requireContained(position);
        Objects.requireNonNull(direction, "Direction must not be null");

        int row = position.value() / width;
        int column = position.value() % width;
        boolean evenRow = (row & 1) == 0;
        int neighborRow = row + direction.deltaRow(evenRow);
        int neighborColumn = column + direction.deltaColumn(evenRow);

        if (!containsCoordinates(neighborRow, neighborColumn)) {
            return Optional.empty();
        }
        return Optional.of(new Position(neighborRow * width + neighborColumn));
    }

    public List<Position> neighbors(Position position) {
        requireContained(position);

        List<Position> result = new ArrayList<>(6);
        for (Direction direction : DIRECTIONS_IN_CODE_ORDER) {
            neighbor(position, direction).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    private boolean containsCoordinates(int row, int column) {
        return row >= 0 && row < height && column >= 0 && column < width;
    }

    private void requireContained(Position position) {
        if (!contains(position)) {
            throw new IllegalArgumentException("Position is outside this map: " + position);
        }
    }
}