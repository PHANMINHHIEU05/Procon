package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.domain.udon.BrandId;

/**
 * M16 resolution of one planned own opportunity event against the shared coupled stock timeline.
 *
 * <p>{@code equalStepContest} records that at least one opponent collector arrived at the very same
 * spot on the very same step. It is diagnostics only: the tie policy is fixed and never depends on
 * this flag.</p>
 */
public record CoupledOwnEventResult(
        PlannedOwnOpportunityEvent event,
        BrandId brand,
        CoupledOwnEventOutcome outcome,
        boolean equalStepContest) {

    public CoupledOwnEventResult {
        Objects.requireNonNull(event, "Coupled own event must not be null");
        Objects.requireNonNull(brand, "Coupled own event brand must not be null");
        Objects.requireNonNull(outcome, "Coupled own event outcome must not be null");
    }

    public boolean collected() {
        return outcome == CoupledOwnEventOutcome.COLLECTED;
    }

    public boolean invalidatedByOpponent() {
        return outcome == CoupledOwnEventOutcome.INVALIDATED_BY_OPPONENT;
    }
}
