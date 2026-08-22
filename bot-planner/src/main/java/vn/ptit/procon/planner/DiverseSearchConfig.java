package vn.ptit.procon.planner;

/**
 * Deterministic elite-plus-diversity policy for the M11 bounded search.
 *
 * <p>Diversity is bounded and subordinate to quality: the elite lanes always keep the
 * globally strongest branches under the unchanged M10 comparators, and only the remaining
 * capacity is offered to strategically different branches. No production budget value is
 * changed by this configuration.</p>
 */
public record DiverseSearchConfig(
        int eliteCandidateSlots,
        int diverseCandidateSlots,
        int frontierEliteNumerator,
        int frontierEliteDenominator,
        int maxDiversityStatesPerStrategy,
        int qualityExpansionsPerDiversityExpansion) {

    /** Elite candidate slots kept per expanded state. */
    public static final int DEFAULT_ELITE_CANDIDATE_SLOTS = 2;

    /** Candidate slots offered to strategically different candidates per expanded state. */
    public static final int DEFAULT_DIVERSE_CANDIDATE_SLOTS = 2;

    /** Frontier elite reserve share, {@code 2/3} of 48 giving 32 elite and 16 diversity slots. */
    public static final int DEFAULT_FRONTIER_ELITE_NUMERATOR = 2;

    /** Denominator of the frontier elite reserve share. */
    public static final int DEFAULT_FRONTIER_ELITE_DENOMINATOR = 3;

    /** Cap on states one strategy may occupy inside the frontier diversity reserve only. */
    public static final int DEFAULT_MAX_DIVERSITY_STATES_PER_STRATEGY = 4;

    /** Quality expansions scheduled before one diversity expansion, giving 75%/25%. */
    public static final int DEFAULT_QUALITY_EXPANSIONS_PER_DIVERSITY_EXPANSION = 3;

    public DiverseSearchConfig {
        if (eliteCandidateSlots <= 0) {
            throw new IllegalArgumentException("Elite candidate slots must be positive");
        }
        if (diverseCandidateSlots < 0) {
            throw new IllegalArgumentException("Diverse candidate slots must be non-negative");
        }
        if (frontierEliteDenominator <= 0) {
            throw new IllegalArgumentException("Frontier elite denominator must be positive");
        }
        if (frontierEliteNumerator <= 0 || frontierEliteNumerator > frontierEliteDenominator) {
            throw new IllegalArgumentException("Frontier elite share must be within (0, 1]");
        }
        if (2 * frontierEliteNumerator < frontierEliteDenominator) {
            throw new IllegalArgumentException(
                    "Frontier elite reserve must keep at least half of the frontier capacity");
        }
        if (maxDiversityStatesPerStrategy <= 0) {
            throw new IllegalArgumentException("Diversity states per strategy must be positive");
        }
        if (qualityExpansionsPerDiversityExpansion <= 0) {
            throw new IllegalArgumentException("Quality expansions per diversity expansion must be positive");
        }
    }

    public static DiverseSearchConfig defaults() {
        return new DiverseSearchConfig(
                DEFAULT_ELITE_CANDIDATE_SLOTS,
                DEFAULT_DIVERSE_CANDIDATE_SLOTS,
                DEFAULT_FRONTIER_ELITE_NUMERATOR,
                DEFAULT_FRONTIER_ELITE_DENOMINATOR,
                DEFAULT_MAX_DIVERSITY_STATES_PER_STRATEGY,
                DEFAULT_QUALITY_EXPANSIONS_PER_DIVERSITY_EXPANSION);
    }

    /** Elite reserve of the frontier, never below one and never above the frontier limit. */
    public int frontierEliteSlots(int maxFrontierSize) {
        int elite = maxFrontierSize * frontierEliteNumerator / frontierEliteDenominator;
        return Math.max(1, Math.min(maxFrontierSize, elite));
    }

    /** Diversity reserve of the frontier, being the capacity the elite reserve does not claim. */
    public int frontierDiversitySlots(int maxFrontierSize) {
        return Math.max(0, maxFrontierSize - frontierEliteSlots(maxFrontierSize));
    }

    /** Elite candidate slots actually usable for the configured per-state candidate limit. */
    public int eliteCandidateSlots(int topCandidatesPerState) {
        return Math.max(1, Math.min(topCandidatesPerState, eliteCandidateSlots));
    }
}
