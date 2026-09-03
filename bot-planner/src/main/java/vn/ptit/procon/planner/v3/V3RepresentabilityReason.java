package vn.ptit.procon.planner.v3;

/** Why one V2 strategic transition is or is not representable inside the V3 search space. */
public enum V3RepresentabilityReason {
    REPRESENTED,
    NODE_MISSING,
    EDGE_MISSING,
    EDGE_PORTFOLIO_PRUNED,
    TRAJECTORY_ROUTE_MISMATCH,
    SUPPORT_ROOT_MISSING,
    POST_SUPPORT_STATE_MISMATCH,
    OTHER_PROVEN
}
