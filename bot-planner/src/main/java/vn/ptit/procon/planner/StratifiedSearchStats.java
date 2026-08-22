package vn.ptit.procon.planner;

/**
 * Bounded diagnostics describing how the M11 correction split one unchanged expansion budget
 * across discovery, strategy qualification and exploitation.
 *
 * <p>These are search diagnostics only. They never take part in the complete-plan objective,
 * which stays exactly the M10 corrected {@link IntentAwarePlanEvaluation} ordering.</p>
 *
 * @param strategiesDiscovered distinct strategy keys among all generated states
 * @param strategiesQualified strategies admitted to the qualification portfolio
 * @param strategiesExpanded strategies that received at least one expansion
 * @param strategiesWithAtLeast2Expansions strategies that reached depth two
 * @param strategiesWithAtLeast3Expansions strategies that reached depth three
 * @param maxStrategyExpansionCount expansions consumed by the single deepest strategy
 * @param medianStrategyExpansionCount lower median expansion count over expanded strategies
 * @param qualifiedStrategiesMeetingMinimumDepth qualified strategies that reached the minimum
 * @param qualifiedStrategiesExhaustedBeforeMinimum qualified strategies that ran out of states
 * @param discoveryExpansions expansions actually spent in the discovery stage
 * @param qualificationExpansions expansions actually spent in the qualification stage
 * @param exploitationExpansions expansions actually spent in the exploitation stage
 * @param frontierPeak largest frontier size observed
 * @param budgetExhausted whether states remained unexpanded when the budget ran out
 */
public record StratifiedSearchStats(
        int strategiesDiscovered,
        int strategiesQualified,
        int strategiesExpanded,
        int strategiesWithAtLeast2Expansions,
        int strategiesWithAtLeast3Expansions,
        int maxStrategyExpansionCount,
        int medianStrategyExpansionCount,
        int qualifiedStrategiesMeetingMinimumDepth,
        int qualifiedStrategiesExhaustedBeforeMinimum,
        int discoveryExpansions,
        int qualificationExpansions,
        int exploitationExpansions,
        int frontierPeak,
        boolean budgetExhausted) {

    public StratifiedSearchStats {
        requireNonNegative(strategiesDiscovered, "Strategies discovered");
        requireNonNegative(strategiesQualified, "Strategies qualified");
        requireNonNegative(strategiesExpanded, "Strategies expanded");
        requireNonNegative(strategiesWithAtLeast2Expansions, "Strategies with two expansions");
        requireNonNegative(strategiesWithAtLeast3Expansions, "Strategies with three expansions");
        requireNonNegative(maxStrategyExpansionCount, "Max strategy expansion count");
        requireNonNegative(medianStrategyExpansionCount, "Median strategy expansion count");
        requireNonNegative(
                qualifiedStrategiesMeetingMinimumDepth, "Qualified strategies meeting minimum depth");
        requireNonNegative(
                qualifiedStrategiesExhaustedBeforeMinimum, "Qualified strategies exhausted early");
        requireNonNegative(discoveryExpansions, "Discovery expansions");
        requireNonNegative(qualificationExpansions, "Qualification expansions");
        requireNonNegative(exploitationExpansions, "Exploitation expansions");
        requireNonNegative(frontierPeak, "Frontier peak");
        if (strategiesExpanded > strategiesDiscovered) {
            throw new IllegalArgumentException(
                    "Expanded strategies can never exceed discovered strategies");
        }
        if (strategiesWithAtLeast2Expansions > strategiesExpanded) {
            throw new IllegalArgumentException(
                    "Strategies with two expansions can never exceed expanded strategies");
        }
        if (strategiesWithAtLeast3Expansions > strategiesWithAtLeast2Expansions) {
            throw new IllegalArgumentException(
                    "Strategies with three expansions can never exceed those with two");
        }
        if (maxStrategyExpansionCount
                > discoveryExpansions + qualificationExpansions + exploitationExpansions) {
            throw new IllegalArgumentException(
                    "A single strategy can never be expanded more often than the whole search");
        }
        if (qualifiedStrategiesMeetingMinimumDepth > strategiesQualified) {
            throw new IllegalArgumentException(
                    "Qualified strategies meeting the minimum can never exceed the qualified set");
        }
        if (qualifiedStrategiesExhaustedBeforeMinimum > strategiesQualified) {
            throw new IllegalArgumentException(
                    "Qualified strategies exhausted early can never exceed the qualified set");
        }
    }

    /** Expansions the search actually performed, which is the sum of the three stages. */
    public int totalExpansions() {
        return discoveryExpansions + qualificationExpansions + exploitationExpansions;
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
