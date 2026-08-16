package vn.ptit.procon.planner;

/** Bounded heuristic target rank; pressure units are not probabilities. */
public enum IntentRank {
    PRIMARY(1, 3),
    SECONDARY(2, 2),
    TERTIARY(3, 1);

    private final int value;
    private final int pressureUnits;

    IntentRank(int value, int pressureUnits) {
        this.value = value;
        this.pressureUnits = pressureUnits;
    }

    public int value() {
        return value;
    }

    public int pressureUnits() {
        return pressureUnits;
    }

    static IntentRank fromOneBasedIndex(int index) {
        return switch (index) {
            case 1 -> PRIMARY;
            case 2 -> SECONDARY;
            case 3 -> TERTIARY;
            default -> throw new IllegalArgumentException("Intent rank index must be between 1 and 3");
        };
    }
}