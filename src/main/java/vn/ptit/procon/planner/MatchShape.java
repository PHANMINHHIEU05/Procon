package vn.ptit.procon.planner;

import vn.ptit.procon.model.Model.Setup;

/** Immutable match dimensions used to choose bounded search budgets. */
public record MatchShape(int width, int height, int agents, int days, int opponents) {
    public static MatchShape of(Setup setup) { return new MatchShape(setup.map().width(), setup.map().height(), setup.agentCount(), setup.dayCount(), 1); }
}
