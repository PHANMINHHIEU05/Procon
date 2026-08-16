package vn.ptit.procon.planner;

import vn.ptit.procon.domain.opponent.ObservedOtherAgent;

/** Explicit M10 policy for neutral or experimental observed-agent inclusion. */
public enum OpponentAgentPolicy {
    ALL_OBSERVED,
    LIKELY_COLLECTOR_RAW_KIND_ZERO;

    boolean includes(ObservedOtherAgent agent) {
        return this == ALL_OBSERVED || agent.rawKind() == 0;
    }
}