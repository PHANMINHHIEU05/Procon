package vn.ptit.procon.planner;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** M12 commitment interpretation of one simulator-projected own collection event. */
public record CommitmentCollectionAssessment(
        Position spot,
        int ourCollectionStep,
        CommitmentCollectionClassification classification,
        int commitmentRemainingStock,
        boolean commitmentRealizable,
        int commitmentValueUnits) {

    public CommitmentCollectionAssessment {
        Objects.requireNonNull(spot, "Assessment spot must not be null");
        Objects.requireNonNull(classification, "Assessment classification must not be null");
        if (ourCollectionStep < 0 || commitmentRemainingStock < 0 || commitmentValueUnits < 0) {
            throw new IllegalArgumentException(
                    "Commitment collection assessment metrics must be non-negative");
        }
        if (commitmentRealizable != (commitmentRemainingStock > 0)) {
            throw new IllegalArgumentException(
                    "Commitment realizability must match remaining hard forecast stock");
        }
        if ((classification == CommitmentCollectionClassification.HARD_CLAIMED_FIRST)
                == commitmentRealizable) {
            throw new IllegalArgumentException(
                    "HARD_CLAIMED_FIRST is exactly the class with no remaining hard forecast stock");
        }
    }
}
