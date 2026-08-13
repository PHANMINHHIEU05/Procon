package vn.ptit.procon.domain.match;

/** Zero-based API day index. */
public record DayIndex(int value) {

    public DayIndex {
        if (value < 0) {
            throw new IllegalArgumentException("Day index must be non-negative: " + value);
        }
    }
}