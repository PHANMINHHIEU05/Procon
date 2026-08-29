package vn.ptit.procon.engine;

import java.util.List;
import java.util.Objects;

/**
 * Authoritative wire-action duration calculator.
 *
 * <p>This is the SINGLE SOURCE OF TRUTH for computing how many day steps a per-agent encoded action
 * list will consume on the wire. Both pre-submission validation and tests must use this class; no
 * other code may independently compute wire duration.
 *
 * <p>Encoding contract (derived from {@link vn.ptit.procon.protocol.ActionEncoder}):
 * <ul>
 *   <li>A negative integer {@code -n} encodes a WAIT action consuming {@code n} steps.</li>
 *   <li>A non-negative integer {@code 0..5} encodes a MOVE action; its step cost is NOT
 *       recoverable from the direction code alone. The caller must supply per-agent step costs
 *       (from {@link ValidDaySimulationResult#stepUsage()}) rather than re-computing them here.</li>
 * </ul>
 *
 * <p>Because move step costs require terrain context that is absent from the encoded integer list,
 * this class works with the authoritative {@link AgentStepUsage} produced by the simulation that
 * validated the plan. The {@link #ofSimulationResult} factory provides the canonical pipeline:
 * simulate → extract usage → validate before submission.
 *
 * <p>For unit tests that only deal with WAIT-only plans (all actions negative), the
 * {@link #ofEncodedWaitsOnly} factory computes duration directly from the encoded list.
 */
public final class WireActionDuration {

    /**
     * Authoritative day-step contract.
     *
     * <p>The local {@link DaySimulator} loop runs {@code step=1..budget} inclusive and allows an
     * action whose cumulative steps equal the budget ({@code elapsedSteps + cost == budget} passes
     * the {@code > budget} guard). Match m-4693 (budget=30) confirmed that plans with
     * {@code totalSteps == budget} (a full-day WAIT(-30)) are accepted by the server.
     *
     * <p>Match m-4703 (budget=60) had {@code E_STEP_OVERFLOW} with "arrivalStep=60". Based on
     * available evidence, the most likely root cause is that the encoded wire actions totalled MORE
     * than 60 steps (i.e., {@code > budget}) despite the planner's own step counter reading 60.
     * Common causes: terrain-cost miscalculation, an extra padding WAIT added after a full-budget
     * route, or an implicit per-move step count from the encoder that differs from the simulator.
     *
     * <p><strong>Authoritative contract: {@code encodedDuration <= dayBudget}</strong>. A plan
     * whose cumulative wire steps equal the budget is valid; one that exceeds it must be blocked
     * before HTTP submission with a {@code WIRE_ACTION_DURATION_INVALID} diagnostic.</p>
     *
     * @param encodedDuration the total steps the wire action list will consume
     * @param dayBudget       the day's step budget as received in the setup
     * @return {@code true} if the encoded duration is within the authoritative server-accepted range
     */
    public static boolean isValid(int encodedDuration, int dayBudget) {
        return encodedDuration >= 0 && encodedDuration <= dayBudget;
    }

    /**
     * Returns the encoded duration for one agent taken from a validated simulation result.
     *
     * <p>This is the canonical production path: the simulation that produced {@code usage} ran the
     * exact same action list that will be encoded and submitted. No pathfinding, no rollout, no
     * second simulation.
     *
     * @param usage the {@link AgentStepUsage} for one agent from a {@link ValidDaySimulationResult}
     * @return total steps this agent's encoded actions will consume
     */
    public static int ofSimulationResult(AgentStepUsage usage) {
        Objects.requireNonNull(usage, "Agent step usage must not be null");
        return usage.totalSteps();
    }

    /**
     * Computes encoded duration from a WAIT-only encoded action list (all entries negative).
     *
     * <p>This factory exists for tests that construct synthetic encoded plans without running the
     * simulator. It is ONLY valid when every action in the list is a WAIT (encoded as a negative
     * integer). If any non-negative (MOVE) action is present, this method throws rather than
     * silently returning a wrong result.
     *
     * @param encodedActions list of encoded actions; every entry must be negative
     * @return total steps consumed by the WAIT-only plan
     * @throws IllegalArgumentException if any encoded action is non-negative (a MOVE direction)
     */
    public static int ofEncodedWaitsOnly(List<Integer> encodedActions) {
        Objects.requireNonNull(encodedActions, "Encoded actions must not be null");
        int total = 0;
        for (int index = 0; index < encodedActions.size(); index++) {
            int encoded = encodedActions.get(index);
            if (encoded >= 0) {
                throw new IllegalArgumentException(
                        "ofEncodedWaitsOnly: action at index " + index
                                + " is a MOVE (encoded=" + encoded
                                + "); use ofSimulationResult for plans with moves");
            }
            total += -encoded;
        }
        return total;
    }

    /**
     * Computes total encoded duration from a mixed encoded action list where MOVE step costs are
     * explicitly supplied.
     *
     * <p>This is used for tests that need fine-grained control over per-action costs.
     *
     * @param encodedActions    encoded action list (negative=WAIT, non-negative=MOVE)
     * @param moveStepCosts     step cost for each non-negative MOVE action, in the order they appear
     *                          in {@code encodedActions}
     * @return total steps consumed
     */
    public static int ofEncodedWithMoveCosts(List<Integer> encodedActions, List<Integer> moveStepCosts) {
        Objects.requireNonNull(encodedActions, "Encoded actions must not be null");
        Objects.requireNonNull(moveStepCosts, "Move step costs must not be null");
        int total = 0;
        int moveIndex = 0;
        for (int encoded : encodedActions) {
            if (encoded < 0) {
                total += -encoded;
            } else {
                if (moveIndex >= moveStepCosts.size()) {
                    throw new IllegalArgumentException(
                            "Not enough move step costs provided for encoded action list");
                }
                int cost = moveStepCosts.get(moveIndex++);
                if (cost <= 0) {
                    throw new IllegalArgumentException("Move step cost must be positive: " + cost);
                }
                total += cost;
            }
        }
        return total;
    }

    private WireActionDuration() {
    }
}
