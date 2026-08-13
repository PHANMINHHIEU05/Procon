package vn.ptit.procon.domain.map;

/**
 * The six hex directions in official code order, with EVEN-R offsets expressed
 * as (delta column, delta row).
 */
public enum Direction {
    UP_LEFT(0, 0, -1, -1, -1),
    UP_RIGHT(1, 1, -1, 0, -1),
    RIGHT(2, 1, 0, 1, 0),
    DOWN_RIGHT(3, 1, 1, 0, 1),
    DOWN_LEFT(4, 0, 1, -1, 1),
    LEFT(5, -1, 0, -1, 0);

    private final int code;
    private final int evenDeltaColumn;
    private final int evenDeltaRow;
    private final int oddDeltaColumn;
    private final int oddDeltaRow;

    Direction(
            int code,
            int evenDeltaColumn,
            int evenDeltaRow,
            int oddDeltaColumn,
            int oddDeltaRow) {
        this.code = code;
        this.evenDeltaColumn = evenDeltaColumn;
        this.evenDeltaRow = evenDeltaRow;
        this.oddDeltaColumn = oddDeltaColumn;
        this.oddDeltaRow = oddDeltaRow;
    }

    public int code() {
        return code;
    }

    int deltaColumn(boolean evenRow) {
        return evenRow ? evenDeltaColumn : oddDeltaColumn;
    }

    int deltaRow(boolean evenRow) {
        return evenRow ? evenDeltaRow : oddDeltaRow;
    }

    public static Direction fromCode(int code) {
        return switch (code) {
            case 0 -> UP_LEFT;
            case 1 -> UP_RIGHT;
            case 2 -> RIGHT;
            case 3 -> DOWN_RIGHT;
            case 4 -> DOWN_LEFT;
            case 5 -> LEFT;
            default -> throw new IllegalArgumentException("Unknown direction code: " + code);
        };
    }
}