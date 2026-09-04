package vn.ptit.procon.planner.v3;

import java.util.Objects;
import java.util.List;

/** Result of benchmark-only bounded V3 search. */
public record StrategicSearchResult(StrategicOpportunityGraph graph, StrategicOracleEvaluation winner,
        StrategicOracleEvaluation seed, StrategicSearchDiagnostics diagnostics, StrategicSearchNode winningNode,
        StrategicOracleEvaluation rawWinner, boolean fallbackUsed, List<StrategicTerminalSnapshot> terminalSnapshots,
        V3SupportSearchDiagnostics supportDiagnostics) {
    public StrategicSearchResult(StrategicOpportunityGraph graph, StrategicOracleEvaluation winner,
            StrategicOracleEvaluation seed, StrategicSearchDiagnostics diagnostics, StrategicSearchNode winningNode) {
        this(graph, winner, seed, diagnostics, winningNode, winner, false, List.of());
    }
    /** PART 8: a result built without a support axis reports the historical, tanker-free diagnostics. */
    public StrategicSearchResult(StrategicOpportunityGraph graph, StrategicOracleEvaluation winner,
            StrategicOracleEvaluation seed, StrategicSearchDiagnostics diagnostics, StrategicSearchNode winningNode,
            StrategicOracleEvaluation rawWinner, boolean fallbackUsed,
            List<StrategicTerminalSnapshot> terminalSnapshots) {
        this(graph, winner, seed, diagnostics, winningNode, rawWinner, fallbackUsed, terminalSnapshots,
                V3SupportSearchDiagnostics.none());
    }
    public StrategicSearchResult {
        Objects.requireNonNull(graph); Objects.requireNonNull(winner); Objects.requireNonNull(seed);
        Objects.requireNonNull(diagnostics); Objects.requireNonNull(winningNode); Objects.requireNonNull(rawWinner);
        Objects.requireNonNull(supportDiagnostics);
        terminalSnapshots = List.copyOf(terminalSnapshots);
    }
    /** PART 53: the support root the RAW bounded search chose, never the fallback's. */
    public String rawSupportRootSignature() { return supportDiagnostics.rawSelectedSupportRootSignature(); }
    public boolean improvedSeed() { return winner.hybridMarginScore4() > seed.hybridMarginScore4()
            || winner.ownSemiBrands() > seed.ownSemiBrands(); }
    public vn.ptit.procon.engine.TeamPlan plan() { return winner.plan(); }
}
