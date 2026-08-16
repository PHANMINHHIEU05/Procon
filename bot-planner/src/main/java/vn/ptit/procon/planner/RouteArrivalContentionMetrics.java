package vn.ptit.procon.planner;

/** Route-wide unique branch-local arrival and static contention metrics. */
public record RouteArrivalContentionMetrics(
        int projectedCollectionGain,
        int arrivalSafeCollections,
        int arrivalTiedCollections,
        int arrivalAtRiskCollections,
        int unobservedCollections,
        int staticSafeCollections,
        int staticTiedCollections,
        int staticContestedCollections,
        int stronglyStaticContestedCollections) {

    public RouteArrivalContentionMetrics {
        if (projectedCollectionGain < 0
                || arrivalSafeCollections < 0 || arrivalTiedCollections < 0 || arrivalAtRiskCollections < 0
                || unobservedCollections < 0 || unobservedCollections > arrivalSafeCollections
                || staticSafeCollections < 0 || staticTiedCollections < 0 || staticContestedCollections < 0
                || stronglyStaticContestedCollections < 0
                || arrivalSafeCollections + arrivalTiedCollections + arrivalAtRiskCollections != projectedCollectionGain
                || staticSafeCollections + staticTiedCollections + staticContestedCollections != projectedCollectionGain
                || stronglyStaticContestedCollections > staticContestedCollections) {
            throw new IllegalArgumentException("Route arrival contention metrics are inconsistent");
        }
    }

    public RouteArrivalContentionMetrics(
            int projectedCollectionGain,
            int arrivalSafeCollections,
            int arrivalTiedCollections,
            int arrivalAtRiskCollections,
            int staticSafeCollections,
            int staticTiedCollections,
            int staticContestedCollections,
            int stronglyStaticContestedCollections) {
        this(projectedCollectionGain, arrivalSafeCollections, arrivalTiedCollections,
                arrivalAtRiskCollections, 0, staticSafeCollections, staticTiedCollections,
                staticContestedCollections, stronglyStaticContestedCollections);
    }

    public int observedArrivalSafeCollections() {
        return arrivalSafeCollections - unobservedCollections;
    }
}
