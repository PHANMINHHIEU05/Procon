package vn.ptit.procon.planner.v2;

import java.util.List;

/** Simulator-derived evidence for whether a selected support prefix unlocked later collections. */
public record R3SupportProductivity(
        int serviceCount,
        List<Integer> supportedPatrols,
        int collectionsBeforeFirstService,
        int collectionsDuringSupportPrefix,
        int collectionsAfterLastService,
        int totalSupportedPatrolCollections,
        int supportMovementSteps,
        int supportWaitSteps,
        String supportSkeletonSignature,
        List<PatrolProductivity> patrols) {
    public R3SupportProductivity {
        supportedPatrols = List.copyOf(supportedPatrols);
        patrols = List.copyOf(patrols);
    }

    public record PatrolProductivity(int patrolId, int collectionsBeforeService, int collectionsAfterService) {
    }
}
