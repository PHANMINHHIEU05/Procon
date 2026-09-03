package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;

/**
 * PART 19 of Phase 2.4: the outcome of pushing the V2-extracted strategic decisions back through V3's
 * own primitives — retained edge lookup, {@link CachedTrajectoryEffect}, {@link
 * StrategicChronologyReplay} and V3's normalization.
 *
 * <p>This is diagnostics only.  Nothing in {@link StrategicTeamSearch} can reach it, so a forced replay
 * can never influence a real V3 plan.
 */
public record V3ForcedWitnessReplayResult(Mode mode, int transitionsAttempted, int transitionsResolved,
        String firstMismatch, String firstMismatchReason, int reproducedOwn, int reproducedHybrid4,
        String chronologyFingerprint, boolean planMaterialized, boolean validatorAccepted,
        int simulatorOwn, List<String> resolvedRoutes, String notes) {

    public V3ForcedWitnessReplayResult {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(firstMismatch);
        Objects.requireNonNull(firstMismatchReason);
        Objects.requireNonNull(chronologyFingerprint);
        resolvedRoutes = List.copyOf(resolvedRoutes);
        Objects.requireNonNull(notes);
    }

    public boolean fullyReproduced() { return transitionsResolved == transitionsAttempted; }

    /**
     * STRICT replays against the untouched day state, so it measures V3's real representation.
     * SUPPORT_GRANTED first grants each patrol the fuel V2 obtained from its tanker service, which
     * isolates the refuel-root gap from every other possible cause.
     */
    public enum Mode { STRICT, SUPPORT_GRANTED }
}
