package vn.ptit.procon.planner;

import java.util.List;
import java.util.Objects;

/**
 * Plan-specific full-day opponent residual over one immutable {@link OpponentFullDayBaseline}.
 *
 * <p>This is deliberately NOT the M14 {@link OpponentResidualClaimEvaluation} shape and does not
 * assert {@code residual + denied == baseline}. A replacement-aware rollout lets an opponent
 * collector reroute when we take its target first, so the residual may keep the same total, or even
 * exceed the baseline when the replacement target is closer and leaves more step budget behind.
 * {@link #netOpponentCollectionsRemoved()} is therefore signed on purpose.</p>
 */
public record OpponentFullDayResidualEvaluation(
        OpponentFullDayBaseline baseline,
        List<OpponentFullDayClaim> residualClaims,
        int residualObservedNow,
        int residualDirectIntent,
        int residualFollowOnIntent,
        int replacementCollections,
        int rolloutEvents,
        int maxCollectorCollections) {

    public OpponentFullDayResidualEvaluation {
        Objects.requireNonNull(baseline, "Full-day baseline must not be null");
        Objects.requireNonNull(residualClaims, "Residual claims must not be null");
        residualClaims = List.copyOf(residualClaims);
        if (residualObservedNow < 0 || residualDirectIntent < 0 || residualFollowOnIntent < 0
                || replacementCollections < 0 || rolloutEvents < 0 || maxCollectorCollections < 0) {
            throw new IllegalArgumentException("Full-day residual metrics must be non-negative");
        }
        if (residualObservedNow + residualDirectIntent + residualFollowOnIntent
                != residualClaims.size()) {
            throw new IllegalArgumentException(
                    "Full-day residual commitment counts must cover every residual collection");
        }
        if (replacementCollections > residualClaims.size()) {
            throw new IllegalArgumentException(
                    "Replacement collections cannot exceed the residual collections that hold them");
        }
    }

    public int baselineOpponentCollections() {
        return baseline.totalCollections();
    }

    public int residualOpponentCollections() {
        return residualClaims.size();
    }

    /**
     * Signed full-day effect of our own plan on the opponent team.
     *
     * <p>Positive means the opponent really lost collections over the whole day. Zero means the
     * opponent fully replaced whatever we took first. Negative means our interference handed the
     * opponent a cheaper route than the one it started on — the case the old fixed-claim denial model
     * could not express at all.</p>
     */
    public int netOpponentCollectionsRemoved() {
        return Math.subtractExact(baselineOpponentCollections(), residualOpponentCollections());
    }

    /** Diagnostics only: M12 commitment continuity over the residual rollout. */
    public int residualStrongCollections() {
        return residualObservedNow + residualDirectIntent;
    }
}
