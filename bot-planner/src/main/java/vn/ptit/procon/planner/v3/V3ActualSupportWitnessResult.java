package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;

/**
 * ACTUAL_R3_SUPPORT_REPLAY (PART 24): the V2 strategic skeleton replayed through V3's own primitives while
 * the selected EXISTING R3 tanker really drives its route.
 *
 * <p>This is the replacement for the Phase 2.4 {@code SUPPORT_GRANTED} diagnostic. Nothing is granted: the
 * day state is untouched, every PATROL starts with the fuel it really has, and the only reason a leg becomes
 * executable is that the tanker arrives. It is still diagnostics — {@link StrategicTeamSearch} cannot reach
 * this class, so a forced skeleton can never become a real V3 plan.
 */
public record V3ActualSupportWitnessResult(String supportRootId, String supportRootSignature,
        int transitionsAttempted, int transitionsResolved, String firstMismatch, String firstMismatchReason,
        boolean planMaterialized, boolean validatorAccepted, boolean simulatorValid, int simulatorOwn,
        int hybridMarginScore4, int chronologyOwn, String chronologyFingerprint,
        Map<AgentId, Integer> insertedWaitSteps, V3SupportEventTable eventTable,
        V3MobileSupportParityAudit parity, String notes) {

    public V3ActualSupportWitnessResult {
        Objects.requireNonNull(supportRootId, "Support root id must not be null");
        Objects.requireNonNull(supportRootSignature, "Support root signature must not be null");
        Objects.requireNonNull(firstMismatch, "First mismatch must not be null");
        Objects.requireNonNull(firstMismatchReason, "First mismatch reason must not be null");
        Objects.requireNonNull(chronologyFingerprint, "Fingerprint must not be null");
        insertedWaitSteps = Map.copyOf(Objects.requireNonNull(insertedWaitSteps));
        Objects.requireNonNull(eventTable, "Event table must not be null");
        Objects.requireNonNull(parity, "Parity audit must not be null");
        Objects.requireNonNull(notes, "Notes must not be null");
    }

    public boolean fullyReproduced() {
        return transitionsAttempted > 0 && transitionsResolved == transitionsAttempted;
    }

    /** The PART 24 gate: the skeleton reproduces, the plan is legal, and the chronology agrees. */
    public boolean reproduces(int requiredOwn, int requiredHybrid4) {
        return fullyReproduced() && planMaterialized && validatorAccepted && simulatorValid
                && simulatorOwn >= requiredOwn && hybridMarginScore4 >= requiredHybrid4 && parity.match();
    }

    /** PART 25: chronology parity is a hard gate, reported on its own so a failure is unmistakable. */
    public boolean chronologyParity() { return parity.match(); }

    public List<V3SupportEventTable.Row> supportEvents() { return eventTable.rows(); }

    @Override
    public String toString() {
        return "root=" + supportRootSignature + " attempted=" + transitionsAttempted + " resolved="
                + transitionsResolved + " own=" + simulatorOwn + " hybrid4=" + hybridMarginScore4
                + " validator=" + validatorAccepted + " simulator=" + simulatorValid
                + " chronologyParity=" + parity.match() + " firstMismatch=" + firstMismatch;
    }
}
