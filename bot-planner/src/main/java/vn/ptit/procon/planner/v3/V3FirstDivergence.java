package vn.ptit.procon.planner.v3;

/**
 * PART 9 of Phase 2.4: the closed set of first-divergence classifications.
 *
 * <p>Exactly one value is reported per fixture and it must be derived from an observed search, never
 * from a plausible story about one.
 */
public enum V3FirstDivergence {

    /** The V2 transition is not an edge V3 can represent at all. */
    REPRESENTATION_EDGE,
    /** V3 cannot generate or retain the refuel/support root the V2 plan depends on. */
    SUPPORT_ROOT,
    /** The root exists but the state V3 continues from is not the state V2 continues from. */
    POST_SUPPORT_STATE,
    ALLOCATION_GENERATION,
    ALLOCATION_RETENTION,
    /** The edge is representable and legal but the expansion loop never offered it. */
    CHILD_GENERATION,
    /** The edge was offered but truncated by a per-patrol or per-state child quota. */
    CHILD_CAP,
    DEDUP,
    DOMINANCE,
    PARTIAL_ORDERING,
    BEAM,
    EXPANDED_CAP,
    STOP,
    TERMINALIZATION,
    MATERIALIZATION,
    TERMINAL_EVALUATION,
    NO_DIVERGENCE,
    OTHER_PROVEN
}
