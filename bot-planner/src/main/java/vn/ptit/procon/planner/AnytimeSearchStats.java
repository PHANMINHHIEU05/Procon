package vn.ptit.procon.planner;

/** Immutable deterministic work accounting for one anytime search. */
public record AnytimeSearchStats(
        int expandedStates,
        int generatedStates,
        int prunedStates,
        int completedPlans,
        int incumbentImprovements,
        int coveragePhaseExpandedStates,
        int harvestPhaseExpandedStates,
        int candidateGenerated,
        int candidateRetained,
        int candidatePrunedByTopK,
        int duplicateStates,
        int frontierPrunedStates,
        boolean budgetExhausted) {

    public AnytimeSearchStats {
        if (expandedStates < 0 || generatedStates < 0 || prunedStates < 0
                || completedPlans < 0 || incumbentImprovements < 0
                || coveragePhaseExpandedStates < 0 || harvestPhaseExpandedStates < 0
                || candidateGenerated < 0 || candidateRetained < 0
                || candidatePrunedByTopK < 0 || duplicateStates < 0
                || frontierPrunedStates < 0) {
            throw new IllegalArgumentException("Search statistics must be non-negative");
        }
    }
}
