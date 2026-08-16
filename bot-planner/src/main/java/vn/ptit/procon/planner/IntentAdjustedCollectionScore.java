package vn.ptit.procon.planner;

/** Deterministic M10 planner heuristic; not an actual or expected server score. */
public record IntentAdjustedCollectionScore(int value) {

    public IntentAdjustedCollectionScore {
        if (value < 0) {
            throw new IllegalArgumentException("Intent-adjusted collection score must be non-negative");
        }
    }
}