package vn.ptit.procon.planner;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import vn.ptit.procon.domain.map.Position;

/**
 * Commitment-annotated forecast claims for one stocked spot.
 *
 * <p>{@code hardConsumedPortions} is the only quantity allowed to delete stock from the M12
 * hard-realizable forecast, and it is capped by {@code currentStock}: three observed claimers on a
 * spot holding two portions consume two, never three, and forecast stock never goes negative.</p>
 */
public record SpotCommitmentPressure(
        Position spot,
        int currentStock,
        int observedNowClaims,
        int directIntentClaims,
        int followOnIntentClaims,
        int hardConsumedPortions,
        List<CommittedOpponentClaim> claims) {

    public SpotCommitmentPressure {
        Objects.requireNonNull(spot, "Commitment pressure spot must not be null");
        claims = List.copyOf(Objects.requireNonNull(claims, "Committed claims must not be null"));
        if (currentStock < 0 || observedNowClaims < 0 || directIntentClaims < 0
                || followOnIntentClaims < 0 || hardConsumedPortions < 0) {
            throw new IllegalArgumentException("Commitment pressure metrics must be non-negative");
        }
        if (observedNowClaims + directIntentClaims + followOnIntentClaims != claims.size()) {
            throw new IllegalArgumentException("Commitment class counts must cover every claim");
        }
        if (hardConsumedPortions != Math.min(currentStock, observedNowClaims)) {
            throw new IllegalArgumentException(
                    "Hard consumed portions must be the observed-now claims capped by current stock");
        }
    }

    /**
     * Hard forecast portions an opponent takes strictly before {@code ourStep}, capped by stock.
     *
     * <p>Equal-step arrival is a contest, not a deletion, so an observed claim at step 0 never
     * removes stock from a collection we also project at step 0.</p>
     */
    public int hardConsumedStrictlyBefore(int ourStep) {
        int hard = 0;
        for (CommittedOpponentClaim committed : claims) {
            if (committed.hard() && committed.forecastArrivalStep() < ourStep) {
                hard++;
            }
        }
        return Math.min(currentStock, hard);
    }

    /**
     * Number of {@code DIRECT_INTENT} claims arriving strictly before {@code ourStep}.
     *
     * <p>Read by the M12.1 semi-reservation only, and only as a zero-or-more test: the count itself
     * never scales the reservation. M12 does not call this.</p>
     */
    public int directClaimsStrictlyBefore(int ourStep) {
        int direct = 0;
        for (CommittedOpponentClaim committed : claims) {
            if (committed.commitment() == OpponentClaimCommitment.DIRECT_INTENT
                    && committed.forecastArrivalStep() < ourStep) {
                direct++;
            }
        }
        return direct;
    }

    /**
     * M12.1 bounded semi reservation for a collection of ours at {@code ourStep}.
     *
     * <p>At most <em>one</em> portion is reserved at this spot no matter how many
     * {@code DIRECT_INTENT} agents are forecast to arrive before us, and never more than the capacity
     * left after hard depletion. Five direct claimers on a one-portion spot reserve one portion, not
     * five: that per-spot cap is what keeps M12.1 from collapsing back to the M10 binary model.</p>
     *
     * <p>Strictly-before only. A direct claim arriving on exactly {@code ourStep} reserves
     * nothing.</p>
     */
    public int semiReservedDirectPortionsStrictlyBefore(int ourStep) {
        int remainingAfterHard = Math.max(0, currentStock - hardConsumedStrictlyBefore(ourStep));
        return Math.min(remainingAfterHard, directClaimsStrictlyBefore(ourStep) > 0 ? 1 : 0);
    }

    /**
     * Day-level potential semi reservation at this spot, for bounded diagnostics only.
     *
     * <p>Ignores arrival ordering on purpose: it answers "could this spot ever hold a semi
     * reservation today", which is what the per-day summary line reports. Always zero or one.</p>
     */
    public int semiReservedDirectPortions() {
        int remainingAfterHard = Math.max(0, currentStock - hardConsumedPortions);
        return Math.min(remainingAfterHard, directIntentClaims > 0 ? 1 : 0);
    }

    /** True when a claim of the given commitment class arrives strictly before {@code ourStep}. */
    public boolean anyBefore(OpponentClaimCommitment commitment, int ourStep) {
        for (CommittedOpponentClaim committed : claims) {
            if (committed.commitment() == commitment && committed.forecastArrivalStep() < ourStep) {
                return true;
            }
        }
        return false;
    }

    /** True when any claim, of any commitment class, arrives on exactly {@code ourStep}. */
    public boolean anyAt(int ourStep) {
        for (CommittedOpponentClaim committed : claims) {
            if (committed.forecastArrivalStep() == ourStep) {
                return true;
            }
        }
        return false;
    }

    public OptionalInt earliestClaimStep() {
        return claims.stream().mapToInt(CommittedOpponentClaim::forecastArrivalStep).min();
    }
}
