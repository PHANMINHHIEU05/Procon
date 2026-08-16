package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/** M9.2 arrival-aware contention guidance backed by the shared bounded anytime engine. */
public final class ArrivalContentionAnytimePlanner implements DayPlanner {

    private final AnytimeTeamPlanner engine;
    private final HarvestAnytimeTeamPlanner noOpponentFallback;

    public ArrivalContentionAnytimePlanner() {
        this(AnytimePlannerConfig.defaults(), false);
    }

    public ArrivalContentionAnytimePlanner(AnytimePlannerConfig config) {
        this(config, false);
    }

    public ArrivalContentionAnytimePlanner(
            AnytimePlannerConfig config, boolean contentionDiagnostics) {
        Objects.requireNonNull(config, "Anytime configuration must not be null");
        this.engine = new AnytimeTeamPlanner(
                config, AnytimeSearchPolicy.ANYTIME_ARRIVAL_CONTENTION, contentionDiagnostics);
        this.noOpponentFallback = new HarvestAnytimeTeamPlanner(config);
    }

    @Override
    public TeamPlan plan(DayState state) {
        return planWithStats(state).plan();
    }

    public AnytimePlanResult planWithStats(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        boolean hasObservedAgents = state.observedOthers().stream()
                .anyMatch(group -> !group.agents().isEmpty());
        return !hasObservedAgents
                ? noOpponentFallback.planWithStats(state)
                : engine.planWithStats(state);
    }
}
