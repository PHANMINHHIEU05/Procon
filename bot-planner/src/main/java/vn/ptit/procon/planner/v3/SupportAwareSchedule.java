package vn.ptit.procon.planner.v3;

import java.util.Objects;

/**
 * When ONE strategic leg can actually be executed, given the PATROL's current timeline and the fixed
 * authoritative REFUEL trajectory of the selected support root.
 *
 * <p>PART 9: this record is the place where the two questions that used to be conflated are kept
 * apart. {@code ROUTE GEOMETRY EXISTS} is answered by the graph (a cached route is present at all).
 * {@code ROUTE IS CURRENTLY FUEL/TIME FEASIBLE} is answered here, and only here, against the actual
 * fuel the PATROL will hold at every step it is about to move.
 *
 * <p>PART 13: {@code insertedWaitDuration} is a derived quantity, never a searched one. It is the
 * deterministic amount of waiting that reaches the earliest legal execution of this leg.
 */
public record SupportAwareSchedule(boolean feasible, int scheduledStartStep, int scheduledEndStep,
        int insertedWaitDuration, int fuelBefore, int fuelAfter, int supportEventsUsed, String reason) {

    public static final String OK = "OK";

    public SupportAwareSchedule {
        Objects.requireNonNull(reason, "Schedule reason must not be null");
        if (feasible && (scheduledStartStep < 0 || scheduledEndStep < scheduledStartStep
                || insertedWaitDuration < 0)) {
            throw new IllegalArgumentException("Feasible schedule must be monotonic");
        }
    }

    /** A leg that cannot be executed at any deterministically derived start time. */
    public static SupportAwareSchedule infeasible(String reason) {
        return new SupportAwareSchedule(false, 0, 0, 0, 0, 0, 0, reason);
    }

    /** PART 12: waiting is only ever inserted when a support event makes the leg legal later. */
    public boolean waited() { return insertedWaitDuration > 0; }

    /** PART 14: true when the leg is only legal because the tanker meets the PATROL mid-route. */
    public boolean usedSupport() { return supportEventsUsed > 0; }

    @Override
    public String toString() {
        return feasible
                ? "start=" + scheduledStartStep + " end=" + scheduledEndStep + " wait=" + insertedWaitDuration
                        + " fuel=" + fuelBefore + "->" + fuelAfter + " supportEvents=" + supportEventsUsed
                : "INFEASIBLE(" + reason + ")";
    }
}
