package vn.ptit.procon.planner.v2;

/** Immutable work and audit counters for one Planner V2 call.
 *
 * <p>{@code planningMillis}, {@code catalogMillis}, {@code searchMillis}, and
 * {@code stageBTerminalWallMillis} are wall-clock measures. Stage-A evaluation happens while
 * states are admitted, so it is deliberately accounted as accumulated work rather than made to
 * look like a separate wall-clock phase.</p>
 */
public record JointTeamBeamStats(
        int rootStates,
        int expandedStates,
        int generatedChildren,
        int collectChildrenGenerated,
        int stopChildrenGenerated,
        int rootScheduleStates,
        int uniqueStates,
        int duplicateStatesRejected,
        int dominatedStatesRejected,
        int terminalStates,
        int continuingTerminalCandidates,
        int fullyStoppedTerminalCandidates,
        int stageATerminalEvaluations,
        int terminalPlansEvaluated,
        int coupledTerminalEvaluations,
        int stageBRequested,
        int stageBDuplicateCandidatesSkipped,
        int maxDepthReached,
        int frontierPeak,
        int catalogPathfindingExecutions,
        int searchPathfindingExecutions,
        long catalogMillis,
        long searchMillis,
        long stageBTerminalWallMillis,
        long stageATerminalAccumulatedMillis,
        long stageBTerminalAccumulatedMillis,
        long terminalEvaluationMillis,
        long planningMillis,
        boolean planningDeadlineTriggered,
        String deadlinePhase,
        boolean budgetExhausted) {

    public JointTeamBeamStats {
        if (rootStates < 0 || expandedStates < 0 || generatedChildren < 0
                || collectChildrenGenerated < 0 || stopChildrenGenerated < 0 || rootScheduleStates < 0
                || uniqueStates < 0
                || duplicateStatesRejected < 0 || dominatedStatesRejected < 0 || terminalStates < 0
                || continuingTerminalCandidates < 0 || fullyStoppedTerminalCandidates < 0
                || stageATerminalEvaluations < 0 || terminalPlansEvaluated < 0
                || coupledTerminalEvaluations < 0 || stageBRequested < 0
                || stageBDuplicateCandidatesSkipped < 0 || maxDepthReached < 0
                || frontierPeak < 0 || catalogPathfindingExecutions < 0
                || searchPathfindingExecutions < 0 || catalogMillis < 0 || searchMillis < 0
                || stageBTerminalWallMillis < 0 || stageATerminalAccumulatedMillis < 0
                || stageBTerminalAccumulatedMillis < 0 || terminalEvaluationMillis < 0
                || planningMillis < 0) {
            throw new IllegalArgumentException("Joint beam statistics must be non-negative");
        }
        if (searchPathfindingExecutions != 0) {
            throw new IllegalArgumentException("Joint beam search must not execute pathfinding");
        }
        deadlinePhase = deadlinePhase == null ? "NONE" : deadlinePhase;
    }
}
