package vn.ptit.procon.planner;

/** Deterministic M12.1 planner heuristic; not an actual or expected server score. */
public record SemiCommitmentAdjustedCollectionScore(int value) {

    public SemiCommitmentAdjustedCollectionScore {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "Semi-commitment-adjusted collection score must be non-negative");
        }
    }
}
