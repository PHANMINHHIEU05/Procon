package vn.ptit.procon.planner;

/** Deterministic M12 planner heuristic; not an actual or expected server score. */
public record CommitmentAdjustedCollectionScore(int value) {

    public CommitmentAdjustedCollectionScore {
        if (value < 0) {
            throw new IllegalArgumentException("Commitment-adjusted collection score must be non-negative");
        }
    }
}
