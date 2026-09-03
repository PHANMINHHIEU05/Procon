package vn.ptit.procon.planner.v2;

import java.util.List;

/** R3-only accounting; wall time is intentionally separate from accumulated evaluator work. */
public record R3PlanningStats(
        int partialToursGenerated, int partialToursPruned, int skeletonsConsidered,
        int skeletonsValidated, int skeletonsValid, int skeletonsRetained,
        int rootCandidates, int rootsInitiallyAdmitted, int rootsSurvivingAfterMerge,
        int stageATerminals, int stageBRequested, int stageBEvaluated, int stageBSkippedForDeadline,
        int tourCatalogPathfindingExecutions, int tourSearchPathfindingExecutions,
        int beamSearchPathfindingExecutions, boolean planningDeadlineTriggered, String deadlinePhase,
        String incumbentSource, boolean fallbackUsed, long wallPlanningMillis,
        long terminalEvaluationWallMillis, long terminalEvaluationAccumulatedMillis,
        int selectedSupportServiceCount, String selectedSupportSkeletonSignature,
        List<Integer> selectedSupportedPatrols, R3SupportFamilyPipeline supportFamilyPipeline) {
    public R3PlanningStats {
        deadlinePhase = deadlinePhase == null ? "NONE" : deadlinePhase;
        incumbentSource = incumbentSource == null ? "NONE" : incumbentSource;
        selectedSupportSkeletonSignature = selectedSupportSkeletonSignature == null
                ? "NO_REFUEL" : selectedSupportSkeletonSignature;
        selectedSupportedPatrols = List.copyOf(selectedSupportedPatrols);
        supportFamilyPipeline = supportFamilyPipeline == null
                ? R3SupportFamilyPipeline.empty() : supportFamilyPipeline;
        if (selectedSupportServiceCount < 0) {
            throw new IllegalArgumentException("Selected support service count must be non-negative");
        }
    }

    public R3PlanningStats(int partialToursGenerated, int partialToursPruned, int skeletonsConsidered,
            int skeletonsValidated, int skeletonsValid, int skeletonsRetained,
            int rootCandidates, int rootsInitiallyAdmitted, int rootsSurvivingAfterMerge,
            int stageATerminals, int stageBRequested, int stageBEvaluated, int stageBSkippedForDeadline,
            int tourCatalogPathfindingExecutions, int tourSearchPathfindingExecutions,
            int beamSearchPathfindingExecutions, boolean planningDeadlineTriggered, String deadlinePhase,
            String incumbentSource, boolean fallbackUsed, long wallPlanningMillis,
            long terminalEvaluationWallMillis, long terminalEvaluationAccumulatedMillis,
            int selectedSupportServiceCount, String selectedSupportSkeletonSignature,
            List<Integer> selectedSupportedPatrols) {
        this(partialToursGenerated, partialToursPruned, skeletonsConsidered, skeletonsValidated,
                skeletonsValid, skeletonsRetained, rootCandidates, rootsInitiallyAdmitted,
                rootsSurvivingAfterMerge, stageATerminals, stageBRequested, stageBEvaluated,
                stageBSkippedForDeadline, tourCatalogPathfindingExecutions, tourSearchPathfindingExecutions,
                beamSearchPathfindingExecutions, planningDeadlineTriggered, deadlinePhase, incumbentSource,
                fallbackUsed, wallPlanningMillis, terminalEvaluationWallMillis,
                terminalEvaluationAccumulatedMillis, selectedSupportServiceCount,
                selectedSupportSkeletonSignature, selectedSupportedPatrols,
                R3SupportFamilyPipeline.empty());
    }
}
