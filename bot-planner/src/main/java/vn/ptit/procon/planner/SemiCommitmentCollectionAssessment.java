package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/**
 * M12.1 interpretation of one simulator-projected own collection event.
 *
 * <p>Separate from {@link CommitmentCollectionAssessment} because a non-realizable collection now has
 * two possible causes: hard depletion by an observed collector, or the one bounded direct reservation
 * at that spot. The M12 record can only express the first.</p>
 */
public record SemiCommitmentCollectionAssessment(
        Position spot,
        int ourCollectionStep,
        SemiCommitmentCollectionClassification classification,
        int semiCommitmentRemainingStock,
        boolean semiCommitmentRealizable,
        int semiCommitmentValueUnits) {

    public SemiCommitmentCollectionAssessment {
        Objects.requireNonNull(spot, "Assessment spot must not be null");
        Objects.requireNonNull(classification, "Assessment classification must not be null");
        if (ourCollectionStep < 0 || semiCommitmentRemainingStock < 0
                || semiCommitmentValueUnits < 0) {
            throw new IllegalArgumentException(
                    "Semi-commitment collection assessment metrics must be non-negative");
        }
        if (semiCommitmentRealizable != (semiCommitmentRemainingStock > 0)) {
            throw new IllegalArgumentException(
                    "Semi-commitment realizability must match the remaining semi forecast stock");
        }
        boolean claimedFirst =
                classification == SemiCommitmentCollectionClassification.HARD_CLAIMED_FIRST
                        || classification == SemiCommitmentCollectionClassification.SEMI_CLAIMED_FIRST;
        if (claimedFirst == semiCommitmentRealizable) {
            throw new IllegalArgumentException(
                    "HARD_CLAIMED_FIRST and SEMI_CLAIMED_FIRST are exactly the classes with no"
                            + " remaining semi forecast stock");
        }
    }
}
