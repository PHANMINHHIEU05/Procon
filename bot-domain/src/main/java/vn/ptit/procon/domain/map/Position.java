package vn.ptit.procon.domain.map;

/** A server position encoded as a non-negative flat cell index. */
public record Position(int value) {

    public Position {
        if (value < 0) {
            throw new IllegalArgumentException("Position value must be non-negative: " + value);
        }
    }
}