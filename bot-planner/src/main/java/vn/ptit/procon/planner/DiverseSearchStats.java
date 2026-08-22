package vn.ptit.procon.planner;

/**
 * Bounded diagnostics describing how the M11 expansion budget was distributed across
 * strategically different opening branches.
 *
 * <p>These are search diagnostics only. They never take part in the complete-plan objective,
 * which stays exactly the M10 corrected {@link IntentAwarePlanEvaluation} ordering.</p>
 *
 * <p>{@code strategiesWithAtLeast2Expansions} is a read-only depth diagnostic added so the M11
 * correction can be compared against this mode at the same budget. It changes no M11 behavior.</p>
 */
public record DiverseSearchStats(
        int uniqueStrategyKeysGenerated,
        int uniqueStrategyKeysExpanded,
        int qualityExpansions,
        int diversityExpansions,
        int maxStrategyExpansionCount,
        int frontierPeak,
        int candidateEliteSelected,
        int candidateDiverseSelected,
        int frontierEliteRetained,
        int frontierDiverseRetained,
        int strategyBucketsSeen,
        int statesRejectedByExactDedup,
        int statesRejectedByFrontierLimit,
        int strategiesWithAtLeast2Expansions) {

    public DiverseSearchStats {
        requireNonNegative(uniqueStrategyKeysGenerated, "Unique strategy keys generated");
        requireNonNegative(uniqueStrategyKeysExpanded, "Unique strategy keys expanded");
        requireNonNegative(qualityExpansions, "Quality expansions");
        requireNonNegative(diversityExpansions, "Diversity expansions");
        requireNonNegative(maxStrategyExpansionCount, "Max strategy expansion count");
        requireNonNegative(frontierPeak, "Frontier peak");
        requireNonNegative(candidateEliteSelected, "Elite candidates selected");
        requireNonNegative(candidateDiverseSelected, "Diverse candidates selected");
        requireNonNegative(frontierEliteRetained, "Frontier elite retained");
        requireNonNegative(frontierDiverseRetained, "Frontier diverse retained");
        requireNonNegative(strategyBucketsSeen, "Strategy buckets seen");
        requireNonNegative(statesRejectedByExactDedup, "States rejected by exact dedup");
        requireNonNegative(statesRejectedByFrontierLimit, "States rejected by frontier limit");
        requireNonNegative(strategiesWithAtLeast2Expansions, "Strategies with two expansions");
        if (uniqueStrategyKeysExpanded > uniqueStrategyKeysGenerated) {
            throw new IllegalArgumentException(
                    "Expanded strategy keys can never exceed generated strategy keys");
        }
        if (strategiesWithAtLeast2Expansions > uniqueStrategyKeysExpanded) {
            throw new IllegalArgumentException(
                    "Strategies with two expansions can never exceed expanded strategy keys");
        }
        if (maxStrategyExpansionCount > qualityExpansions + diversityExpansions) {
            throw new IllegalArgumentException(
                    "A single strategy can never be expanded more often than the whole search");
        }
    }

    /** Total expansions the search actually performed, quality plus diversity. */
    public int totalExpansions() {
        return qualityExpansions + diversityExpansions;
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
