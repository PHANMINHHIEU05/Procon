package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;

/** One fixture row of the mandated Phase 2.4 table, plus every audit that produced it. */
public record V3Phase24FixtureReport(String fixture, int v2Own, int v2Hybrid4, int rawV3Own, int rawV3Hybrid4,
        int selectedOwn, int selectedHybrid4, boolean v2WitnessRepresentable, int forcedWitnessReplayOwn,
        V3FirstDivergence firstDivergence, int statesExpanded, int edgesExpanded, int trajectoryCacheEntries,
        long searchMillis, boolean parityMatch, int searchPathfindingExecutions, boolean fallbackUsed,
        String oracleCap, int oracleOwn, int oracleHybrid4,
        V2BaselineWitness witness, V2StrategicWitness strategic, V3BaselineRepresentability representability,
        V3AllocationReplay allocation, V3PrefixSurvivalTrace prefixSurvival, V3SearchCoverage coverage,
        V3ForcedWitnessReplayResult strictReplay, V3ForcedWitnessReplayResult supportGrantedReplay) {

    public V3Phase24FixtureReport {
        Objects.requireNonNull(fixture);
        Objects.requireNonNull(firstDivergence);
    }

    /** PART 26/27: the raw planner is scored on its own, never behind the incumbent fallback. */
    public int rawDelta() { return rawV3Own - v2Own; }

    public int selectedDelta() { return selectedOwn - v2Own; }

    public boolean rawCatastrophicRegression() { return rawDelta() <= -2; }

    public String rawOutcome() { return outcome(rawV3Own, v2Own); }

    public String selectedOutcome() { return outcome(selectedOwn, v2Own); }

    private static String outcome(int value, int baseline) {
        if (value > baseline) return "WIN";
        return value == baseline ? "TIE" : "LOSS";
    }

    public List<String> tableRow() {
        return List.of(fixture, Integer.toString(v2Own), Integer.toString(v2Hybrid4),
                Integer.toString(rawV3Own), Integer.toString(rawV3Hybrid4), Integer.toString(selectedOwn),
                Integer.toString(selectedHybrid4), Boolean.toString(v2WitnessRepresentable),
                Integer.toString(forcedWitnessReplayOwn), firstDivergence.name(),
                Integer.toString(statesExpanded), Integer.toString(edgesExpanded),
                Integer.toString(trajectoryCacheEntries), Long.toString(searchMillis),
                Boolean.toString(parityMatch));
    }
}
