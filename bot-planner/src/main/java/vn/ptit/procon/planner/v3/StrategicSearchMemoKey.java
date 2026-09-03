package vn.ptit.procon.planner.v3;

/** Named wrapper documenting that memoization uses future state, not history text. */
public record StrategicSearchMemoKey(String value) {
    public StrategicSearchMemoKey { if (value == null) throw new IllegalArgumentException("Memo key must not be null"); }
    public static StrategicSearchMemoKey of(StrategicSearchState state) { return new StrategicSearchMemoKey(state.exactKey()); }
}
