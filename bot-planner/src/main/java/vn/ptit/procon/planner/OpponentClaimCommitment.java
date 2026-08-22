package vn.ptit.procon.planner;

/**
 * Discrete M12 commitment class of one accepted opponent forecast claim.
 *
 * <p>This is a structural ordinal tier, not a probability and not a learned opponent model. The
 * class is decided by the order of the claims a forecast actually produced for one opponent agent,
 * never by the {@link IntentRank} of the intent target behind it: a PRIMARY target that never
 * becomes a claim carries no commitment at all.</p>
 *
 * <p>Only {@link #OBSERVED_NOW} is strong enough to delete stock from the hard-realizable forecast.
 * The two future classes still influence scoring and ordering, because a hypothetical later route
 * choice is evidence of pressure but not evidence of a committed future action.</p>
 */
public enum OpponentClaimCommitment {

    /**
     * The opponent agent is already standing on the stocked spot, so the claim arrival step is 0.
     * Strongest structural evidence available without inventing opponent intent.
     */
    OBSERVED_NOW,

    /**
     * First future claim the forecast produced for this agent, with arrival step above 0. The
     * agent has to travel for it, so it is a plan we inferred rather than a state we observed.
     */
    DIRECT_INTENT,

    /**
     * Second or later future claim for the same agent. It only happens after every earlier claim
     * of that agent already worked out, so it is the weakest structural evidence M12 retains.
     */
    FOLLOW_ON_INTENT
}
