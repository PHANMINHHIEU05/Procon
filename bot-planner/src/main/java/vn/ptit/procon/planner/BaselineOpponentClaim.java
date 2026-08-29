package vn.ptit.procon.planner;

import java.util.Objects;

/** One deterministically ordered opponent claim that can consume current stock without our plan. */
public record BaselineOpponentClaim(
        CommittedOpponentClaim committedClaim,
        int spotOrdinal) {

    public BaselineOpponentClaim {
        Objects.requireNonNull(committedClaim, "Committed opponent claim must not be null");
        if (spotOrdinal < 0) {
            throw new IllegalArgumentException("Spot claim ordinal must be non-negative");
        }
    }

    public OpponentClaimCommitment commitment() {
        return committedClaim.commitment();
    }

    public int arrivalStep() {
        return committedClaim.forecastArrivalStep();
    }
}