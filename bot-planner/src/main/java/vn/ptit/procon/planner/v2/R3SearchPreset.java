package vn.ptit.procon.planner.v2;

/** Bounded structural search presets used by the optimizer and adaptive defaults. */
public enum R3SearchPreset {
    FAST(64, 256, 24, 6, 12),
    BALANCED(96, 384, 32, 8, 16),
    DEEP(112, 448, 36, 10, 20);

    private final int beamWidth;
    private final int maxExpandedStates;
    private final int maxChildren;
    private final int maxTargets;
    private final int maxStageB;

    R3SearchPreset(int beamWidth, int maxExpandedStates, int maxChildren, int maxTargets, int maxStageB) {
        this.beamWidth = beamWidth;
        this.maxExpandedStates = maxExpandedStates;
        this.maxChildren = maxChildren;
        this.maxTargets = maxTargets;
        this.maxStageB = maxStageB;
    }

    public int beamWidth() { return beamWidth; }
    public int maxExpandedStates() { return maxExpandedStates; }
    public int maxChildren() { return maxChildren; }
    public int maxTargets() { return maxTargets; }
    public int maxStageB() { return maxStageB; }
}
