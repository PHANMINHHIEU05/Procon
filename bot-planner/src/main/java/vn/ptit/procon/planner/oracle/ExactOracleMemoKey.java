package vn.ptit.procon.planner.oracle;

/** Named memo key to make the future-equivalence audit explicit. */
public record ExactOracleMemoKey(ExactOracleState state) {
    public ExactOracleMemoKey {
        if (state == null) throw new IllegalArgumentException("Memo state must not be null");
    }
}
