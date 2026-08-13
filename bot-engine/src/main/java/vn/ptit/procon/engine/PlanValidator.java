package vn.ptit.procon.engine;

import java.util.Objects;

/** Validates by executing the exact same engine used for simulation. */
public final class PlanValidator {

    private final DaySimulator simulator;

    public PlanValidator() {
        this(new DaySimulator());
    }

    public PlanValidator(DaySimulator simulator) {
        this.simulator = Objects.requireNonNull(simulator, "Simulator must not be null");
    }

    public PlanValidation validate(DayState state, TeamPlan plan) {
        DaySimulationResult result = simulator.simulate(state, plan);
        if (result instanceof InvalidDaySimulationResult invalid) {
            return PlanValidation.invalidPlan(invalid.failure());
        }
        return PlanValidation.validPlan();
    }
}