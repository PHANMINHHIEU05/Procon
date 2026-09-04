package vn.ptit.procon.planner.v3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.engine.DayState;

/**
 * Runs the Phase 2.5 audits for one fixture, or for the whole mandated eight-fixture table.
 *
 * <p>Budgets come from {@link V3Phase24Analysis#frozenConfig(String)} by delegation rather than by copy:
 * Phase 2.5 is forbidden from increasing any search budget, and a second copy of the rule would be a place
 * for one to drift.
 *
 * <p>Each fixture costs three bounded searches — ablation arm A, ablation arm B, and one seeded run for the
 * PART 53 selected figure — and no representation oracle. The oracle is deliberately excluded: a
 * per-support-root oracle run on CURRENT LARGE costs tens of seconds, so {@link #representationOracle} is a
 * separate entry point that a caller invokes once, for the one fixture PART 27 asks about.
 */
public final class V3Phase25Analysis {

    /** The frozen Phase 2.3 budget rule, unchanged and not re-implemented. */
    public static StrategicSearchConfig frozenConfig(String fixture) {
        return V3Phase24Analysis.frozenConfig(fixture);
    }

    public V3Phase25FixtureReport analyse(String fixture, DayState state) {
        Objects.requireNonNull(fixture, "Fixture name must not be null");
        Objects.requireNonNull(state, "Day state must not be null");
        StrategicSearchConfig config = frozenConfig(fixture);
        V2BaselineWitness witness = new V2BaselineWitnessCapture().capture(state);
        V3SupportRootUniverse universe = V3SupportRootUniverse.of(state);
        StrategicSearchResult armA = new StrategicTeamSearch().solve(state, config, List.of(), 0, 0,
                StrategicSearchObserver.NONE, V3SupportRootUniverse.noRefuelOnly(state));
        StrategicSearchResult armB = new StrategicTeamSearch().solve(state, config, List.of(), 0, 0,
                StrategicSearchObserver.NONE, universe);
        // PART 53: the incumbent is seeded ONLY for the selected figure. The raw figures above never see it.
        StrategicSearchResult seeded = new StrategicTeamSearch().solve(state, config,
                List.of(witness.plan()), 0, 0, StrategicSearchObserver.NONE, universe);
        V3SupportEntryRouteAudit entryRoutes = V3SupportEntryRouteAudit.of(state, armB.graph(), universe,
                armB.diagnostics());
        return new V3Phase25FixtureReport(fixture, witness.ownSemiCollections(),
                witness.hybridMarginScore4(), armA.rawWinner().ownSemiCollections(),
                armA.rawWinner().hybridMarginScore4(), armB.rawWinner().ownSemiCollections(),
                armB.rawWinner().hybridMarginScore4(), seeded.winner().ownSemiCollections(),
                seeded.winner().hybridMarginScore4(), seeded.supportDiagnostics().selectedSupportRootSignature(),
                armB.rawSupportRootSignature(), seeded.fallbackUsed(),
                armB.supportDiagnostics().supportRootsConsidered(),
                armB.supportDiagnostics().mobileSupportRootsConsidered(),
                armB.diagnostics().statesExpanded(), armB.diagnostics().searchMillis(),
                V3TerminalParityAudit.audit(state, armB).exact(),
                armB.diagnostics().strategicSearchPathfindingExecutions(),
                armB.supportDiagnostics().v3GeneratedNewRefuelTours(),
                V3TerminalDistribution.of(armA.terminalSnapshots()),
                V3TerminalDistribution.of(armB.terminalSnapshots()), entryRoutes,
                V3MobileSupportAblation.of(fixture, armA, armB), armB.supportDiagnostics());
    }

    /** The mandated eight-row table, in the mandated order. */
    public Map<String, V3Phase25FixtureReport> table() {
        Map<String, V3Phase25FixtureReport> reports = new LinkedHashMap<>();
        V3Phase24Fixtures.phase24Table().forEach((fixture, state) ->
                reports.put(fixture, analyse(fixture, state)));
        return reports;
    }

    /**
     * PART 27: the representation oracle, re-run once per support root under the same explicit caps.
     *
     * <p>This is the first capability gate, and it is a REPRESENTATION question, not a search question: it
     * asks what the graph plus a fixed tanker trajectory can express at all, with the bounded search taken
     * out of the picture.
     */
    public static OracleSweep representationOracle(DayState state, V3SupportRootUniverse universe,
            V3RepresentationConfig oracleConfig) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(universe, "Universe must not be null");
        Objects.requireNonNull(oracleConfig, "Oracle config must not be null");
        Map<String, Integer> ownByRoot = new LinkedHashMap<>();
        Map<String, Integer> hybridByRoot = new LinkedHashMap<>();
        Map<String, Integer> materializedByRoot = new LinkedHashMap<>();
        for (V3SupportRootContext root : universe.roots()) {
            V3RepresentationResult result = root.present()
                    ? new V3RepresentationOracle().solve(state, oracleConfig,
                            V3EdgeRetentionPolicy.DIVERSE_GRAPH, List.of(), root)
                    : new V3RepresentationOracle().solve(state, oracleConfig);
            ownByRoot.put(root.signature(), result.winner().ownSemiCollections());
            hybridByRoot.put(root.signature(), result.winner().hybridMarginScore4());
            materializedByRoot.put(root.signature(), result.diagnostics().materializedPlans());
        }
        return new OracleSweep(Map.copyOf(ownByRoot), Map.copyOf(hybridByRoot),
                Map.copyOf(materializedByRoot), universe.noRefuel().signature());
    }

    /** Every root's representation ceiling, so the best mobile root can be compared with NO_REFUEL. */
    public record OracleSweep(Map<String, Integer> ownBySupportRoot, Map<String, Integer> hybrid4BySupportRoot,
            Map<String, Integer> materializedPlansBySupportRoot, String noRefuelSignature) {

        public OracleSweep {
            ownBySupportRoot = Map.copyOf(Objects.requireNonNull(ownBySupportRoot));
            hybrid4BySupportRoot = Map.copyOf(Objects.requireNonNull(hybrid4BySupportRoot));
            materializedPlansBySupportRoot = Map.copyOf(Objects.requireNonNull(materializedPlansBySupportRoot));
            Objects.requireNonNull(noRefuelSignature, "NO_REFUEL signature must not be null");
        }

        public int noRefuelOwn() { return ownBySupportRoot.getOrDefault(noRefuelSignature, 0); }

        public int noRefuelHybrid4() { return hybrid4BySupportRoot.getOrDefault(noRefuelSignature, 0); }

        /** The best mobile root by own collections, ties broken by signature for determinism. */
        public String bestMobileSignature() {
            return ownBySupportRoot.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(noRefuelSignature))
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
        }

        public int bestMobileOwn() {
            String best = bestMobileSignature();
            return best == null ? 0 : ownBySupportRoot.get(best);
        }

        public int bestMobileHybrid4() {
            String best = bestMobileSignature();
            return best == null ? 0 : hybrid4BySupportRoot.get(best);
        }

        @Override
        public String toString() {
            return "oracle noRefuel=" + noRefuelOwn() + "/" + noRefuelHybrid4() + " bestMobile="
                    + bestMobileOwn() + "/" + bestMobileHybrid4() + " @" + bestMobileSignature();
        }
    }
}
