package vn.ptit.procon.planner.v2;

/** Supported practice-map tiers, ordered from smallest to largest. */
public enum R3MapTier {
    P08(8), P12(12), P16(16), P24(24), P32(32);

    private final int upperDimension;

    R3MapTier(int upperDimension) { this.upperDimension = upperDimension; }

    public int upperDimension() { return upperDimension; }

    public static R3MapTier forDimensions(int width, int height) {
        int dimension = Math.max(width, height);
        for (R3MapTier tier : values()) {
            if (dimension <= tier.upperDimension) return tier;
        }
        return P32;
    }
}
