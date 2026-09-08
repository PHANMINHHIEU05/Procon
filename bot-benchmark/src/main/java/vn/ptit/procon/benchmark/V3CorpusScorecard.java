package vn.ptit.procon.benchmark;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;

/**
 * The full live-state scorecard (PART 6), computed from replayed days only.
 *
 * <p>Every rate is reported with its denominator, and RAW is never merged with SAFE. Days on which raw V3
 * arrived at V2/R3's own plan are counted separately as {@code samePhysical}: they are ties by
 * construction and inflate a naive non-loss rate.
 */
public final class V3CorpusScorecard {

    /** The live shadow budget the runtime enforces; used here only to count would-be timeouts. */
    public static final long LIVE_BUDGET_MILLIS = 4000;

    /** PART 20/21 admissibility thresholds, quoted here so a report can never invent its own bar. */
    public static final int MIN_TOTAL_STATES = 28;
    public static final int MIN_SMALL_STATES = 8;
    public static final int MIN_MEDIUM_STATES = 8;
    public static final int MIN_LARGE_STATES = 8;
    public static final double MIN_RAW_NON_LOSS_RATE = 0.80;
    public static final long LARGE_P90_HARD_MILLIS = 3000;
    public static final long LARGE_P90_PREFERRED_MILLIS = 2000;

    private final List<V3CorpusReplayResult> results;

    public V3CorpusScorecard(List<V3CorpusReplayResult> results) {
        this.results = List.copyOf(results);
    }

    public List<V3CorpusReplayResult> results() {
        return results;
    }

    /**
     * One named cohort of replayed days. Everything is a count or a quantile over that cohort, so any
     * figure in a report can be re-derived from the per-day {@code CORPUS_DAY} lines alone.
     */
    public record Cohort(
            String name,
            int states,
            int rawWins,
            int rawTies,
            int rawLosses,
            int safeWins,
            int safeTies,
            int safeLosses,
            int samePhysical,
            int fallbacks,
            int deadlineExceeded,
            int overLiveBudget,
            int pathfindingDirty,
            int parityMismatches,
            double meanOwnDelta,
            double medianOwnDelta,
            double meanHybridDelta4,
            double medianHybridDelta4,
            long p50Millis,
            long p90Millis,
            long p95Millis,
            long maxMillis) {

        public double rawWinRate() {
            return states == 0 ? 0.0 : (double) rawWins / states;
        }

        /** Non-loss counts ties as acceptable: it is the PART 20 gate's own definition. */
        public double rawNonLossRate() {
            return states == 0 ? 0.0 : (double) (states - rawLosses) / states;
        }

        public String line() {
            return String.format(Locale.ROOT,
                    "SCORECARD cohort=%s states=%d rawW=%d rawT=%d rawL=%d rawWinRate=%.3f"
                            + " rawNonLossRate=%.3f safeW=%d safeT=%d safeL=%d samePhysical=%d"
                            + " fallbacks=%d meanOwnDelta=%.2f medianOwnDelta=%.1f meanHybridDelta4=%.2f"
                            + " medianHybridDelta4=%.1f p50Millis=%d p90Millis=%d p95Millis=%d"
                            + " maxMillis=%d deadlineExceeded=%d overLiveBudget=%d pathfindingDirty=%d"
                            + " parityMismatches=%d",
                    name, states, rawWins, rawTies, rawLosses, rawWinRate(), rawNonLossRate(), safeWins,
                    safeTies, safeLosses, samePhysical, fallbacks, meanOwnDelta, medianOwnDelta,
                    meanHybridDelta4, medianHybridDelta4, p50Millis, p90Millis, p95Millis, maxMillis,
                    deadlineExceeded, overLiveBudget, pathfindingDirty, parityMismatches);
        }
    }

    /**
     * The set the {@code large p90} promotion gate is evaluated over: every state from the
     * "6-agent/60-step" shape upwards, i.e. MEDIUM and LARGE together. Deliberately WIDER than the
     * LARGE cohort alone — splitting the old binary taxonomy into three must never let a heavy state
     * escape the latency gate it used to be inside.
     */
    public Cohort heavy() {
        return cohort("HEAVY(MEDIUM+LARGE)", results.stream()
                .filter(V3CorpusScorecard::isHeavy)
                .toList());
    }

    private static boolean isHeavy(V3CorpusReplayResult result) {
        return result.agentCount() >= 6 && result.stepBudget() >= 60;
    }

    public Cohort overall() {
        return cohort("ALL", results);
    }

    public Cohort sizeClass(String sizeClass) {
        return cohort(sizeClass, results.stream()
                .filter(result -> result.sizeClass().equals(sizeClass))
                .toList());
    }

    /**
     * SMALL / MEDIUM / LARGE, always all three rows even when one is empty: an absent cohort is itself
     * evidence, and section 45 requires at least 8 states in each before promotion is even considered.
     */
    public List<Cohort> bySizeClass() {
        return List.of(sizeClass("SMALL"), sizeClass("MEDIUM"), sizeClass("LARGE"));
    }

