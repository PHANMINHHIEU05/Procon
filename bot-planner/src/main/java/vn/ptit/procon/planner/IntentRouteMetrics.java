package vn.ptit.procon.planner;

/** Branch-local M10 route attribution used for candidate and partial-state guidance. */
record IntentRouteMetrics(
        int projectedCollectionGain,
        int adjustedScore,
        int forecastRealizableCollections,
        int likelyClaimedFirstCollections,
        int tieCollections,
        int unforecastedCollections) {

    static IntentRouteMetrics empty() {
        return new IntentRouteMetrics(0, 0, 0, 0, 0, 0);
    }
}