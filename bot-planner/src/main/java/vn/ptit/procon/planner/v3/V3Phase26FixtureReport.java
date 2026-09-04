package vn.ptit.procon.planner.v3;

import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;

/**
 * One fixture row of the mandated Phase 2.6 before/after table.
 *
 * <p>"Before" is the historical joint per-hop beam and "after" is the bounded team-composition search, both
 * run from the SAME frozen {@link StrategicSearchConfig} — the only difference between the two columns is
 * {@link StrategicSearchConfig#compositionSearch()}. That is what makes the comparison a statement about
 * search organisation rather than about budget.
 *
 * <p>{@code selectedOwn}/{@code selectedHybrid4} are the incumbent-seeded figures and are reported separately
 * on purpose: PART 33 forbids using the frozen V2 fallback to claim success, so the verdict is read off
 * {@code afterRawOwn} alone.
 */
public record V3Phase26FixtureReport(String fixture, int v2Own, int v2Hybrid4, int beforeRawOwn,
        int beforeRawHybrid4, int afterRawOwn, int afterRawHybrid4, int selectedOwn, int selectedHybrid4,
        String beforeSupportRoot, String afterSupportRoot, Map<AgentId, Integer> perPatrolRoutesGenerated,
        Map<AgentId, Integer> perPatrolRoutesRetained, StrategicTeamComposition.Counters counters,
        long beforeSearchMillis, long afterSearchMillis, boolean fallbackUsed, boolean parityMatch,
        int strategicSearchPathfindingExecutions, V3TeamCompositionDiversity teamDiversity,
        V3MaterializationEfficiency materialization, V3PatrolRouteDiversityAudit routeDiversity) {

    public V3Phase26FixtureReport {
        Objects.requireNonNull(fixture, "Fixture must not be null");
        Objects.requireNonNull(beforeSupportRoot, "Before root must not be null");
        Objects.requireNonNull(afterSupportRoot, "After root must not be null");
        perPatrolRoutesGenerated = Map.copyOf(Objects.requireNonNull(perPatrolRoutesGenerated));
        perPatrolRoutesRetained = Map.copyOf(Objects.requireNonNull(perPatrolRoutesRetained));
        Objects.requireNonNull(counters, "Counters must not be null");
        Objects.requireNonNull(teamDiversity, "Team diversity must not be null");
        Objects.requireNonNull(materialization, "Materialization must not be null");
        Objects.requireNonNull(routeDiversity, "Route diversity must not be null");
    }

    public int routesRetained() { return counters.routesRetained(); }

    public int routesGenerated() { return counters.routesGenerated(); }

    /** PART 32: the raw comparison against the frozen incumbent, before and after, never against fallback. */
    public boolean afterRawWinsAgainstV2() { return afterRawOwn > v2Own; }

    public boolean afterRawTiesV2() { return afterRawOwn == v2Own; }

    public boolean afterRawLosesToV2() { return afterRawOwn < v2Own; }

    public boolean beforeRawWinsAgainstV2() { return beforeRawOwn > v2Own; }

    public boolean beforeRawTiesV2() { return beforeRawOwn == v2Own; }

    public boolean beforeRawLosesToV2() { return beforeRawOwn < v2Own; }

    public boolean rawImproved() { return afterRawOwn > beforeRawOwn; }

    public boolean rawRegressed() { return afterRawOwn < beforeRawOwn; }

    /** PART 37: hard zero. Search-time pathfinding would invalidate every timing figure in the row. */
    public boolean pathfindingFree() { return strategicSearchPathfindingExecutions == 0; }

    /** The mandated table row, in the mandated column order. */
    public String row() {
        return String.join(" | ", fixture, Integer.toString(v2Own), Integer.toString(v2Hybrid4),
                Integer.toString(beforeRawOwn), Integer.toString(beforeRawHybrid4),
                Integer.toString(afterRawOwn), Integer.toString(afterRawHybrid4),
                Integer.toString(selectedOwn), Integer.toString(selectedHybrid4), afterSupportRoot,
                Integer.toString(routesRetained()), Integer.toString(counters.partialStatesExpanded()),
                Integer.toString(counters.completeTeamCandidates()),
                Integer.toString(counters.materializedPlans()), afterSearchMillis + "ms",
                Boolean.toString(fallbackUsed), Boolean.toString(parityMatch),
                Integer.toString(strategicSearchPathfindingExecutions));
    }

    /** PART 35: the per-fixture search-organisation counters, reported for every fixture. */
    public String counterRow() {
        return fixture + " generated=" + perPatrolRoutesGenerated + " retained=" + perPatrolRoutesRetained
                + " partialGenerated=" + counters.partialStatesGenerated() + " partialUnique="
                + counters.partialStatesUnique() + " partialExpanded=" + counters.partialStatesExpanded()
                + " complete=" + counters.completeTeamCandidates() + " materialized="
                + counters.materializedPlans() + " valid=" + counters.validPlans() + " coupled="
                + counters.coupledEvaluations();
    }

    @Override
    public String toString() { return row(); }
}
