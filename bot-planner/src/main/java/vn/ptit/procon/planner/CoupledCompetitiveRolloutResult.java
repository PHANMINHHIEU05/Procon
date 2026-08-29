package vn.ptit.procon.planner;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.udon.BrandId;

/**
 * M16 result of ONE coupled competitive rollout of ONE complete own plan against the shared timeline.
 *
 * <p>Both sides draw from the same mutable stock. Our action routes are fixed, so
 * {@link #plannedOwnOpportunityEvents()} is the same list whatever the opponent does; the opponent
 * may reroute freely, so it can empty a spot before one of our later planned arrivals and turn that
 * arrival into a zero collection that removes no stock. That asymmetry — our events fixed but our
 * collections not guaranteed — is the correction M15 was missing.</p>
 *
 * <p>No count here is weighted or fitted. {@link #projectedCoupledMargin()} is a plain difference of
 * two realized collection counts on one timeline.</p>
 */
public record CoupledCompetitiveRolloutResult(
        CoupledCompetitiveBaseline baseline,
        List<PlannedOwnOpportunityEvent> plannedOwnOpportunityEvents,
        List<CoupledOwnEventResult> ownEventResults,
        List<OpponentFullDayClaim> coupledOpponentClaims,
        int coupledOwnCollections,
        Set<BrandId> coupledOwnBrandSet,
        int ownPlannedEventsInvalidatedByOpponent,
        int ownPlannedEventsExhaustedByOwnTeam,
        int opponentReplacementCollections,
        int coupledObservedNow,
        int coupledDirectIntent,
        int coupledFollowOnIntent,
        int equalStepContests,
        int rolloutEvents,
        int maxCollectorCollections) {

    public CoupledCompetitiveRolloutResult {
        Objects.requireNonNull(baseline, "Coupled baseline must not be null");
        plannedOwnOpportunityEvents = List.copyOf(Objects.requireNonNull(
                plannedOwnOpportunityEvents, "Planned own opportunity events must not be null"));
        ownEventResults = List.copyOf(Objects.requireNonNull(
                ownEventResults, "Coupled own event results must not be null"));
        coupledOpponentClaims = List.copyOf(Objects.requireNonNull(
                coupledOpponentClaims, "Coupled opponent claims must not be null"));
        coupledOwnBrandSet = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(coupledOwnBrandSet, "Coupled own brands must not be null")));
        if (coupledOwnCollections < 0 || ownPlannedEventsInvalidatedByOpponent < 0
                || ownPlannedEventsExhaustedByOwnTeam < 0 || opponentReplacementCollections < 0
                || coupledObservedNow < 0 || coupledDirectIntent < 0 || coupledFollowOnIntent < 0
                || equalStepContests < 0 || rolloutEvents < 0 || maxCollectorCollections < 0) {
            throw new IllegalArgumentException("Coupled rollout metrics must be non-negative");
        }
        if (ownEventResults.size() != plannedOwnOpportunityEvents.size()) {
            throw new IllegalArgumentException(
                    "Every planned own opportunity event must have exactly one coupled outcome");
        }
        if (coupledOwnCollections + ownPlannedEventsInvalidatedByOpponent
                + ownPlannedEventsExhaustedByOwnTeam != plannedOwnOpportunityEvents.size()) {
            throw new IllegalArgumentException(
                    "Coupled own outcomes must partition the planned own opportunity events");
        }
        if (coupledObservedNow + coupledDirectIntent + coupledFollowOnIntent
                != coupledOpponentClaims.size()) {
            throw new IllegalArgumentException(
                    "Coupled commitment counts must cover every coupled opponent collection");
        }
        if (opponentReplacementCollections > coupledOpponentClaims.size()) {
            throw new IllegalArgumentException(
                    "Replacement collections cannot exceed the coupled collections that hold them");
        }
        if (coupledOwnBrandSet.size() > coupledOwnCollections) {
            throw new IllegalArgumentException(
                    "Coupled own brands cannot exceed the coupled own collections that produced them");
        }
        if (maxCollectorCollections > coupledOpponentClaims.size()) {
            throw new IllegalArgumentException(
                    "One collector cannot hold more collections than the whole opponent team");
        }
    }

    /** Distinct brands our team really collects on the coupled timeline. */
    public int coupledOwnBrands() {
        return coupledOwnBrandSet.size();
    }

    public int opponentBaselineCollections() {
        return baseline.totalCollections();
    }

    public int coupledOpponentCollections() {
        return coupledOpponentClaims.size();
    }

    /**
     * Signed effect of our plan on the whole opponent team over the current day.
     *
     * <p>Positive means the opponent really lost collections. Zero means it fully replaced whatever we
     * took first. Negative means our interference handed it a cheaper route.</p>
     */
    public int opponentCollectionsRemovedVsBaseline() {
        return Math.subtractExact(opponentBaselineCollections(), coupledOpponentCollections());
    }

    /** Plain unweighted difference of the two realized collection counts on one shared timeline. */
    public int projectedCoupledMargin() {
        return Math.subtractExact(coupledOwnCollections, coupledOpponentCollections());
    }

    /** Diagnostics only: M12 commitment continuity over the coupled rollout. */
    public int coupledStrongCollections() {
        return coupledObservedNow + coupledDirectIntent;
    }
}
