package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.ptit.procon.engine.DayState;

/**
 * Runs every Phase 2.4 audit for one fixture, or for the whole mandated eight-fixture table.
 *
 * <p>Budgets are the frozen Phase 2.3 per-fixture budgets, reproduced exactly: Phase 2.4 is forbidden
 * from increasing any search budget, so this class must never widen them.
 */
public final class V3Phase24Analysis {

    /** Exactly the frozen Phase 2.3 budget rule, including its lowercase fixture-name matching. */
    public static StrategicSearchConfig frozenConfig(String fixture) {
        if (fixture.contains("live-like")) return new StrategicSearchConfig(64, 1024, 64, 16, 12, 128);
        if (fixture.contains("large")) return new StrategicSearchConfig(32, 128, 32, 16, 10, 16);
        return new StrategicSearchConfig(32, 512, 32, 16, 10, 64);
    }

    public V3Phase24FixtureReport analyse(String fixture, DayState state) {
        StrategicSearchConfig config = frozenConfig(fixture);
        V2BaselineWitness witness = new V2BaselineWitnessCapture().capture(state);
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder()
                .build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
        V2StrategicWitness strategic = new V2PlanToStrategicWitness().extract(state, witness, graph);
        V3ObservedSearch.Observation observation = V3ObservedSearch.run(state, config,
                List.of(witness.plan()));
        StrategicSearchResult result = observation.result();
        V3SearchCoverage coverage = observation.observed().coverage(result);
        V3BaselineRepresentability representability = new V3BaselineRepresentabilityAudit()
                .audit(state, witness, strategic, graph, result.terminalSnapshots());
        V3AllocationReplay allocation = new V3AllocationReplayAudit()
                .audit(state, strategic, graph, config, coverage);
        V3PrefixSurvivalTrace prefix = new V3PrefixSurvivalAudit()
                .audit(strategic, observation.observed(), representability, result, config);
        V3ForcedWitnessReplayResult strict = new V3ForcedWitnessReplayRunner()
                .replay(state, witness, strategic, V3ForcedWitnessReplayResult.Mode.STRICT);
        V3ForcedWitnessReplayResult granted = new V3ForcedWitnessReplayRunner()
                .replay(state, witness, strategic, V3ForcedWitnessReplayResult.Mode.SUPPORT_GRANTED);
        V3RepresentationConfig oracleConfig = V3RepresentationConfig.defaults();
        V3RepresentationResult oracle = new V3RepresentationOracle().solve(state, oracleConfig);
        return new V3Phase24FixtureReport(fixture, witness.ownSemiCollections(),
                witness.hybridMarginScore4(), result.rawWinner().ownSemiCollections(),
                result.rawWinner().hybridMarginScore4(), result.winner().ownSemiCollections(),
                result.winner().hybridMarginScore4(), representability.fullyRepresentable(),
                strict.reproducedOwn(), prefix.classification(), result.diagnostics().statesExpanded(),
                result.diagnostics().graphEdgeExpansions() + result.diagnostics().crossRegionExpansions(),
                result.diagnostics().trajectoryCacheEntries(), result.diagnostics().searchMillis(),
                V3TerminalParityAudit.audit(state, result).exact(),
                result.diagnostics().searchPathfindingExecutions(), result.fallbackUsed(),
                oracleConfig.toString(), oracle.winner().ownSemiCollections(),
                oracle.winner().hybridMarginScore4(), witness, strategic, representability,
                allocation, prefix, coverage, strict, granted);
    }

    /** The mandated eight-row table, in the mandated order. */
    public Map<String, V3Phase24FixtureReport> table() {
        Map<String, V3Phase24FixtureReport> reports = new LinkedHashMap<>();
        V3Phase24Fixtures.phase24Table().forEach((fixture, state) ->
                reports.put(fixture, analyse(fixture, state)));
        return reports;
    }

    /** PART 26 aggregate: raw and selected outcomes counted separately. */
    public static Scorecard scorecard(Map<String, V3Phase24FixtureReport> reports) {
        int rawWins = 0, rawTies = 0, rawLosses = 0, selWins = 0, selTies = 0, selLosses = 0;
        List<String> catastrophic = new ArrayList<>();
        for (V3Phase24FixtureReport report : reports.values()) {
            switch (report.rawOutcome()) {
                case "WIN" -> rawWins++;
                case "TIE" -> rawTies++;
                default -> rawLosses++;
            }
            switch (report.selectedOutcome()) {
                case "WIN" -> selWins++;
                case "TIE" -> selTies++;
                default -> selLosses++;
            }
            if (report.rawCatastrophicRegression()) {
                catastrophic.add(report.fixture() + "(" + report.rawDelta() + ")");
            }
        }
        return new Scorecard(rawWins, rawTies, rawLosses, selWins, selTies, selLosses,
                List.copyOf(catastrophic));
    }

    public record Scorecard(int rawWins, int rawTies, int rawLosses, int selectedWins, int selectedTies,
            int selectedLosses, List<String> rawCatastrophicRegressions) {
        public Scorecard { rawCatastrophicRegressions = List.copyOf(rawCatastrophicRegressions); }
    }
}
