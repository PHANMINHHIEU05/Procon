package vn.ptit.procon.planner.v3;

import java.util.Objects;

/**
 * One row of the mandated Phase 2.5 generalization table.
 *
 * <p>Both raw figures are carried side by side on purpose. {@code noRefuelRawOwn} is arm A of the PART 51/52
 * ablation and reproduces Phase 2.4 exactly; {@code withSupportRawOwn} is the same search with the EXISTING
 * R3 roots added. Reporting only the second number would make an improvement look like a property of the
 * fixture rather than of the support axis, and reporting only the selected figure would hide raw behaviour
 * behind the V2 incumbent, which PART 53 forbids.
 */
public record V3Phase25FixtureReport(String fixture, int v2Own, int v2Hybrid4, int noRefuelRawOwn,
        int noRefuelRawHybrid4, int withSupportRawOwn, int withSupportRawHybrid4, int selectedOwn,
        int selectedHybrid4, String selectedSupportRoot, String rawSupportRoot, boolean fallbackUsed,
        int supportRootsConsidered, int mobileSupportRootsConsidered, int statesExpanded, long searchMillis,
        boolean parityMatch, int pathfindingExecutions, int v3GeneratedNewRefuelTours,
        V3TerminalDistribution noRefuelTerminals, V3TerminalDistribution withSupportTerminals,
        V3SupportEntryRouteAudit entryRoutes, V3MobileSupportAblation ablation,
        V3SupportSearchDiagnostics supportDiagnostics) {

    public V3Phase25FixtureReport {
        Objects.requireNonNull(fixture, "Fixture name must not be null");
        Objects.requireNonNull(selectedSupportRoot, "Selected support root must not be null");
        Objects.requireNonNull(rawSupportRoot, "Raw support root must not be null");
        Objects.requireNonNull(noRefuelTerminals, "NO_REFUEL terminal distribution must not be null");
        Objects.requireNonNull(withSupportTerminals, "Support terminal distribution must not be null");
        Objects.requireNonNull(entryRoutes, "Entry route audit must not be null");
        Objects.requireNonNull(ablation, "Ablation must not be null");
        Objects.requireNonNull(supportDiagnostics, "Support diagnostics must not be null");
    }

    /** PART 32/33/34: the raw figure against the V2 incumbent. */
    public String rawOutcome() { return outcome(withSupportRawOwn, v2Own); }

    public String selectedOutcome() { return outcome(selectedOwn, v2Own); }

    private static String outcome(int v3, int v2) {
        if (v3 > v2) return "WIN";
        return v3 == v2 ? "TIE" : "LOSS";
    }

    public int rawDelta() { return withSupportRawOwn - v2Own; }

    /** PART 51: adding the support axis must never cost a fixture anything. */
    public boolean supportNonRegression() { return withSupportRawOwn >= noRefuelRawOwn; }

    /** The Phase 2.4 collapse signature: a raw search that finds a single collection on a rich day. */
    public boolean rawCollapsed() { return withSupportRawOwn <= 1 && v2Own > 4; }

    /** PART 37: true when NO_REFUEL is still what the raw search prefers on this fixture. */
    public boolean noRefuelStillWins() {
        return V3SupportRootContext.NO_REFUEL.equals(rawSupportRoot);
    }

    /** The mandated table row, in the mandated column order. */
    public String tableRow() {
        return fixture + " | v2=" + v2Own + "/" + v2Hybrid4 + " | noRefuelRaw=" + noRefuelRawOwn
                + " | withSupportRaw=" + withSupportRawOwn + "/" + withSupportRawHybrid4 + " | selected="
                + selectedOwn + "/" + selectedHybrid4 + " | root=" + selectedSupportRoot + " | fallback="
                + fallbackUsed + " | roots=" + supportRootsConsidered + " | expanded=" + statesExpanded
                + " | ms=" + searchMillis + " | parity=" + parityMatch + " | pf=" + pathfindingExecutions;
    }

    @Override
    public String toString() { return tableRow(); }
}
