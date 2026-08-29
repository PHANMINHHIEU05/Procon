package vn.ptit.procon.planner;

/** Plan-specific opponent residual and denial counts over one immutable daily baseline. */
public record OpponentResidualClaimEvaluation(
        OpponentClaimBaseline baseline,
        int residualObservedNow,
        int residualDirectIntent,
        int residualFollowOnIntent,
        int deniedObservedNow,
        int deniedDirectIntent,
        int deniedFollowOnIntent) {

    public OpponentResidualClaimEvaluation {
        if (baseline == null) {
            throw new NullPointerException("Opponent claim baseline must not be null");
        }
        if (residualObservedNow < 0 || residualDirectIntent < 0 || residualFollowOnIntent < 0
                || deniedObservedNow < 0 || deniedDirectIntent < 0 || deniedFollowOnIntent < 0) {
            throw new IllegalArgumentException("Opponent residual metrics must be non-negative");
        }
        if (residualObservedNow + deniedObservedNow != baseline.observedNowRealizable()
                || residualDirectIntent + deniedDirectIntent != baseline.directIntentRealizable()
                || residualFollowOnIntent + deniedFollowOnIntent != baseline.followOnIntentRealizable()) {
            throw new IllegalArgumentException("Residual plus denied claims must equal the baseline");
        }
    }

    public int strongDeniedOpponentCollections() {
        return deniedObservedNow + deniedDirectIntent;
    }

    public int softDeniedOpponentCollections() {
        return deniedFollowOnIntent;
    }

    public int residualStrongRealizable() {
        return residualObservedNow + residualDirectIntent;
    }
}