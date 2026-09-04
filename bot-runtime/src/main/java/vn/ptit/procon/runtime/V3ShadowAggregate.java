package vn.ptit.procon.runtime;

import java.util.Objects;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;

/**
 * The match-level shadow tally. Written by the action loop (eligibility, drops) and by the shadow worker
 * (completions), read at match end, so every mutator is {@code synchronized}.
 *
 * <p>Win, tie and loss come from the frozen terminal comparator inside
 * {@link V3ShadowEvaluation#rawVerdict()}; nothing here compares quantities.
 */
public final class V3ShadowAggregate {

    private int daysEligible;
    private int daysStarted;
    private int daysCompleted;
    private int daysDropped;
    private int daysTimedOut;
    private int daysFailed;
    private int rawV3Wins;
    private int rawV3Ties;
    private int rawV3Losses;
    private int safeV3Wins;
    private int safeV3Ties;
    private int safeV3Losses;
    private long totalV3Millis;

    public synchronized void recordEligible() { daysEligible++; }

    public synchronized void recordStarted() { daysStarted++; }

    public synchronized void recordDropped() { daysDropped++; }

    /** Folds one finished observation in, whatever its status. */
    public synchronized void record(V3ShadowResult result) {
        Objects.requireNonNull(result, "Result must not be null");
        totalV3Millis += result.planningMillis();
        switch (result.status()) {
            case COMPLETED -> {
                daysCompleted++;
                V3ShadowEvaluation evaluation = result.evaluation().orElseThrow();
                switch (evaluation.rawVerdict()) {
                    case WIN -> rawV3Wins++;
                    case TIE -> rawV3Ties++;
                    case LOSS -> rawV3Losses++;
                }
                switch (evaluation.safeVerdict()) {
                    case WIN -> safeV3Wins++;
                    case TIE -> safeV3Ties++;
                    case LOSS -> safeV3Losses++;
                }
            }
            case TIMEOUT -> daysTimedOut++;
            case ERROR -> daysFailed++;
            case DROPPED -> { }
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(daysEligible, daysStarted, daysCompleted, daysDropped, daysTimedOut,
                daysFailed, rawV3Wins, rawV3Ties, rawV3Losses, safeV3Wins, safeV3Ties, safeV3Losses,
                totalV3Millis);
    }

    /** The immutable {@code V3_SHADOW_MATCH_SUMMARY} payload. */
    public record Snapshot(int daysEligible, int daysStarted, int daysCompleted, int daysDropped,
            int daysTimedOut, int daysFailed, int rawV3Wins, int rawV3Ties, int rawV3Losses,
            int safeV3Wins, int safeV3Ties, int safeV3Losses, long totalV3Millis) {

        /** Every eligible day is accounted for exactly once: started days plus dropped days. */
        public boolean accountedFor() { return daysStarted + daysDropped == daysEligible; }

        /** Every started day ended in exactly one terminal status. */
        public boolean settled() {
            return daysCompleted + daysTimedOut + daysFailed == daysStarted;
        }

        @Override
        public String toString() {
            return "eligible=" + daysEligible + " started=" + daysStarted + " completed=" + daysCompleted
                    + " dropped=" + daysDropped + " timedOut=" + daysTimedOut + " failed=" + daysFailed
                    + " raw=" + rawV3Wins + "W/" + rawV3Ties + "T/" + rawV3Losses + "L safe="
                    + safeV3Wins + "W/" + safeV3Ties + "T/" + safeV3Losses + "L v3ShadowMillis="
                    + totalV3Millis;
        }
    }
}
