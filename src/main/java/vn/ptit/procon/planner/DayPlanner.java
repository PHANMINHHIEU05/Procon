package vn.ptit.procon.planner;

import vn.ptit.procon.model.Model;

public interface DayPlanner {
    Model.PlannedDay plan(MatchContext context, Model.DayState state, Deadline deadline);
}
