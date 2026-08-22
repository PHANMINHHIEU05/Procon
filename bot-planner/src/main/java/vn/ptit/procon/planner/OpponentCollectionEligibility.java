package vn.ptit.procon.planner;

import vn.ptit.procon.domain.opponent.ObservedOtherAgent;

/**
 * Planner-only Udon-collection eligibility for observed opponent agents.
 *
 * <p>This is a forecasting inference policy, not a protocol truth. {@code rawKind}
 * stays neutral in the protocol and domain representation; only this policy decides
 * which observed agents are treated as Udon collectors while forecasting stock.</p>
 */
public enum OpponentCollectionEligibility {

    /**
     * Diagnostic and regression policy: every observed agent is treated as a
     * collector. This reproduces the original M10 forecast, which live evidence
     * showed to be too pessimistic.
     */
    ALL_OBSERVED_COLLECT,

    /**
     * Production policy: raw kind zero is treated as collection-eligible for the
     * current forecasting policy. Repeated live observation showed raw kind one
     * behaving like non-collecting support, and our own game semantics already
     * establish that REFUEL agents do not collect Udon.
     */
    RAW_KIND_ZERO_COLLECTS;

    boolean collectsUdon(ObservedOtherAgent agent) {
        return this == ALL_OBSERVED_COLLECT || agent.rawKind() == 0;
    }
}
