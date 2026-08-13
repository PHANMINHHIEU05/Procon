package vn.ptit.procon.engine;

public record DayCompletedEvent(int step) implements SimulationEvent {

    public DayCompletedEvent {
        if (step < 0) {
            throw new IllegalArgumentException("Completion step must be non-negative: " + step);
        }
    }
}