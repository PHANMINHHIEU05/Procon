package vn.ptit.procon.planner.v3;

import java.util.Objects;
import vn.ptit.procon.engine.TeamPlan;

/** A materialized terminal retained for offline parity and ablation audits. */
public record StrategicTerminalSnapshot(TeamPlan plan, StrategicSearchState state,
        StrategicOracleEvaluation evaluation) {
    public StrategicTerminalSnapshot {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(state);
        Objects.requireNonNull(evaluation);
    }
}
