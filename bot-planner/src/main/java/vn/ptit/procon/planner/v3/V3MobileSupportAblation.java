package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;

/**
 * PART 51/52: the causal ablation. One fixture, one budget, one comparator, two universes.
 *
 * <p>Arm A is {@link #NO_REFUEL_ONLY} — literally {@link V3SupportRootUniverse#noRefuelOnly(DayState)},
 * which is the universe every historical V3 overload passes, so arm A reproduces Phase 2.4 exactly. Arm B is
 * the same search handed the EXISTING R3 roots as well. Nothing else differs: same graph builder, same
 * {@link StrategicSearchConfig}, same objective, same seeds. Any difference between the two arms is
 * therefore attributable to the support axis and to nothing else, which is the only reason this class
 * exists — {@code own=1 -> own=N} is a claim about causation, and a single run cannot support it.
 *
 * <p>The comparison is deliberately against arm A and never against a freshly generated tanker tour: V3
 * authors no tours, so there is nothing else legitimate to compare with.
 */
public record V3MobileSupportAblation(String fixture, Arm noRefuelOnly, Arm r3SupportRootsAvailable) {

    /** Arm A: the historical, tanker-free universe. */
    public static final String NO_REFUEL_ONLY = "NO_REFUEL_ONLY";

    /** Arm B, PART 52's name for it. */
    public static final String R3_SUPPORT_ROOTS_AVAILABLE = "R3_SUPPORT_ROOTS_AVAILABLE";

    /** Arm B, PART 51's name for the same arm. Both names are mandated, so both are reported. */
    public static final String NO_REFUEL_PLUS_R3_ROOTS = "NO_REFUEL_PLUS_R3_ROOTS";

    public V3MobileSupportAblation {
        Objects.requireNonNull(fixture, "Fixture name must not be null");
        Objects.requireNonNull(noRefuelOnly, "Arm A must not be null");
        Objects.requireNonNull(r3SupportRootsAvailable, "Arm B must not be null");
    }

    /** One arm's measured outcome. */
    public record Arm(String label, int rawOwn, int rawHybrid4, int selectedOwn, int selectedHybrid4,
            boolean fallbackUsed, String rawSupportRootSignature, int supportRootsConsidered,
            int mobileSupportRootsConsidered, int statesExpanded, long searchMillis,
            int pathfindingExecutions, V3TerminalDistribution terminals) {

        public Arm {
            Objects.requireNonNull(label, "Arm label must not be null");
            Objects.requireNonNull(rawSupportRootSignature, "Raw signature must not be null");
            Objects.requireNonNull(terminals, "Terminal distribution must not be null");
        }

        static Arm of(String label, StrategicSearchResult result) {
            return new Arm(label, result.rawWinner().ownSemiCollections(),
                    result.rawWinner().hybridMarginScore4(), result.winner().ownSemiCollections(),
                    result.winner().hybridMarginScore4(), result.fallbackUsed(),
                    result.rawSupportRootSignature(),
                    result.supportDiagnostics().supportRootsConsidered(),
                    result.supportDiagnostics().mobileSupportRootsConsidered(),
                    result.diagnostics().statesExpanded(), result.diagnostics().searchMillis(),
                    result.diagnostics().strategicSearchPathfindingExecutions(),
                    V3TerminalDistribution.of(result.terminalSnapshots()));
        }

        /** PART 37: true when this arm's RAW search preferred a mobile tanker over NO_REFUEL. */
        public boolean mobileSupportSelected() {
            return !V3SupportRootContext.NO_REFUEL.equals(rawSupportRootSignature);
        }

        @Override
        public String toString() {
            return label + " raw=" + rawOwn + "/" + rawHybrid4 + " selected=" + selectedOwn + "/"
                    + selectedHybrid4 + " fallback=" + fallbackUsed + " root=" + rawSupportRootSignature
                    + " roots=" + supportRootsConsidered + " expanded=" + statesExpanded + " ms="
                    + searchMillis + " pf=" + pathfindingExecutions;
        }
    }

    /** Runs both arms, in a fixed order, with identical inputs apart from the universe. */
    public static V3MobileSupportAblation measure(String fixture, DayState state,
            StrategicSearchConfig config, List<TeamPlan> seedPlans) {
        Objects.requireNonNull(fixture, "Fixture name must not be null");
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(config, "Search config must not be null");
        Objects.requireNonNull(seedPlans, "Seed plans must not be null");
        StrategicSearchResult armA = new StrategicTeamSearch().solve(state, config, seedPlans, 0, 0,
                StrategicSearchObserver.NONE, V3SupportRootUniverse.noRefuelOnly(state));
        StrategicSearchResult armB = new StrategicTeamSearch().solve(state, config, seedPlans, 0, 0,
                StrategicSearchObserver.NONE, V3SupportRootUniverse.of(state));
        return of(fixture, armA, armB);
    }

    /**
     * Reads the ablation off two runs a caller already performed.
     *
     * <p>An analysis pass needs arm B's graph and diagnostics for other audits, so re-running the search
     * merely to fill this record would double the cost and, worse, would let the two copies disagree.
     */
    public static V3MobileSupportAblation of(String fixture, StrategicSearchResult armA,
            StrategicSearchResult armB) {
        Objects.requireNonNull(armA, "Arm A result must not be null");
        Objects.requireNonNull(armB, "Arm B result must not be null");
        return new V3MobileSupportAblation(fixture, Arm.of(NO_REFUEL_ONLY, armA),
                Arm.of(R3_SUPPORT_ROOTS_AVAILABLE, armB));
    }

    /** The unseeded ablation, which is the one PART 52 asks for: raw behaviour with no incumbent. */
    public static V3MobileSupportAblation measure(String fixture, DayState state,
            StrategicSearchConfig config) {
        return measure(fixture, state, config, List.of());
    }

    public int rawOwnDelta() { return r3SupportRootsAvailable.rawOwn() - noRefuelOnly.rawOwn(); }

    public int rawHybrid4Delta() {
        return r3SupportRootsAvailable.rawHybrid4() - noRefuelOnly.rawHybrid4();
    }

    /**
     * PART 52: the primary causal proof. Arm B must strictly beat arm A on the raw figure.
     *
     * <p>This is intentionally strict rather than a threshold at 14. A tie would mean the support axis
     * changed nothing measurable, which is the failure this ablation is designed to detect; how far past
     * arm A the treatment reaches is a separate question, answered by
     * {@link V3SupportCapabilityCeiling}.
     */
    public boolean supportImprovedRawSearch() { return rawOwnDelta() > 0; }

    /** PART 37/51: arm B must never be WORSE than arm A — the axis is additive, not a replacement. */
    public boolean noRegressionFromSupport() {
        return r3SupportRootsAvailable.rawOwn() >= noRefuelOnly.rawOwn()
                && r3SupportRootsAvailable.selectedOwn() >= noRefuelOnly.selectedOwn();
    }

    @Override
    public String toString() {
        return fixture + " A[" + noRefuelOnly + "] B[" + r3SupportRootsAvailable + "] deltaOwn="
                + rawOwnDelta() + " deltaHybrid4=" + rawHybrid4Delta();
    }
}
