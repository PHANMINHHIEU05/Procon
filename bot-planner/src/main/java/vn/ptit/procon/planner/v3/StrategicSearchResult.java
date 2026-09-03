package vn.ptit.procon.planner.v3;

import java.util.Objects;
import java.util.List;

/** Result of benchmark-only bounded V3 search. */
public record StrategicSearchResult(StrategicOpportunityGraph graph, StrategicOracleEvaluation winner,
        StrategicOracleEvaluation seed, StrategicSearchDiagnostics diagnostics, StrategicSearchNode winningNode,
        StrategicOracleEvaluation rawWinner, boolean fallbackUsed, List<StrategicTerminalSnapshot> terminalSnapshots) {
    public StrategicSearchResult(StrategicOpportunityGraph graph, StrategicOracleEvaluation winner,
            StrategicOracleEvaluation seed, StrategicSearchDiagnostics diagnostics, StrategicSearchNode winningNode) {
        this(graph, winner, seed, diagnostics, winningNode, winner, false, List.of());
    }
    public StrategicSearchResult {
        Objects.requireNonNull(graph); Objects.requireNonNull(winner); Objects.requireNonNull(seed);
        Objects.requireNonNull(diagnostics); Objects.requireNonNull(winningNode); Objects.requireNonNull(rawWinner);
        terminalSnapshots = List.copyOf(terminalSnapshots);
    }
    public boolean improvedSeed() { return winner.hybridMarginScore4() > seed.hybridMarginScore4()
            || winner.ownSemiBrands() > seed.ownSemiBrands(); }
    public vn.ptit.procon.engine.TeamPlan plan() { return winner.plan(); }
}
