package vn.ptit.procon.planner.v2;

import java.util.List;
import java.util.Optional;

/** Bounded live-only shadow evidence. It is never consulted for normal R3 selection. */
public record R3LiveFamilyAudit(
        List<R3LiveFamilyAuditFamily> families,
        int shadowEvaluationsPerformed,
        int shadowEvaluationsSkippedForDeadline,
        Optional<Integer> bestShadowFamilyByFrozenObjective,
        Optional<Boolean> selectedMatchesBestShadowFamily) {

    public R3LiveFamilyAudit {
        families = List.copyOf(families);
        bestShadowFamilyByFrozenObjective = bestShadowFamilyByFrozenObjective == null
                ? Optional.empty() : bestShadowFamilyByFrozenObjective;
        selectedMatchesBestShadowFamily = selectedMatchesBestShadowFamily == null
                ? Optional.empty() : selectedMatchesBestShadowFamily;
        if (shadowEvaluationsPerformed < 0 || shadowEvaluationsPerformed > 4
                || shadowEvaluationsSkippedForDeadline < 0) {
            throw new IllegalArgumentException("Invalid bounded live shadow audit accounting");
        }
    }
}