    public List<Cohort> byAgentCount() {
        return grouped(result -> "agents=" + result.agentCount());
    }

    public List<Cohort> byStepBudget() {
        return grouped(result -> "steps=" + result.stepBudget());
    }

    public List<Cohort> byMatch() {
        return grouped(V3CorpusReplayResult::matchId);
    }

    public List<Cohort> byMapSize() {
        return grouped(result -> result.mapWidth() + "x" + result.mapHeight());
    }

    private List<Cohort> grouped(java.util.function.Function<V3CorpusReplayResult, String> key) {
        Map<String, List<V3CorpusReplayResult>> buckets = new TreeMap<>();
        for (V3CorpusReplayResult result : results) {
            buckets.computeIfAbsent(key.apply(result), ignored -> new ArrayList<>()).add(result);
        }
        List<Cohort> cohorts = new ArrayList<>();
        buckets.forEach((name, bucket) -> cohorts.add(cohort(name, bucket)));
        return cohorts;
    }

    private static Cohort cohort(String name, List<V3CorpusReplayResult> bucket) {
        List<Double> ownDeltas = new ArrayList<>();
        List<Double> hybridDeltas = new ArrayList<>();
        List<Long> millis = new ArrayList<>();
        int rawWins = 0;
        int rawTies = 0;
        int rawLosses = 0;
        int safeWins = 0;
        int safeTies = 0;
        int safeLosses = 0;
        int samePhysical = 0;
        int fallbacks = 0;
        int deadlineExceeded = 0;
        int overLiveBudget = 0;
        int pathfindingDirty = 0;
        int parityMismatches = 0;
        for (V3CorpusReplayResult result : bucket) {
            V3ShadowEvaluation value = result.evaluation();
            ownDeltas.add((double) value.ownDelta());
            hybridDeltas.add((double) value.hybridDelta4());
            millis.add(value.planningMillis());
            rawWins += value.rawVerdict() == V3ShadowEvaluation.Verdict.WIN ? 1 : 0;
            rawTies += value.rawVerdict() == V3ShadowEvaluation.Verdict.TIE ? 1 : 0;
            rawLosses += value.rawVerdict() == V3ShadowEvaluation.Verdict.LOSS ? 1 : 0;
            safeWins += value.safeVerdict() == V3ShadowEvaluation.Verdict.WIN ? 1 : 0;
            safeTies += value.safeVerdict() == V3ShadowEvaluation.Verdict.TIE ? 1 : 0;
            safeLosses += value.safeVerdict() == V3ShadowEvaluation.Verdict.LOSS ? 1 : 0;
            samePhysical += value.samePhysicalPlan() ? 1 : 0;
            fallbacks += value.v3FallbackUsed() ? 1 : 0;
            deadlineExceeded += value.deadlineBudgetExceeded() ? 1 : 0;
            overLiveBudget += result.exceedsLiveBudget(LIVE_BUDGET_MILLIS) ? 1 : 0;
            pathfindingDirty += value.pathfindingFree() ? 0 : 1;
            parityMismatches += result.parityOk() ? 0 : 1;
        }
        return new Cohort(name, bucket.size(), rawWins, rawTies, rawLosses, safeWins, safeTies,
                safeLosses, samePhysical, fallbacks, deadlineExceeded, overLiveBudget, pathfindingDirty,
                parityMismatches, mean(ownDeltas), percentile(ownDeltas, 50), mean(hybridDeltas),
                percentile(hybridDeltas, 50), millisPercentile(millis, 50), millisPercentile(millis, 90),
                millisPercentile(millis, 95), millis.stream().mapToLong(Long::longValue).max().orElse(0));
    }

    private static double mean(List<Double> values) {
        return values.isEmpty() ? 0.0
                : values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }

    /** Nearest-rank percentile: with tiny corpora an interpolating definition would invent values. */
    private static double percentile(List<Double> values, int percentile) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static long millisPercentile(List<Long> values, int percentile) {
        List<Double> asDoubles = new ArrayList<>();
        values.forEach(value -> asDoubles.add((double) value));
        return Math.round(percentile(asDoubles, percentile));
    }

