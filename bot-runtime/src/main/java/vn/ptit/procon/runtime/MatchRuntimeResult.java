package vn.ptit.procon.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record MatchRuntimeResult(
        int submittedDays,
        int rateLimitOccurrences,
        JsonNode authoritativeResult,
        List<ParityComparison> parityComparisons) {

    public MatchRuntimeResult {
        parityComparisons = List.copyOf(parityComparisons);
    }
}