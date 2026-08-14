package vn.ptit.procon.runtime;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/** Sanitized local evidence retained until the next authoritative day arrives. */
public record ParityObservation(
        DayState beginningState,
        TeamPlan submittedPlan,
        ValidDaySimulationResult predictedResult) {

    public ParityObservation {
        Objects.requireNonNull(beginningState, "Beginning state must not be null");
        Objects.requireNonNull(submittedPlan, "Submitted plan must not be null");
        Objects.requireNonNull(predictedResult, "Predicted result must not be null");
    }
}