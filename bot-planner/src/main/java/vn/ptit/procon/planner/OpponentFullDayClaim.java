package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/**
 * One realized opponent collection inside a bounded full-day rollout of the CURRENT day.
 *
 * <p>Unlike the M14 {@link BaselineOpponentClaim} this is not capped to the first few intent targets
 * of a collector: a single collector may produce as many of these as its step budget, fuel and the
 * shared residual stock allow. The {@link OpponentClaimCommitment} label is retained for diagnostics
 * and continuity with M12 only — the M15 objective counts collections, not commitment classes.</p>
 */
public record OpponentFullDayClaim(
        int groupRawId,
        int agentIndex,
        int rawKind,
        Position spot,
        int arrivalStep,
        int collectorOrdinal,
        int legSteps,
        int legFuel,
        OpponentClaimCommitment commitment) {

    public OpponentFullDayClaim {
        Objects.requireNonNull(spot, "Full-day claim spot must not be null");
        Objects.requireNonNull(commitment, "Full-day claim commitment must not be null");
        if (agentIndex < 0 || arrivalStep < 0 || collectorOrdinal < 0 || legSteps < 0 || legFuel < 0) {
            throw new IllegalArgumentException("Full-day claim ordering values must be non-negative");
        }
        if (commitment == OpponentClaimCommitment.OBSERVED_NOW != (arrivalStep == 0)) {
            throw new IllegalArgumentException(
                    "OBSERVED_NOW is exactly the collection already standing on the spot at step 0");
        }
    }

    /** Collector identity, stable between the immutable baseline and any residual rollout. */
    public String collectorKey() {
        return groupRawId + ":" + agentIndex;
    }

    /** Collector plus spot, the identity used to recognise a rerouted replacement collection. */
    public String collectorSpotKey() {
        return groupRawId + ":" + agentIndex + ":" + spot.value();
    }

    /** True for the two commitment classes M14 called strong; diagnostics only in M15. */
    public boolean strong() {
        return commitment == OpponentClaimCommitment.OBSERVED_NOW
                || commitment == OpponentClaimCommitment.DIRECT_INTENT;
    }
}
