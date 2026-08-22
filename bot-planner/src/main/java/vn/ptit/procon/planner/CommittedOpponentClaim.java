package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/**
 * One accepted M10 forecast claim annotated with its M12 commitment class.
 *
 * <p>The underlying {@link ForecastOpponentClaim} is untouched: M12 is a cheap annotation over the
 * claims the existing forecast already produced, so old modes keep reading exactly the same claim
 * records they always did.</p>
 */
public record CommittedOpponentClaim(
        ForecastOpponentClaim claim,
        OpponentClaimCommitment commitment) {

    public CommittedOpponentClaim {
        Objects.requireNonNull(claim, "Forecast claim must not be null");
        Objects.requireNonNull(commitment, "Claim commitment must not be null");
        if (commitment == OpponentClaimCommitment.OBSERVED_NOW
                != (claim.forecastArrivalStep() == 0)) {
            throw new IllegalArgumentException(
                    "OBSERVED_NOW is exactly the claim already standing on the spot at step 0");
        }
    }

    public Position spot() {
        return claim.spot();
    }

    public int forecastArrivalStep() {
        return claim.forecastArrivalStep();
    }

    /** True only for the one commitment class allowed to delete hard forecast stock. */
    public boolean hard() {
        return commitment == OpponentClaimCommitment.OBSERVED_NOW;
    }
}
