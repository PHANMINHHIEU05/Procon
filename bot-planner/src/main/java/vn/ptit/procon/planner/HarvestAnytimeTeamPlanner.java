package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/** M8.1 harvest-guided mode backed by the shared bounded M8 search engine. */
public final class HarvestAnytimeTeamPlanner implements DayPlanner {

    private final AnytimeTeamPlanner engine;

    public HarvestAnytimeTeamPlanner() {
        this(AnytimePlannerConfig.defaults());
    }

    public HarvestAnytimeTeamPlanner(AnytimePlannerConfig config) {
        this.engine = new AnytimeTeamPlanner(
                Objects.requireNonNull(config, "Anytime configuration must not be null"),
                AnytimeSearchPolicy.HARVEST);
    }

    @Override
    public TeamPlan plan(DayState state) {
        return engine.plan(state);
    }

    public AnytimePlanResult planWithStats(DayState state) {
        return engine.planWithStats(state);
    }
}
