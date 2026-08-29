package vn.ptit.procon.planner;

/**
 * M16 outcome of one {@link PlannedOwnOpportunityEvent} on the shared coupled stock timeline.
 *
 * <p>The distinction between the two failure kinds is the whole point of M16. M15 froze every
 * M12.1 semi-realizable own collection as a guaranteed stock removal and then let the opponent
 * reroute around it, so an own event the opponent had actually beaten still consumed stock. Here a
 * failure is classified against an own-only replay of the very same plan, which isolates exactly the
 * events an adaptive opponent took away from us.</p>
 */
public enum CoupledOwnEventOutcome {

    /** Stock was still available when our PATROL physically arrived, so we collected. */
    COLLECTED,

    /**
     * The spot was already empty in an own-only replay of this same plan.
     *
     * <p>Our own team over-subscribed the spot; no opponent behaviour is involved and this is not a
     * denial. The day simulator produces the same zero collection for this arrival.</p>
     */
    EXHAUSTED_BY_OWN_TEAM,

    /**
     * Our own-only replay collected here, but on the coupled timeline the opponent got there first.
     *
     * <p>The event still happens physically — the PATROL really stands on the spot — but it collects
     * nothing and, critically, removes no stock afterwards.</p>
     */
    INVALIDATED_BY_OPPONENT
}
