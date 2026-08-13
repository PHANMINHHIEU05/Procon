package vn.ptit.procon.domain.agent;

/** Match-defined maximum fuel capacity for PATROL agents. */
public record FuelCapacity(int value) {

    public FuelCapacity {
        if (value <= 0) {
            throw new IllegalArgumentException("Fuel capacity must be positive: " + value);
        }
    }
}