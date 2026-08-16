package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/** M9.3 bounded planner using contention-adjusted collection value. */
public final class RiskAdjustedAnytimePlanner implements DayPlanner {

    private final AnytimeTeamPlanner engine;

    public RiskAdjustedAnytimePlanner() {
        this(AnytimePlannerConfig.defaults(), RiskAdjustmentWeights.defaults(), false);
    }

    public RiskAdjustedAnytimePlanner(AnytimePlannerConfig config) {
        this(config, RiskAdjustmentWeights.defaults(), false);
    }

    public RiskAdjustedAnytimePlanner(
            AnytimePlannerConfig config, RiskAdjustmentWeights weights) {
        this(config, weights, false);
    }

    public RiskAdjustedAnytimePlanner(
            AnytimePlannerConfig config,
            RiskAdjustmentWeights weights,
            boolean contentionDiagnostics) {
        Objects.requireNonNull(config, "Anytime configuration must not be null");
        Objects.requireNonNull(weights, "Risk adjustment weights must not be null");
        this.engine = new AnytimeTeamPlanner(
                config,
                AnytimeSearchPolicy.ANYTIME_RISK_ADJUSTED,
                weights,
                contentionDiagnostics);
    }

    @Override
    public TeamPlan plan(DayState state) {
        return planWithStats(state).plan();
    }

    public AnytimePlanResult planWithStats(DayState state) {
        return engine.planWithStats(Objects.requireNonNull(state, "Day state must not be null"));
    }
}