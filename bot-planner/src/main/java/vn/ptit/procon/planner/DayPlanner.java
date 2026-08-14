package vn.ptit.procon.planner;

import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/** Protocol-independent strategy for producing one complete day plan. */
@FunctionalInterface
public interface DayPlanner {

    TeamPlan plan(DayState state);
}