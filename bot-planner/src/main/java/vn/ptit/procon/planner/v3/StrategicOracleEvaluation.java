package vn.ptit.procon.planner.v3;

import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.HybridCalibratedMarginEvaluation;

/** Existing authoritative objective values captured for one valid oracle plan. */
public record StrategicOracleEvaluation(TeamPlan plan, String physicalSignature,
        HybridCalibratedMarginEvaluation objective) {
    public StrategicOracleEvaluation {
        if (plan == null || physicalSignature == null || objective == null) {
            throw new IllegalArgumentException("Oracle evaluation fields must not be null");
        }
    }

    public int ownSemiBrands() { return objective.ownSemiBrands(); }
    public int ownSemiCollections() { return objective.ownSemiCollections(); }
    public int coupledOwnBrands() { return objective.coupledOwnBrands(); }
    public int coupledOwnCollections() { return objective.coupledOwnCollections(); }
    public int baselineOpponentCollections() { return objective.opponentBaselineCollections(); }
    public int coupledOpponentCollections() { return objective.coupledOpponentCollections(); }
    public int hybridMarginScore4() { return objective.hybridMarginScore4(); }
}
