package vn.ptit.procon.planner;

import java.util.List;

/** Frozen production defaults. Unknown map sizes use the nearest larger generic tier. */
public final class AdaptiveR3Policy {
    private static final List<R3PlannerProfile> DEFAULTS = List.of(
            new R3PlannerProfile("P08", 16, 512, 48, 768, 3_200, 4_200, 0),
            // Pareto paths improved the P12 live holdout; it stays scoped to this profile until
            // it proves a non-regression on P08/P16 as well.
            new R3PlannerProfile("P12", 16, 384, 40, 640, 3_200, 4_200, 2),
            new R3PlannerProfile("P16", 14, 256, 32, 512, 3_200, 4_200, 0),
            new R3PlannerProfile("P24", 12, 160, 24, 320, 3_200, 4_200, 0),
            new R3PlannerProfile("P32", 12, 160, 24, 320, 3_200, 4_200, 0),
            // Same proven limits as P08, but a distinct immutable fingerprint: the 4-agent
            // shape has its own 5/5 three-Hard-Bot holdout and must never inherit 6-agent fuel
            // pacing assumptions.
            new R3PlannerProfile("P08_4", 16, 512, 48, 768, 3_200, 4_200, 0));

    private AdaptiveR3Policy() {}

    public static List<R3PlannerProfile> defaults() { return DEFAULTS; }

    public static R3PlannerProfile select(MatchShape shape) {
        int size = Math.max(shape.width(), shape.height());
        if (size <= 8) return shape.agents() <= 4 ? DEFAULTS.get(5) : DEFAULTS.get(0);
        if (size <= 12) return DEFAULTS.get(1);
        if (size <= 16) return DEFAULTS.get(2);
        if (size <= 24) return DEFAULTS.get(3);
        return DEFAULTS.get(4);
    }
}
