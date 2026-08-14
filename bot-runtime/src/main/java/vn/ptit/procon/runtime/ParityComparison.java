package vn.ptit.procon.runtime;

import java.util.List;
import java.util.Objects;

public record ParityComparison(
        int submittedDay,
        ParityStatus position,
        ParityStatus patrolFuel,
        List<AgentParityMismatch> agentMismatches) {

    public ParityComparison {
        Objects.requireNonNull(position, "Position parity must not be null");
        Objects.requireNonNull(patrolFuel, "PATROL fuel parity must not be null");
        agentMismatches = List.copyOf(
                Objects.requireNonNull(agentMismatches, "Agent mismatches must not be null"));
    }
}
