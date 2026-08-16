package vn.ptit.procon.planner;

/** Aggregate current-day classification of configured Udon spots. */
public record SpotContentionSummary(
        int spotsConsidered,
        int safeSpots,
        int tiedSpots,
        int contestedSpots,
        int unobservedSpots) {

    public SpotContentionSummary {
        if (spotsConsidered < 0 || safeSpots < 0 || tiedSpots < 0
                || contestedSpots < 0 || unobservedSpots < 0
                || safeSpots + tiedSpots + contestedSpots + unobservedSpots != spotsConsidered) {
            throw new IllegalArgumentException("Spot contention summary is inconsistent");
        }
    }

}