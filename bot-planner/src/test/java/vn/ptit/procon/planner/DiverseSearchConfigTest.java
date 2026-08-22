package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Bounded diversity policy defaults for the unchanged production budget.
 *
 * <p>The production budget stays {@code 64} expanded states, {@code 48} frontier states and
 * {@code 4} candidates per state, so these assertions pin the elite/diversity split that budget
 * is divided into rather than any new budget value.</p>
 */
class DiverseSearchConfigTest {

    private static final AnytimePlannerConfig PRODUCTION = AnytimePlannerConfig.defaults();

    @Test
    void productionBudgetIsUnchangedByTheDiversityPolicy() {
        assertEquals(64, PRODUCTION.maxExpandedStates());
        assertEquals(48, PRODUCTION.maxFrontierSize());
        assertEquals(4, PRODUCTION.topCandidatesPerState());
    }

    @Test
    void frontierSplitsIntoThirtyTwoEliteAndSixteenDiversitySlots() {
        DiverseSearchConfig config = DiverseSearchConfig.defaults();

        assertEquals(32, config.frontierEliteSlots(PRODUCTION.maxFrontierSize()));
        assertEquals(16, config.frontierDiversitySlots(PRODUCTION.maxFrontierSize()));
        assertEquals(PRODUCTION.maxFrontierSize(),
                config.frontierEliteSlots(PRODUCTION.maxFrontierSize())
                        + config.frontierDiversitySlots(PRODUCTION.maxFrontierSize()));
        assertTrue(2 * config.frontierEliteSlots(PRODUCTION.maxFrontierSize())
                        >= PRODUCTION.maxFrontierSize(),
                "The elite reserve must never drop below half of the frontier capacity");
    }

    @Test
    void candidateSlotsSplitEvenlyAndKeepHalfOfPerStateCapacityElite() {
        DiverseSearchConfig config = DiverseSearchConfig.defaults();

        assertEquals(2, config.eliteCandidateSlots());
        assertEquals(2, config.diverseCandidateSlots());
        assertEquals(PRODUCTION.topCandidatesPerState(),
                config.eliteCandidateSlots() + config.diverseCandidateSlots());
        assertEquals(2, config.eliteCandidateSlots(PRODUCTION.topCandidatesPerState()));
        assertTrue(2 * config.eliteCandidateSlots(PRODUCTION.topCandidatesPerState())
                        >= PRODUCTION.topCandidatesPerState(),
                "At least half of the per-state candidate capacity must stay elite");
    }

    @Test
    void expansionScheduleIsThreeQualityTurnsPerDiversityTurn() {
        DiverseSearchConfig config = DiverseSearchConfig.defaults();
        int quality = config.qualityExpansionsPerDiversityExpansion();

        assertEquals(3, quality);
        assertEquals(4, config.maxDiversityStatesPerStrategy());
        // 3 quality then 1 diversity is 75% / 25% of the unchanged 64-state budget.
        assertEquals(48, PRODUCTION.maxExpandedStates() * quality / (quality + 1));
        assertEquals(16, PRODUCTION.maxExpandedStates() / (quality + 1));
    }

    @Test
    void tinyBudgetsNeverProduceAnEmptyEliteReserve() {
        DiverseSearchConfig config = DiverseSearchConfig.defaults();

        assertEquals(1, config.frontierEliteSlots(1));
        assertEquals(0, config.frontierDiversitySlots(1));
        assertEquals(2, config.frontierEliteSlots(4));
        assertEquals(2, config.frontierDiversitySlots(4));
        assertEquals(1, config.eliteCandidateSlots(1));
    }

    @Test
    void invalidPoliciesAreRejectedInsteadOfSilentlyDisablingQuality() {
        assertThrows(IllegalArgumentException.class,
                () -> new DiverseSearchConfig(0, 2, 2, 3, 4, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new DiverseSearchConfig(2, -1, 2, 3, 4, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new DiverseSearchConfig(2, 2, 2, 0, 4, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new DiverseSearchConfig(2, 2, 4, 3, 4, 3));
        // An elite share below one half would let diversity outrank quality in the frontier.
        assertThrows(IllegalArgumentException.class,
                () -> new DiverseSearchConfig(2, 2, 1, 3, 4, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new DiverseSearchConfig(2, 2, 2, 3, 0, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new DiverseSearchConfig(2, 2, 2, 3, 4, 0));
    }
}
