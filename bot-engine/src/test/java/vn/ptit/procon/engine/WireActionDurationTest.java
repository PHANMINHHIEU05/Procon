package vn.ptit.procon.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Contract tests for {@link WireActionDuration}.
 *
 * <p>All 12 required test cases use dayBudget=60 and exercise the authoritative
 * wire-duration contract ({@code encodedDuration <= dayBudget}).
 *
 * <p>The boundary analysis: match m-4693 (budget=30) showed that a WAIT-only plan
 * with total=30==budget is server-accepted. Match m-4703 (budget=60) had E_STEP_OVERFLOW,
 * most likely because the actual encoded total was &gt;60 (not exactly 60). Therefore
 * the authoritative contract is {@code encodedDuration <= dayBudget}.
 */
class WireActionDurationTest {

    private static final int DAY_BUDGET = 60;

    // -----------------------------------------------------------------------
    // 1. Wire duration 59 — must be valid (strictly inside budget)
    // -----------------------------------------------------------------------
    @Test
    void wireDuration59IsValid() {
        int duration = WireActionDuration.ofEncodedWaitsOnly(List.of(-59));
        assertEquals(59, duration);
        assertTrue(WireActionDuration.isValid(duration, DAY_BUDGET),
                "59 < 60 must be valid");
    }

    // -----------------------------------------------------------------------
    // 2. Wire duration exactly 60 (== budget) — valid per m-4693 contract
    //    (full-day wait is a standard valid plan; m-4703 was total>budget, not total==budget)
    // -----------------------------------------------------------------------
    @Test
    void wireDurationExactlyBudgetIsValid() {
        int duration = WireActionDuration.ofEncodedWaitsOnly(List.of(-60));
        assertEquals(60, duration);
        assertTrue(WireActionDuration.isValid(duration, DAY_BUDGET),
                "60 == budget must be valid (full-day plan; m-4693 confirmed wait-all accepted)");
    }

    // -----------------------------------------------------------------------
    // 3. Wire duration 61 — must never reach HTTP; isValid must return false
    // -----------------------------------------------------------------------
    @Test
    void wireDuration61IsInvalidAndMustNotReachHttp() {
        int duration = WireActionDuration.ofEncodedWaitsOnly(List.of(-30, -31));
        assertEquals(61, duration);
        assertFalse(WireActionDuration.isValid(duration, DAY_BUDGET),
                "61 > budget must be invalid");
    }

    // -----------------------------------------------------------------------
    // 4. Last movement finishes exactly at boundary (step 60) — must be valid
    //    A plan whose last move completes at step=budget is accepted locally and on server.
    // -----------------------------------------------------------------------
    @Test
    void lastMovementFinishesAtBoundaryIsValid() {
        // Encoded: 58 wait steps + 1 move costing 2 steps = total 60 = budget
        int duration = WireActionDuration.ofEncodedWithMoveCosts(
                List.of(-58, 2 /*direction code, not negative*/),
                List.of(2) // 2-step cost for the single move
        );
        assertEquals(60, duration);
        assertTrue(WireActionDuration.isValid(duration, DAY_BUDGET),
                "total==budget (move arrives at step 60) must be valid");
    }

    // -----------------------------------------------------------------------
    // 5. Route before boundary + explicit wait — total == 59 (well under budget)
    // -----------------------------------------------------------------------
    @Test
    void routeBeforeBoundaryPlusExplicitWaitIsValid() {
        // Route uses 40 steps, then wait 19 → total 59 < budget
        int duration = WireActionDuration.ofEncodedWithMoveCosts(
                List.of(2 /*move*/, -19),
                List.of(40)
        );
        assertEquals(59, duration);
        assertTrue(WireActionDuration.isValid(duration, DAY_BUDGET),
                "40 steps route + 19 wait = 59 < 60 must be valid");
    }

    // -----------------------------------------------------------------------
    // 6. Wait-only plan (single wait) — total 60 = budget, valid
    // -----------------------------------------------------------------------
    @Test
    void waitOnlyPlanSingleWaitBudgetIsValid() {
        int duration = WireActionDuration.ofEncodedWaitsOnly(List.of(-60));
        assertEquals(60, duration);
        assertTrue(WireActionDuration.isValid(duration, DAY_BUDGET),
                "Single WaitAction(budget) is the canonical safe plan; must be valid");
    }

    // -----------------------------------------------------------------------
    // 7. Multiple waits — totalling exactly 60 = budget (valid)
    // -----------------------------------------------------------------------
    @Test
    void multipleWaitsValidWhenSumEqualsBudget() {
        // Three waits: 20 + 20 + 20 = 60 = budget
        int duration = WireActionDuration.ofEncodedWaitsOnly(List.of(-20, -20, -20));
        assertEquals(60, duration);
        assertTrue(WireActionDuration.isValid(duration, DAY_BUDGET),
                "Three waits summing to budget must be valid");
    }

