package vn.ptit.procon.runtime;

import java.util.Objects;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;

/**
 * The mandated {@code V3_SHADOW_COMPARISON} row: V2/R3 against V3 on the SAME day state.
 *
 * <p>This is a planner-versus-planner reading and nothing else. A hybrid delta of {@code +4} here does
 * NOT predict a final match score four points higher: the two planners were asked the same one-day
 * question under the same frozen objective, and that is the whole claim.
 */
public record V3ShadowComparison(int day, int v2OwnSemi, int v2Brands, int v2CoupledOwn,
        int v2BaselineOpponent, int v2CoupledOpponent, int v2Hybrid4, int rawV3OwnSemi, int rawV3Brands,
        int rawV3CoupledOwn, int rawV3BaselineOpponent, int rawV3CoupledOpponent, int rawV3Hybrid4,
        int ownDelta, int hybridDelta4, boolean samePhysicalPlan, String v2SupportRoot,
        String v3SupportRoot, String v2PhysicalSignature, String v3PhysicalSignature,
        long v3PlanningMillis, V3ShadowStatus status) {

    public V3ShadowComparison {
        Objects.requireNonNull(v2SupportRoot, "V2 support root must not be null");
        Objects.requireNonNull(v3SupportRoot, "V3 support root must not be null");
        Objects.requireNonNull(v2PhysicalSignature, "V2 physical signature must not be null");
        Objects.requireNonNull(v3PhysicalSignature, "V3 physical signature must not be null");
        Objects.requireNonNull(status, "Status must not be null");
    }

    /** Builds the row from a completed evaluation. Non-completed days have nothing to compare. */
    public static V3ShadowComparison of(V3ShadowEvaluation evaluation, V3ShadowStatus status) {
        Objects.requireNonNull(evaluation, "Evaluation must not be null");
        return new V3ShadowComparison(evaluation.day(), evaluation.v2OwnSemi(), evaluation.v2Brands(),
                evaluation.v2CoupledOwn(), evaluation.v2BaselineOpponent(),
                evaluation.v2CoupledOpponent(), evaluation.v2Hybrid4(), evaluation.rawV3OwnSemi(),
                evaluation.rawV3Brands(), evaluation.rawV3CoupledOwn(),
                evaluation.rawV3BaselineOpponent(), evaluation.rawV3CoupledOpponent(),
                evaluation.rawV3Hybrid4(), evaluation.ownDelta(), evaluation.hybridDelta4(),
                evaluation.samePhysicalPlan(), evaluation.v2SupportRoot(), evaluation.v3SupportRoot(),
                evaluation.v2PhysicalSignature(), evaluation.v3PhysicalSignature(),
                evaluation.planningMillis(), status);
    }

    @Override
    public String toString() {
        return "V3_SHADOW_COMPARISON day=" + day + " v2=" + v2OwnSemi + "/" + v2Hybrid4 + " rawV3="
                + rawV3OwnSemi + "/" + rawV3Hybrid4 + " ownDelta=" + ownDelta + " hybridDelta4="
                + hybridDelta4 + " samePhysical=" + samePhysicalPlan + " status=" + status;
    }
}
