package vn.ptit.procon.planner.v2;

/** Package-visible, deterministic metrics used by the V2 beam ordering and diagnostics. */
record JointPartialStateMetrics(
        int securedCollections,
        int remainingCollectionPotentialUpperBound,
        int securedBrands,
        int reachableMissingBrands,
        int residualCollectionPotential,
        int usableStepCapacity,
        int fuelFeasiblePatrolCount,
        int fuelFeasibleFuel,
        int stoppedUsefulPatrols,
        int zeroGainCommittedLegs) {

    int securedPlusPotential() {
        return securedCollections + remainingCollectionPotentialUpperBound;
    }

    int brandsPlusReachableMissing() {
        return securedBrands + reachableMissingBrands;
    }
}