    // -----------------------------------------------------------------------
    // 8. Negative wait wire semantics — each negative integer is a wait in steps
    // -----------------------------------------------------------------------
    @Test
    void negativeEncodingIsWaitSemantics() {
        // -5 encodes WaitAction(5) → 5 steps
        assertEquals(5, WireActionDuration.ofEncodedWaitsOnly(List.of(-5)));
        // -1 encodes WaitAction(1) → 1 step
        assertEquals(1, WireActionDuration.ofEncodedWaitsOnly(List.of(-1)));
        // -60 encodes WaitAction(60) → 60 steps = budget
        assertEquals(60, WireActionDuration.ofEncodedWaitsOnly(List.of(-60)));
    }

    // -----------------------------------------------------------------------
    // 9. Planner-valid plan stays valid after encoding (round-trip consistency)
    // -----------------------------------------------------------------------
    @Test
    void plannerValidPlanStaysValidAfterEncoding() {
        // A plan: two moves (cost 10, 20) + wait 30 = total 60 = budget
        int duration = WireActionDuration.ofEncodedWithMoveCosts(
                List.of(0 /*move dir=0*/, 1 /*move dir=1*/, -30 /*wait*/),
                List.of(10, 20)
        );
        assertEquals(60, duration);
        assertTrue(WireActionDuration.isValid(duration, DAY_BUDGET),
                "Planner-valid plan (total=budget=60) must remain valid through encoding");
    }

    // -----------------------------------------------------------------------
    // 10. Encoder cannot silently lengthen a validated plan (ofEncodedWithMoveCosts contract)
    // -----------------------------------------------------------------------
    @Test
    void missingMoveCostThrowsRatherThanSilentlyUndercount() {
        // We have a move encoded but supply fewer costs than moves → must throw
        assertThrows(IllegalArgumentException.class, () ->
                WireActionDuration.ofEncodedWithMoveCosts(
                        List.of(0 /*move*/, 1 /*move*/),
                        List.of(10) // only one cost for two moves
                ),
                "ofEncodedWithMoveCosts must throw when costs are exhausted");
    }

    // -----------------------------------------------------------------------
    // 11. Full-day padding cannot double-pad (ActionPlanCompleter contract)
    // -----------------------------------------------------------------------
    @Test
    void fullDayPaddingCannotDoublePad() {
        // Simulate a plan already using all budget steps. Remaining = 0, so
        // ActionPlanCompleter adds no wait. Total should equal the moves' cost only.
        // With moveCost=60 (fills to budget exactly), that's valid.
        int duration = WireActionDuration.ofEncodedWithMoveCosts(
                List.of(0 /*single move*/),
                List.of(60)
        );
        assertEquals(60, duration);
        assertTrue(WireActionDuration.isValid(duration, DAY_BUDGET),
                "Single move using full budget is valid; no padding should be added");

        // If an erroneous extra wait were added, duration would be >60 → invalid
        int withExtraWait = WireActionDuration.ofEncodedWithMoveCosts(
                List.of(0 /*move*/, -1 /*extra wait*/),
                List.of(60)
        );
        assertEquals(61, withExtraWait);
        assertFalse(WireActionDuration.isValid(withExtraWait, DAY_BUDGET),
                "Move(60) + Wait(1) = 61 must be caught as invalid double-padding");
    }

    // -----------------------------------------------------------------------
    // 12. Each agent independently satisfies wire duration
    // -----------------------------------------------------------------------
    @Test
    void eachAgentIndependentlySatisfiesWireDuration() {
        // Two agents: agent 0 has 60 steps (valid = budget), agent 1 has 61 steps (invalid)
        int agent0 = WireActionDuration.ofEncodedWaitsOnly(List.of(-60));
        int agent1 = WireActionDuration.ofEncodedWaitsOnly(List.of(-30, -31));

        assertEquals(60, agent0);
        assertEquals(61, agent1);

        assertTrue(WireActionDuration.isValid(agent0, DAY_BUDGET),
                "agent 0 with 60 steps (= budget) must be valid");
        assertFalse(WireActionDuration.isValid(agent1, DAY_BUDGET),
                "agent 1 with 61 steps (> budget) must be invalid");
    }

    // -----------------------------------------------------------------------
    // Additional: ofEncodedWaitsOnly rejects non-negative (move) entries
    // -----------------------------------------------------------------------
    @Test
    void waitsOnlyRejectsNonNegativeMoveCodes() {
        assertThrows(IllegalArgumentException.class, () ->
                WireActionDuration.ofEncodedWaitsOnly(List.of(-5, 2 /*direction code = move*/)),
                "ofEncodedWaitsOnly must throw when a move (non-negative) is present");
    }

    // -----------------------------------------------------------------------
    // Additional: isValid rejects negative durations (defensive check)
    // -----------------------------------------------------------------------
    @Test
    void isValidReturnsFalseForNegativeDuration() {
        assertFalse(WireActionDuration.isValid(-1, DAY_BUDGET));
    }

