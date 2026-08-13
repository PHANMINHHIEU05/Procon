package vn.ptit.procon.engine;

import java.util.List;

/** Explicit valid or invalid outcome of deterministic day execution. */
public sealed interface DaySimulationResult permits
        ValidDaySimulationResult, InvalidDaySimulationResult {

    boolean valid();

    List<SimulationEvent> events();
}