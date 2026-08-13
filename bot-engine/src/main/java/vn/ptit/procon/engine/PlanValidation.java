package vn.ptit.procon.engine;

import java.util.Objects;
import java.util.Optional;

/** Submission-oriented validation summary backed by the simulator. */
public record PlanValidation(boolean valid, Optional<SimulationFailure> failure) {

    public PlanValidation {
        Objects.requireNonNull(failure, "Validation failure must not be null");
        if (valid == failure.isPresent()) {
            throw new IllegalArgumentException("Valid plans cannot have failures and invalid plans must have one");
        }
    }

    public static PlanValidation validPlan() {
        return new PlanValidation(true, Optional.empty());
    }

    public static PlanValidation invalidPlan(SimulationFailure failure) {
        return new PlanValidation(false, Optional.of(failure));
    }
}