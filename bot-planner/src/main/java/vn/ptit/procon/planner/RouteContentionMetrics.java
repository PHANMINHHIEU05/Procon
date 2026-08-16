package vn.ptit.procon.planner;

/** Route-wide unique, branch-local projected collection contention. */
public record RouteContentionMetrics(
        int projectedCollectionGain,
        int safeProjectedCollections,
        int tiedProjectedCollections,
        int contestedProjectedCollections,
        int stronglyContestedCollections) {

    public RouteContentionMetrics {
        if (projectedCollectionGain < 0 || safeProjectedCollections < 0
                || tiedProjectedCollections < 0 || contestedProjectedCollections < 0
                || stronglyContestedCollections < 0
                || safeProjectedCollections + tiedProjectedCollections
                        + contestedProjectedCollections != projectedCollectionGain
                || stronglyContestedCollections > contestedProjectedCollections) {
            throw new IllegalArgumentException("Route contention metrics are inconsistent");
        }
    }
}