    // -----------------------------------------------------------------------
    // Additional: ofSimulationResult delegates to AgentStepUsage.totalSteps()
    // -----------------------------------------------------------------------
    @Test
    void ofSimulationResultDelegatesToAgentStepUsage() {
        AgentStepUsage usage = new AgentStepUsage(47);
        assertEquals(47, WireActionDuration.ofSimulationResult(usage));
    }

    // -----------------------------------------------------------------------
    // Parameterized: boundary cases for isValid
    // -----------------------------------------------------------------------
    @ParameterizedTest(name = "encodedDuration={0} dayBudget={1} expected={2}")
    @CsvSource({
        "0, 60, true",       // zero steps — unusual but valid
        "1, 60, true",       // 1 step
        "58, 60, true",      // well inside
        "59, 60, true",      // budget-1
        "60, 60, true",      // exactly budget — valid (full-day plan)
        "61, 60, false",     // one over budget — invalid
        "100, 60, false",    // far over budget
        "-1, 60, false",     // negative — defensive
        "59, 3, false",      // large duration vs small budget
        "3, 3, true",        // budget for budget=3 — valid (full-day)
        "4, 3, false",       // over budget=3
    })
    void isValidBoundaryCases(int encodedDuration, int dayBudget, boolean expected) {
        assertEquals(expected, WireActionDuration.isValid(encodedDuration, dayBudget),
                "isValid(" + encodedDuration + ", " + dayBudget + ") should be " + expected);
    }

    // -----------------------------------------------------------------------
    // Exact Server-Contract Replay Model
    // -----------------------------------------------------------------------
    record ServerValidationResult(boolean valid, int stepWhereFailed, String reason) {}

    static ServerValidationResult validateAgainstServerContract(
            List<Integer> encodedActions, List<Integer> moveCosts, int dayBudget) {
        int currentStep = 0;
        int moveIdx = 0;
        for (int i = 0; i < encodedActions.size(); i++) {
            int action = encodedActions.get(i);
            int duration;
            if (action < 0) {
                duration = -action;
            } else {
                if (moveIdx >= moveCosts.size()) {
                    throw new IllegalArgumentException("Missing move cost at index " + moveIdx);
                }
                duration = moveCosts.get(moveIdx++);
            }
            if (currentStep >= dayBudget) {
                return new ServerValidationResult(
                        false, currentStep, "kế hoạch thừa lệnh: tổng số bước vượt quá số bước trong ngày");
            }
            if (currentStep + duration > dayBudget) {
                return new ServerValidationResult(
                        false, currentStep + duration, "tổng số bước vượt quá số bước trong ngày");
            }
            currentStep += duration;
        }
        return new ServerValidationResult(true, currentStep, "OK");
    }

    @Test
    void serverContractReplaysM4693Wait30OnBudget30AsValid() {
        ServerValidationResult result = validateAgainstServerContract(
                List.of(-30), List.of(), 30);
        assertTrue(result.valid());
        assertEquals(30, result.stepWhereFailed());
        assertEquals("OK", result.reason());
    }

    @Test
    void serverContractReplaysExact60MoveSequenceAsValid() {
        // 30 moves on PLAIN terrain (2 steps each) = 60 steps total on dayBudget=60
        List<Integer> moves = java.util.Collections.nCopies(30, 2 /* Direction.RIGHT */);
        List<Integer> costs = java.util.Collections.nCopies(30, 2 /* PLAIN cost */);

        ServerValidationResult result = validateAgainstServerContract(moves, costs, 60);
        assertTrue(result.valid());
        assertEquals(60, result.stepWhereFailed());
        assertEquals("OK", result.reason());
    }

    @Test
    void serverContractReplaysTrailingCommandAtStep60AsSurplusCommandError() {
        // 30 moves on PLAIN (60 steps) + 1 trailing WAIT(1) starting at step 60
        List<Integer> actions = new java.util.ArrayList<>(java.util.Collections.nCopies(30, 2));
        actions.add(-1); // trailing WAIT(1) command
        List<Integer> costs = java.util.Collections.nCopies(30, 2);

        ServerValidationResult result = validateAgainstServerContract(actions, costs, 60);
        assertFalse(result.valid());
        assertEquals(60, result.stepWhereFailed());
        assertEquals("kế hoạch thừa lệnh: tổng số bước vượt quá số bước trong ngày", result.reason());
    }

    @Test
    void serverContractReplaysBoundaryCrossingMoveAsStepOverflowError() {
        // 29 moves on PLAIN (58 steps) + 1 move on MOUNTAIN (3 steps) -> starts at 58, ends at 61 > 60
        List<Integer> actions = new java.util.ArrayList<>(java.util.Collections.nCopies(29, 2));
        actions.add(2); // 30th move
        List<Integer> costs = new java.util.ArrayList<>(java.util.Collections.nCopies(29, 2));
        costs.add(3); // MOUNTAIN cost = 3 steps

        ServerValidationResult result = validateAgainstServerContract(actions, costs, 60);
        assertFalse(result.valid());
        assertEquals(61, result.stepWhereFailed());
        assertEquals("tổng số bước vượt quá số bước trong ngày", result.reason());
    }
}
