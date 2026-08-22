package vn.ptit.procon.planner;

/**
 * Immutable stage allocation for the M11 strategy-depth correction.
 *
 * <p>The same production expansion budget is split into three deterministic stages:
 * discovery finds a portfolio of opening strategies, qualification gives a bounded subset of
 * them enough depth to prove whether they lead anywhere, and exploitation drops all fairness
 * and spends the rest on the globally strongest branch. No production bound is raised by this
 * configuration and no environment variable exposes it.</p>
 */
public record StratifiedSearchConfig(
        int discoveryBudget,
        int qualificationBudget,
        int exploitationBudget,
        int maxQualifiedStrategies,
        int minimumQualificationExpansionsPerStrategy,
        int maxQualificationExpansionsPerStrategy,
        int globalEliteSlots,
        int discoveryQualityExpansionsPerDiversityExpansion,
        int eliteCandidateSlots) {

    /** Expansions spent discovering a representative portfolio of opening strategies. */
    public static final int DEFAULT_DISCOVERY_BUDGET = 16;

    /** Expansions spent giving the qualified strategy portfolio meaningful depth. */
    public static final int DEFAULT_QUALIFICATION_BUDGET = 24;

    /** Expansions spent purely quality-first on the strongest surviving branches. */
    public static final int DEFAULT_EXPLOITATION_BUDGET = 24;

    /** Upper bound on strategies admitted to qualification, regardless of how many exist. */
    public static final int DEFAULT_MAX_QUALIFIED_STRATEGIES = 8;

    /** Qualification expansions every live qualified strategy receives before any third. */
    public static final int DEFAULT_MINIMUM_QUALIFICATION_EXPANSIONS_PER_STRATEGY = 2;

    /** Soft qualification cap while another qualified strategy is below its minimum depth. */
    public static final int DEFAULT_MAX_QUALIFICATION_EXPANSIONS_PER_STRATEGY = 6;

    /** Frontier states that always survive on global quality alone, out of 48. */
    public static final int DEFAULT_GLOBAL_ELITE_SLOTS = 24;

    /** Discovery schedule: two quality expansions, then one diversity expansion. */
    public static final int DEFAULT_DISCOVERY_QUALITY_EXPANSIONS_PER_DIVERSITY_EXPANSION = 2;

    /** Elite candidate slots kept per expanded state, the remainder going to novelty. */
    public static final int DEFAULT_ELITE_CANDIDATE_SLOTS = 2;

    public StratifiedSearchConfig {
        requireNonNegative(discoveryBudget, "Discovery budget");
        requireNonNegative(qualificationBudget, "Qualification budget");
        requireNonNegative(exploitationBudget, "Exploitation budget");
        if (maxQualifiedStrategies <= 0) {
            throw new IllegalArgumentException("Maximum qualified strategies must be positive");
        }
        if (minimumQualificationExpansionsPerStrategy <= 0) {
            throw new IllegalArgumentException("Minimum qualification depth must be positive");
        }
        if (maxQualificationExpansionsPerStrategy < minimumQualificationExpansionsPerStrategy) {
            throw new IllegalArgumentException(
                    "Qualification cap must not be below the minimum qualification depth");
        }
        if (globalEliteSlots <= 0) {
            throw new IllegalArgumentException("Global elite frontier slots must be positive");
        }
        if (discoveryQualityExpansionsPerDiversityExpansion <= 0) {
            throw new IllegalArgumentException(
                    "Discovery quality expansions per diversity expansion must be positive");
        }
        if (eliteCandidateSlots <= 0) {
            throw new IllegalArgumentException("Elite candidate slots must be positive");
        }
    }

    public static StratifiedSearchConfig defaults() {
        return new StratifiedSearchConfig(
                DEFAULT_DISCOVERY_BUDGET,
                DEFAULT_QUALIFICATION_BUDGET,
                DEFAULT_EXPLOITATION_BUDGET,
                DEFAULT_MAX_QUALIFIED_STRATEGIES,
                DEFAULT_MINIMUM_QUALIFICATION_EXPANSIONS_PER_STRATEGY,
                DEFAULT_MAX_QUALIFICATION_EXPANSIONS_PER_STRATEGY,
                DEFAULT_GLOBAL_ELITE_SLOTS,
                DEFAULT_DISCOVERY_QUALITY_EXPANSIONS_PER_DIVERSITY_EXPANSION,
                DEFAULT_ELITE_CANDIDATE_SLOTS);
    }

    /**
     * Stage allocation for a non-production expansion budget, keeping the default 16/24/24
     * proportions and always summing to {@code maxExpandedStates} exactly.
     */
    public static StratifiedSearchConfig forBudget(int maxExpandedStates) {
        if (maxExpandedStates < 0) {
            throw new IllegalArgumentException("Expansion budget must be non-negative");
        }
        int discovery = maxExpandedStates / 4;
        int remaining = maxExpandedStates - discovery;
        int qualification = remaining / 2;
        StratifiedSearchConfig defaults = defaults();
        return new StratifiedSearchConfig(
                discovery,
                qualification,
                remaining - qualification,
                defaults.maxQualifiedStrategies,
                defaults.minimumQualificationExpansionsPerStrategy,
                defaults.maxQualificationExpansionsPerStrategy,
                defaults.globalEliteSlots,
                defaults.discoveryQualityExpansionsPerDiversityExpansion,
                defaults.eliteCandidateSlots);
    }

    public int totalStageBudget() {
        return discoveryBudget + qualificationBudget + exploitationBudget;
    }

    /**
     * Rejects a stage allocation that does not spend the planner's expansion budget exactly, so
     * no stage can silently borrow from or leak budget to another.
     */
    public void requireStagesSumTo(int maxExpandedStates) {
        if (totalStageBudget() != maxExpandedStates) {
            throw new IllegalArgumentException(
                    "Stage budgets " + discoveryBudget + "+" + qualificationBudget + "+"
                            + exploitationBudget + " must sum to the expansion budget "
                            + maxExpandedStates);
        }
    }

    /** Global quality reserve of the frontier, never above the frontier limit. */
    public int globalEliteSlots(int maxFrontierSize) {
        return Math.max(1, Math.min(maxFrontierSize, globalEliteSlots));
    }

    /** Portfolio reserve of the frontier, being the capacity global quality does not claim. */
    public int portfolioReserveSlots(int maxFrontierSize) {
        return Math.max(0, maxFrontierSize - globalEliteSlots(maxFrontierSize));
    }

    /** Elite candidate slots actually usable for the configured per-state candidate limit. */
    public int eliteCandidateSlots(int topCandidatesPerState) {
        return Math.max(1, Math.min(topCandidatesPerState, eliteCandidateSlots));
    }

    /**
     * Soft per-strategy qualification cap: a strategy already at the cap yields the next
     * qualification expansion while any qualified strategy is still below its minimum depth.
     * Once every minimum-depth obligation is settled the cap stops blocking quality selection.
     */
    public boolean qualificationCapBlocks(int qualificationExpansions, boolean anyBelowMinimum) {
        return anyBelowMinimum && qualificationExpansions >= maxQualificationExpansionsPerStrategy;
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
