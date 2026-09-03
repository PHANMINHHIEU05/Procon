package vn.ptit.procon.planner.v2;

/**
 * Search-only toggles used to reproduce R1 and isolate R2 changes. The R2 branching flag is
 * retained for audit completeness, but current source audit found its branch menu control-equivalent.
 * Terminal scoring, refuel roots, movement, and runtime behavior are deliberately outside this policy.
 */
public record V2SearchPolicy(
        boolean r2PartialOrdering,
        boolean r2PotentialBound,
        boolean r2StopTerminalHandling,
        boolean r2SafeDominance,
        boolean r2BranchingPolicy) {

    public static final V2SearchPolicy R1_CONTROL = new V2SearchPolicy(false, false, false, false, false);
    public static final V2SearchPolicy FULL_R2 = new V2SearchPolicy(true, true, true, true, true);

    public static V2SearchPolicy r1ControlWithPartialOrdering() {
        return new V2SearchPolicy(true, false, false, false, false);
    }

    public static V2SearchPolicy r1ControlWithPotentialBound() {
        return new V2SearchPolicy(false, true, false, false, false);
    }

    public static V2SearchPolicy r1ControlWithStopTerminalHandling() {
        return new V2SearchPolicy(false, false, true, false, false);
    }

    public static V2SearchPolicy r1ControlWithSafeDominance() {
        return new V2SearchPolicy(false, false, false, true, false);
    }

    public static V2SearchPolicy r1ControlWithBranchingPolicy() {
        return new V2SearchPolicy(false, false, false, false, true);
    }
}