    /**
     * The PART 20/21 promotion gates, evaluated but never enforced here: this class reports, and the
     * decision to promote stays with the operator reading the report.
     */
    public Map<String, String> gates() {
        Cohort all = overall();
        Cohort large = sizeClass("LARGE");
        Cohort heavy = heavy();
        Map<String, String> gates = new LinkedHashMap<>();
        gates.put("COVERAGE_TOTAL_STATES", gate(all.states() >= MIN_TOTAL_STATES,
                all.states() + "/" + MIN_TOTAL_STATES));
        gates.put("COVERAGE_SMALL_STATES", gate(sizeClass("SMALL").states() >= MIN_SMALL_STATES,
                sizeClass("SMALL").states() + "/" + MIN_SMALL_STATES));
        gates.put("COVERAGE_MEDIUM_STATES", gate(sizeClass("MEDIUM").states() >= MIN_MEDIUM_STATES,
                sizeClass("MEDIUM").states() + "/" + MIN_MEDIUM_STATES));
        gates.put("COVERAGE_LARGE_STATES", gate(large.states() >= MIN_LARGE_STATES,
                large.states() + "/" + MIN_LARGE_STATES));
        gates.put("RAW_NON_LOSS_RATE", gate(all.rawNonLossRate() >= MIN_RAW_NON_LOSS_RATE,
                String.format(Locale.ROOT, "%.3f>=%.2f", all.rawNonLossRate(), MIN_RAW_NON_LOSS_RATE)));
        gates.put("MEDIAN_HYBRID_DELTA4_POSITIVE", gate(all.medianHybridDelta4() > 0,
                String.format(Locale.ROOT, "%.1f>0", all.medianHybridDelta4())));
        gates.put("SAFE_ZERO_LOSSES", gate(all.safeLosses() == 0, all.safeLosses() + "==0"));
        gates.put("LARGE_P90_LATENCY", gate(heavy.p90Millis() <= LARGE_P90_HARD_MILLIS,
                heavy.p90Millis() + "<=" + LARGE_P90_HARD_MILLIS));
        gates.put("LARGE_P90_PREFERRED", gate(heavy.p90Millis() <= LARGE_P90_PREFERRED_MILLIS,
                heavy.p90Millis() + "<=" + LARGE_P90_PREFERRED_MILLIS));
        gates.put("PATHFINDING_FREE_SEARCH", gate(all.pathfindingDirty() == 0,
                all.pathfindingDirty() + "==0"));
        gates.put("V2_PARITY", gate(all.parityMismatches() == 0, all.parityMismatches() + "==0"));
        return gates;
    }

    private static String gate(boolean satisfied, String evidence) {
        return (satisfied ? "PASS" : "FAIL") + " " + evidence;
    }

    /** PART 7 classification of the heavy states: the bottleneck map the hypothesis must be built on. */
    public Map<String, List<String>> largeStateClassification() {
        Map<String, List<String>> classes = new LinkedHashMap<>();
        for (V3CorpusReplayResult result : results) {
            if (!isHeavy(result)) {
                continue;
            }
            V3ShadowEvaluation value = result.evaluation();
            boolean slow = value.planningMillis() > LARGE_P90_PREFERRED_MILLIS;
            String quality = switch (value.rawVerdict()) {
                case WIN -> "QUALITY_WIN";
                case TIE -> "QUALITY_TIE";
                case LOSS -> "QUALITY_LOSS";
            };
            classes.computeIfAbsent(quality + (slow ? "_SLOW" : "_FAST"), ignored -> new ArrayList<>())
                    .add(result.matchId() + "/day-" + result.day() + "@" + value.planningMillis() + "ms");
        }
        return classes;
    }

    /**
     * The same classification over EVERY cohort, keyed {@code <COHORT>_<QUALITY>_<SPEED>}. Section 19
     * runs the earliest-divergence audit on RAW-loss states wherever they occur, so a quality-first
     * iteration must be able to enumerate the losses of SMALL and MEDIUM too, not just LARGE.
     */
    public Map<String, List<String>> stateClassification() {
        Map<String, List<String>> classes = new LinkedHashMap<>();
        for (V3CorpusReplayResult result : results) {
            V3ShadowEvaluation value = result.evaluation();
            boolean slow = value.planningMillis() > LARGE_P90_PREFERRED_MILLIS;
            String quality = switch (value.rawVerdict()) {
                case WIN -> "QUALITY_WIN";
                case TIE -> "QUALITY_TIE";
                case LOSS -> "QUALITY_LOSS";
            };
            classes.computeIfAbsent(result.sizeClass() + "_" + quality + (slow ? "_SLOW" : "_FAST"),
                            ignored -> new ArrayList<>())
                    .add(result.matchId() + "/day-" + result.day() + "@" + value.planningMillis() + "ms");
        }
        return classes;
    }

    public void print(PrintStream out) {
        for (V3CorpusReplayResult result : results) {
            out.println(result.line());
        }
        out.println(overall().line());
        bySizeClass().forEach(cohort -> out.println(cohort.line()));
        out.println(heavy().line());
        byAgentCount().forEach(cohort -> out.println(cohort.line()));
        byStepBudget().forEach(cohort -> out.println(cohort.line()));
        byMapSize().forEach(cohort -> out.println(cohort.line()));
        byMatch().forEach(cohort -> out.println(cohort.line()));
        largeStateClassification().forEach((name, days) ->
                out.println("LARGE_CLASS " + name + " count=" + days.size() + " days=" + days));
        stateClassification().forEach((name, days) ->
                out.println("STATE_CLASS " + name + " count=" + days.size() + " days=" + days));
        gates().forEach((name, verdict) -> out.println("GATE " + name + " " + verdict));
    }
}
