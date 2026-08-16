package vn.ptit.procon.planner;

/** Deterministic planner heuristic; this is not a probability or server score. */
public record ContentionAdjustedCollectionScore(int value) {

    public ContentionAdjustedCollectionScore {
        if (value < 0) {
            throw new IllegalArgumentException("Contention-adjusted collection score must be non-negative");
        }
    }

    public static ContentionAdjustedCollectionScore from(
            ArrivalAttribution attribution, RiskAdjustmentWeights weights) {
        return new ContentionAdjustedCollectionScore(weights.score(attribution));
    }
}