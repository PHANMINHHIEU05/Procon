package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;

/**
 * PART 29: the shape of the retained terminal set, before and after mobile support.
 *
 * <p>Phase 2.4 measured a degenerate distribution on CURRENT LARGE — sixteen retained terminals, every one
 * of them {@code own=1} — which is the signature of a search that never had a second reachable
 * opportunity to offer. The point of recording quantiles rather than only the best terminal is that a
 * single lucky winner and a genuinely healthy frontier are indistinguishable from {@code bestOwn} alone.
 */
public record V3TerminalDistribution(int terminalCount, int ownMin, int ownMedian, int ownP90, int ownMax,
        int hybridMin, int hybridMedian, int hybridMax, int ownAtLeast5, int ownAtLeast10,
        int ownAtLeast14) {

    /** Reads the distribution off the terminals a search retained. */
    public static V3TerminalDistribution of(List<StrategicTerminalSnapshot> terminals) {
        Objects.requireNonNull(terminals, "Terminals must not be null");
        int[] own = terminals.stream().mapToInt(snapshot -> snapshot.evaluation().ownSemiCollections())
                .sorted().toArray();
        int[] hybrid = terminals.stream().mapToInt(snapshot -> snapshot.evaluation().hybridMarginScore4())
                .sorted().toArray();
        return new V3TerminalDistribution(own.length, min(own), quantile(own, 50), quantile(own, 90),
                max(own), min(hybrid), quantile(hybrid, 50), max(hybrid), atLeast(own, 5),
                atLeast(own, 10), atLeast(own, 14));
    }

    /** The Phase 2.4 CURRENT LARGE baseline, stated explicitly so the regression can assert the contrast. */
    public static V3TerminalDistribution collapsedBaseline(int terminalCount) {
        if (terminalCount <= 0) throw new IllegalArgumentException("A collapsed baseline still has terminals");
        return new V3TerminalDistribution(terminalCount, 1, 1, 1, 1, 4, 4, 4, 0, 0, 0);
    }

    /** True when every retained terminal scored the same single collection: the Phase 2.4 collapse. */
    public boolean collapsed() { return terminalCount > 0 && ownMax <= 1; }

    private static int min(int[] sorted) { return sorted.length == 0 ? 0 : sorted[0]; }

    private static int max(int[] sorted) { return sorted.length == 0 ? 0 : sorted[sorted.length - 1]; }

    private static int atLeast(int[] sorted, int threshold) {
        return (int) java.util.Arrays.stream(sorted).filter(value -> value >= threshold).count();
    }

    /** Nearest-rank quantile on the already sorted array; deterministic and index-stable. */
    private static int quantile(int[] sorted, int percent) {
        if (sorted.length == 0) return 0;
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
    }

    @Override
    public String toString() {
        return "terminals=" + terminalCount + " own[min=" + ownMin + " med=" + ownMedian + " p90=" + ownP90
                + " max=" + ownMax + "] hybrid[min=" + hybridMin + " med=" + hybridMedian + " max="
                + hybridMax + "] own>=5:" + ownAtLeast5 + " >=10:" + ownAtLeast10 + " >=14:" + ownAtLeast14;
    }
}
