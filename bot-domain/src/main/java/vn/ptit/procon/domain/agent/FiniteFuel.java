package vn.ptit.procon.domain.agent;

/** Current finite fuel carried by a PATROL agent. */
public record FiniteFuel(int amount) implements AgentFuel {

    public FiniteFuel {
        if (amount < 0) {
            throw new IllegalArgumentException("Finite fuel must be non-negative: " + amount);
        }
    }
}