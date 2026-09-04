package vn.ptit.procon.runtime;

/**
 * How one day's shadow evaluation ended. There is no {@code PROMOTED} value and there never will be:
 * every value here describes an observation, not an authority.
 */
public enum V3ShadowStatus {

    /** V3 finished inside its own wall budget and its figures are recorded. */
    COMPLETED,

    /** V3 exceeded the shadow wall budget. The partial candidate is discarded, never submitted. */
    TIMEOUT,

    /** V3 threw. The exception was caught at the shadow boundary and never reached the action loop. */
    ERROR,

    /** A previous shadow task was still running, so this day was dropped instead of queued. */
    DROPPED
}
