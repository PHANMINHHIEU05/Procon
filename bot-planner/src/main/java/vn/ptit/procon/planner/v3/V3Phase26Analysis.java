package vn.ptit.procon.planner.v3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.engine.DayState;

/**
 * Runs the Phase 2.6 before/after comparison for one fixture, or for the whole mandated eight-fixture table.
 *
 * <p>Budgets come from {@link V3Phase25Analysis#frozenConfig(String)} by delegation, never by copy: Phase 2.6
 * is forbidden from increasing any budget, and a second copy of the rule would be a place for one to drift.
 *
 * <p>Each fixture costs three bounded searches — the historical joint beam, the composition search, and one
 * incumbent-seeded composition run for the PART 33 selected figure. The representation oracle is deliberately
 * NOT part of the table: one oracle run on CURRENT LARGE costs seconds, so it stays a separate entry point
 * that PART 24 invokes once, for the one fixture that asks about representation.
 */
public final class V3Phase26Analysis {

    /** The frozen Phase 2.3 budget rule, unchanged and not re-implemented. */
    public static StrategicSearchConfig frozenConfig(String fixture) {
        return V3Phase25Analysis.frozenConfig(fixture);
    }

    public V3Phase26FixtureReport analyse(String fixture, DayState state) {
        Objects.requireNonNull(fixture, "Fixture name must not be null");
        Objects.requireNonNull(state, "Day state must not be null");
        StrategicSearchConfig frozen = frozenConfig(fixture);
        StrategicSearchConfig composed = frozen.withCompositionSearch(true);
        V2BaselineWitness witness = new V2BaselineWitnessCapture().capture(state);
        V3SupportRootUniverse universe = V3SupportRootUniverse.of(state);
        StrategicSearchResult before = new StrategicTeamSearch().solve(state, frozen, List.of(), 0, 0,
                StrategicSearchObserver.NONE, universe);
        StrategicTeamComposition.Outcome after = new StrategicTeamComposition().run(state, composed, List.of(),
                0, 0, StrategicSearchObserver.NONE, universe);
        // PART 33: the incumbent is seeded ONLY for the selected figure. The raw figures above never see it.
        StrategicSearchResult selected = new StrategicTeamSearch().solve(state, composed,
                List.of(witness.plan()), 0, 0, StrategicSearchObserver.NONE, universe);
        StrategicSearchResult afterSearch = after.search();
        Map<AgentId, Integer> generated = new LinkedHashMap<>();
        Map<AgentId, Integer> retained = new LinkedHashMap<>();
        String afterRoot = afterSearch.rawSupportRootSignature();
        after.portfoliosByRoot().getOrDefault(afterRoot, Map.of()).forEach((patrolId, portfolio) -> {
            generated.put(patrolId, portfolio.generated());
            retained.put(patrolId, portfolio.retained());
        });
        return new V3Phase26FixtureReport(fixture, witness.ownSemiCollections(), witness.hybridMarginScore4(),
                before.rawWinner().ownSemiCollections(), before.rawWinner().hybridMarginScore4(),
                afterSearch.rawWinner().ownSemiCollections(), afterSearch.rawWinner().hybridMarginScore4(),
                selected.winner().ownSemiCollections(), selected.winner().hybridMarginScore4(),
                before.rawSupportRootSignature(), afterRoot, generated, retained, after.counters(),
                before.diagnostics().searchMillis(), afterSearch.diagnostics().searchMillis(),
                selected.fallbackUsed(), V3TerminalParityAudit.audit(state, afterSearch).exact(),
                afterSearch.diagnostics().strategicSearchPathfindingExecutions(),
                V3TeamCompositionDiversity.of(after.materialized()),
                V3MaterializationEfficiency.of(after.counters(), frozen.maxTerminalEvaluations()),
                V3PatrolRouteDiversityAudit.of(afterRoot,
                        after.portfoliosByRoot().getOrDefault(afterRoot, Map.of())));
    }

    /** The mandated eight-row table, in the mandated order. */
    public Map<String, V3Phase26FixtureReport> table() {
        Map<String, V3Phase26FixtureReport> reports = new LinkedHashMap<>();
        V3Phase24Fixtures.phase24Table().forEach((fixture, state) -> reports.put(fixture, analyse(fixture, state)));
        return reports;
    }

    /** PART 32: the raw quality scorecard, aggregated before versus after, with fallback excluded. */
    public static Scorecard scorecard(Map<String, V3Phase26FixtureReport> table) {
        Objects.requireNonNull(table, "Table must not be null");
        List<V3Phase26FixtureReport> rows = List.copyOf(table.values());
        return new Scorecard(rows.size(),
                (int) rows.stream().filter(V3Phase26FixtureReport::beforeRawWinsAgainstV2).count(),
                (int) rows.stream().filter(V3Phase26FixtureReport::beforeRawTiesV2).count(),
                (int) rows.stream().filter(V3Phase26FixtureReport::beforeRawLosesToV2).count(),
                (int) rows.stream().filter(V3Phase26FixtureReport::afterRawWinsAgainstV2).count(),
                (int) rows.stream().filter(V3Phase26FixtureReport::afterRawTiesV2).count(),
                (int) rows.stream().filter(V3Phase26FixtureReport::afterRawLosesToV2).count(),
                (int) rows.stream().filter(V3Phase26FixtureReport::rawImproved).count(),
                (int) rows.stream().filter(V3Phase26FixtureReport::rawRegressed).count());
    }

    /** The aggregate raw comparison against the frozen incumbent, counted per fixture and nothing else. */
    public record Scorecard(int fixtures, int beforeRawWins, int beforeRawTies, int beforeRawLosses,
            int afterRawWins, int afterRawTies, int afterRawLosses, int improvedFixtures,
            int regressedFixtures) {

        /** PART 32/38: the redesign must not cost a single fixture. */
        public boolean noRegression() { return regressedFixtures == 0; }

        /** The number of fixtures where raw V3 is at least as good as V2, after the redesign. */
        public int afterRawAtLeastV2() { return afterRawWins + afterRawTies; }

        @Override
        public String toString() {
            return "scorecard fixtures=" + fixtures + " before=" + beforeRawWins + "W/" + beforeRawTies + "T/"
                    + beforeRawLosses + "L after=" + afterRawWins + "W/" + afterRawTies + "T/" + afterRawLosses
                    + "L improved=" + improvedFixtures + " regressed=" + regressedFixtures;
        }
    }
}
