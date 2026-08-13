package vn.ptit.procon.engine;

import java.util.List;
import java.util.Objects;

/** Diagnostic-only outcome; no partially committed final state is exposed. */
public record InvalidDaySimulationResult(
        SimulationFailure failure, List<SimulationEvent> events)
        implements DaySimulationResult {

    public InvalidDaySimulationResult {
        Objects.requireNonNull(failure, "Simulation failure must not be null");
        events = List.copyOf(Objects.requireNonNull(events, "Diagnostic events must not be null"));
    }

    @Override
    public boolean valid() {
        return false;
    }
}