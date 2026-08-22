package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Stage allocation of the M11 strategy-depth correction.
 *
 * <p>The correction never raises a production bound: the three stage budgets must add up to
 * exactly the unchanged expansion budget, and the frontier and candidate reserves must stay
 * inside the unchanged frontier limit and per-state candidate limit.</p>
 */
class StratifiedSearchConfigTest {

    private static final AnytimePlannerConfig PRODUCTION = AnytimePlannerConfig.defaults();

    @Test
    void defaultsSplitTheUnchangedProductionBudgetIntoSixteenTwentyFourTwentyFour() {
        StratifiedSearchConfig config = StratifiedSearchConfig.defaults();

        assertEquals(16, config.discoveryBudget());
        assertEquals(24, config.qualificationBudget());
        assertEquals(24, config.exploitationBudget());
        assertEquals(64, config.totalStageBudget());
        assertEquals(PRODUCTION.maxExpandedStates(), config.totalStageBudget());
        assertEquals(8, config.maxQualifiedStrategies());
        assertEquals(2, config.minimumQualificationExpansionsPerStrategy());
        assertEquals(6, config.maxQualificationExpansionsPerStrategy());
        assertEquals(24, config.globalEliteSlots());
        assertEquals(2, config.discoveryQualityExpansionsPerDiversityExpansion());
        assertEquals(2, config.eliteCandidateSlots());
    }

    @Test
    void stageBudgetsMustSumToTheExpansionBudgetExactly() {
        StratifiedSearchConfig config = StratifiedSearchConfig.defaults();

        config.requireStagesSumTo(PRODUCTION.maxExpandedStates());
        assertThrows(IllegalArgumentException.class, () -> config.requireStagesSumTo(63));
        assertThrows(IllegalArgumentException.class, () -> config.requireStagesSumTo(65));

        StratifiedSearchConfig leaking = new StratifiedSearchConfig(
                16, 24, 23, 8, 2, 6, 24, 2, 2);
        assertEquals(63, leaking.totalStageBudget());
        assertThrows(IllegalArgumentException.class, () -> leaking.requireStagesSumTo(64));
    }

    @Test
    void nonProductionBudgetsKeepTheProportionsAndStillSumExactly() {
        for (int budget = 0; budget <= 96; budget++) {
            StratifiedSearchConfig config = StratifiedSearchConfig.forBudget(budget);

            assertEquals(budget, config.totalStageBudget(), "Stage budgets must sum to " + budget);
            config.requireStagesSumTo(budget);
            assertEquals(budget / 4, config.discoveryBudget());
            assertTrue(config.qualificationBudget() >= config.discoveryBudget());
            assertTrue(Math.abs(config.qualificationBudget() - config.exploitationBudget()) <= 1);
        }
        assertEquals(StratifiedSearchConfig.defaults(), StratifiedSearchConfig.forBudget(64));
        assertThrows(IllegalArgumentException.class, () -> StratifiedSearchConfig.forBudget(-1));
    }

    @Test
    void frontierReservesStayInsideTheUnchangedFrontierLimit() {
        StratifiedSearchConfig config = StratifiedSearchConfig.defaults();

        assertEquals(24, config.globalEliteSlots(PRODUCTION.maxFrontierSize()));
        assertEquals(24, config.portfolioReserveSlots(PRODUCTION.maxFrontierSize()));
        assertEquals(PRODUCTION.maxFrontierSize(),
                config.globalEliteSlots(PRODUCTION.maxFrontierSize())
                        + config.portfolioReserveSlots(PRODUCTION.maxFrontierSize()));
        // A frontier smaller than the elite reserve keeps every slot elite, never negative reserve.
        assertEquals(8, config.globalEliteSlots(8));
        assertEquals(0, config.portfolioReserveSlots(8));
        assertEquals(1, config.globalEliteSlots(1));
    }

    @Test
    void candidatePortfolioStaysTwoEliteOfTheUnchangedFourPerState() {
        StratifiedSearchConfig config = StratifiedSearchConfig.defaults();
        int elite = config.eliteCandidateSlots(PRODUCTION.topCandidatesPerState());

        assertEquals(2, elite);
        assertEquals(2, PRODUCTION.topCandidatesPerState() - elite,
                "Two elite plus two diverse candidates, exactly as M11 already selected");
        assertEquals(1, config.eliteCandidateSlots(1));
    }

    @Test
    void qualificationCapOnlyBlocksWhileAnotherStrategyIsBelowMinimumDepth() {
        StratifiedSearchConfig config = StratifiedSearchConfig.defaults();

        assertFalse(config.qualificationCapBlocks(5, true));
        assertTrue(config.qualificationCapBlocks(6, true));
        assertTrue(config.qualificationCapBlocks(9, true));
        assertFalse(config.qualificationCapBlocks(6, false),
                "Once every minimum-depth obligation is settled the soft cap must stop blocking");
        assertFalse(config.qualificationCapBlocks(99, false));
    }

    @Test
    void rejectsAllocationsThatWouldBreakTheStageContract() {
        assertThrows(IllegalArgumentException.class,
                () -> new StratifiedSearchConfig(-1, 24, 24, 8, 2, 6, 24, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new StratifiedSearchConfig(16, -1, 24, 8, 2, 6, 24, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new StratifiedSearchConfig(16, 24, -1, 8, 2, 6, 24, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new StratifiedSearchConfig(16, 24, 24, 0, 2, 6, 24, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new StratifiedSearchConfig(16, 24, 24, 8, 0, 6, 24, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new StratifiedSearchConfig(16, 24, 24, 8, 4, 3, 24, 2, 2),
                "A cap below the minimum depth could never satisfy the minimum");
        assertThrows(IllegalArgumentException.class,
                () -> new StratifiedSearchConfig(16, 24, 24, 8, 2, 6, 0, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new StratifiedSearchConfig(16, 24, 24, 8, 2, 6, 24, 0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new StratifiedSearchConfig(16, 24, 24, 8, 2, 6, 24, 2, 0));
    }
}
