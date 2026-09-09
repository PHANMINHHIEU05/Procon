package vn.ptit.procon.planner.v2;

import vn.ptit.procon.engine.DayState;

/** Observable, protocol-independent match dimensions used by the adaptive selector. */
public record MatchShape(
        int width,
        int height,
        int stepBudget,
        int agentCount,
        int opponentGroups) {

    public MatchShape {
        if (width <= 0 || height <= 0 || stepBudget <= 0 || agentCount <= 0 || opponentGroups < 0) {
            throw new IllegalArgumentException("Invalid observable match shape");
        }
    }

    public static MatchShape from(DayState state) {
        java.util.Objects.requireNonNull(state, "Day state must not be null");
        return new MatchShape(
                state.matchData().map().width(),
                state.matchData().map().height(),
                state.stepBudget(),
                state.agents().size(),
                state.observedOthers().size());
    }

    public R3MapTier tier() { return R3MapTier.forDimensions(width, height); }
}
