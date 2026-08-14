package vn.ptit.procon.planner;

import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;

/** The proven deterministic all-WAIT strategy retained from M3. */
public final class WaitDayPlanner implements DayPlanner {

    @Override
    public TeamPlan plan(DayState state) {
        return SafePlanFactory.waitAll(state);
    }
}