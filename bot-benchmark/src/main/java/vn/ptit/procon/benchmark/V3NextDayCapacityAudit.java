package vn.ptit.procon.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.NextDayHarvestCapacityCalculator;
import vn.ptit.procon.planner.PatrolNextDayHarvestCapacity;
import vn.ptit.procon.planner.TeamNextDayHarvestCapacity;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;

/**
 * PART 9 (causal audit): WHY one {@code NextDayHarvestCapacityCalculator.evaluate} call costs ~28 ms.
 *
 * <p>The cost profiler establishes that the next-day stage is ~96% of one objective evaluation. That is a
 * measurement, not a cause. This tool separates the two things the class does — the per-state cache build
 * ({@code forState}) and the per-plan subset DP ({@code evaluate}) — and then reports the DP's own work
 * counter, {@code totalDpStatesEvaluated}, against the number of DP keys the DP can possibly hold.
 *
 * <p>The DP is keyed by {@code (visitedMask, lastSpot)}, so it has at most {@code 2^n * n} keys for {@code n}
 * static opportunities — 384 for a 6-spot map. Each key holds a Pareto set of {@code (steps, fuel)} labels.
 * The search enqueues a key ONCE PER ACCEPTED LABEL but, on every dequeue, re-expands EVERY label the key
 * currently holds. So a key that ends with {@code A} labels is expanded about {@code A(A+1)/2} times instead
 * of {@code A} times, and {@code totalDpStatesEvaluated} counts exactly those expansions.
 *
 * <p>From {@code D = totalDpStatesEvaluated} and the key bound {@code K} the audit therefore derives the
 * average Pareto width {@code A ≈ sqrt(2D/K)} and the expansion count a label-once schedule would need,
 * {@code K*A ≈ sqrt(2DK)}. The ratio between them is the redundancy factor: if it is near 1 the quadratic
 * re-expansion is not the cause and the hypothesis must be something else.
 *
 * <p>Reads only. Nothing in {@code src/main} is touched and nothing here runs in the live bot.
 */
public final class V3NextDayCapacityAudit {

    private static final int DEFAULT_WARMUP = 3;
    private static final int DEFAULT_REPEATS = 9;

    private final int warmup;
    private final int repeats;

    public V3NextDayCapacityAudit() {
        this(DEFAULT_WARMUP, DEFAULT_REPEATS);
    }

    public V3NextDayCapacityAudit(int warmup, int repeats) {
        if (warmup < 0 || repeats < 1) {
            throw new IllegalArgumentException("warmup must be >= 0 and repeats >= 1");
        }
        this.warmup = warmup;
        this.repeats = repeats;
    }

    /** One audited day. Times are microseconds; counts are exact. */
    public record Audit(String matchId, int day, String sizeClass, int agentCount, int stepBudget,
            int opportunities, int remainingFutureDays, int nextDayStepBudget, int patrolCount,
            int routeCostCacheEntries, int pathfindingExecutions, long buildMicros, long evaluateMicros,
            long dpStatesEvaluated, long maxPatrolDpStates, int minimumSpots, int totalSpots,
            int totalBrands) {

        /** The DP cannot hold more than one entry per {@code (visitedMask, lastSpot)} pair. */
        public long dpKeyBound() {
            return opportunities == 0 ? 0 : (1L << opportunities) * opportunities;
        }

        /** Average Pareto width implied by a quadratic re-expansion schedule: D ~ K*A^2/2. */
        public double impliedParetoWidth() {
            long keys = dpKeyBound();
            return keys == 0 ? 0.0 : Math.sqrt(2.0 * dpStatesEvaluated / keys);
        }

        /** Expansions a label-once schedule would perform: K*A ~ sqrt(2*D*K). */
        public long labelOnceExpansions() {
            return Math.round(dpKeyBound() * impliedParetoWidth());
        }

        /** How many times more work the current schedule does than a label-once one. */
        public double redundancyFactor() {
            long once = labelOnceExpansions();
            return once == 0 ? 1.0 : (double) dpStatesEvaluated / once;
        }

        public String line() {
            return String.format(Locale.ROOT,
                    "NEXTDAY match=%s day=%d size=%s agents=%d stepBudget=%d spots=%d futureDays=%d"
                            + " nextBudget=%d patrols=%d cacheEntries=%d pathfinding=%d buildMicros=%d"
                            + " evalMicros=%d dpStates=%d maxPatrolDpStates=%d dpKeyBound=%d"
                            + " impliedParetoWidth=%.1f labelOnceExpansions=%d redundancyFactor=%.1f"
                            + " minSpots=%d totalSpots=%d totalBrands=%d",
                    matchId, day, sizeClass, agentCount, stepBudget, opportunities, remainingFutureDays,
                    nextDayStepBudget, patrolCount, routeCostCacheEntries, pathfindingExecutions,
                    buildMicros, evaluateMicros, dpStatesEvaluated, maxPatrolDpStates, dpKeyBound(),
                    impliedParetoWidth(), labelOnceExpansions(), redundancyFactor(),
                    minimumSpots, totalSpots, totalBrands);
        }
    }

    private long medianMicros(Runnable work) {
        for (int repeat = 0; repeat < warmup; repeat++) {
            work.run();
        }
        List<Long> samples = new ArrayList<>();
        for (int repeat = 0; repeat < repeats; repeat++) {
            long started = System.nanoTime();
            work.run();
            samples.add((System.nanoTime() - started) / 1000L);
        }
        samples.sort(Long::compareTo);
        return samples.get(samples.size() / 2);
    }

    /** Audits one rebuilt live day against the real V2/R3 plan for that day. */
    public Audit measure(V3CorpusDay day) {
        DayState state = day.rebuild();
        TeamPlan plan = new JointTeamBeamR3Planner().plan(state);
        ValidDaySimulationResult valid =
                (ValidDaySimulationResult) new DaySimulator().simulate(state, plan);

        NextDayHarvestCapacityCalculator calculator = NextDayHarvestCapacityCalculator.forState(state);
        long buildMicros = medianMicros(() -> NextDayHarvestCapacityCalculator.forState(state));
        long evaluateMicros = medianMicros(() -> calculator.evaluate(valid));

        TeamNextDayHarvestCapacity capacity = calculator.evaluate(valid);
        long maxPatrol = capacity.patrols().stream()
                .mapToLong(PatrolNextDayHarvestCapacity::dpStatesEvaluated).max().orElse(0L);
        return new Audit(day.matchId(), day.day(), day.sizeClass(), day.agentCount(), day.stepBudget(),
                calculator.opportunityCount(), capacity.remainingFutureDays(),
                capacity.nextDayStepBudget(), capacity.patrols().size(),
                calculator.routeCostCacheEntries(), calculator.pathfindingExecutions(),
                buildMicros, evaluateMicros, capacity.totalDpStatesEvaluated(), maxPatrol,
                capacity.minimumPatrolDistinctSpots(), capacity.totalPatrolDistinctSpotCapacity(),
                capacity.totalPatrolDistinctBrandCapacity());
    }

    /**
     * The cohort verdict. A redundancy factor well above 1 means the quadratic re-expansion schedule — not the
     * cache build and not the Pareto model itself — is what the milliseconds are spent on.
     */
    public static String summary(List<Audit> audits) {
        List<Audit> horizon = audits.stream().filter(audit -> audit.remainingFutureDays() > 0).toList();
        if (horizon.isEmpty()) {
            return "NEXTDAY_SUMMARY cohort=NONE states=0";
        }
        long build = 0, eval = 0, dpStates = 0, keyBound = 0, labelOnce = 0;
        double width = 0;
        for (Audit audit : horizon) {
            build += audit.buildMicros();
            eval += audit.evaluateMicros();
            dpStates += audit.dpStatesEvaluated();
            keyBound += audit.dpKeyBound();
            labelOnce += audit.labelOnceExpansions();
            width += audit.impliedParetoWidth();
        }
        int states = horizon.size();
        return String.format(Locale.ROOT,
                "NEXTDAY_SUMMARY cohort=FUTURE_HORIZON states=%d meanBuildMicros=%d meanEvalMicros=%d"
                        + " meanDpStates=%d meanDpKeyBound=%d meanImpliedParetoWidth=%.1f"
                        + " meanLabelOnceExpansions=%d redundancyFactor=%.1f dominantStage=%s"
                        + " projectedEvalMicros=%d",
                states, build / states, eval / states, dpStates / states, keyBound / states,
                width / states, labelOnce / states,
                labelOnce == 0 ? 1.0 : (double) dpStates / labelOnce,
                eval > build ? "SUBSET_DP" : "CACHE_BUILD",
                labelOnce == 0 || dpStates == 0 ? eval / states
                        : Math.round((double) eval / states * labelOnce / dpStates));
    }

    public static void main(String[] args) throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of(V3CorpusReplay.DEFAULT_CORPUS_DIRECTORY);
        String matchFilter = "";
        int warmup = DEFAULT_WARMUP;
        int repeats = DEFAULT_REPEATS;
        int limit = Integer.MAX_VALUE;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--corpus" -> root = java.nio.file.Path.of(args[++index]);
                case "--match" -> matchFilter = args[++index];
                case "--warmup" -> warmup = Integer.parseInt(args[++index]);
                case "--repeats" -> repeats = Integer.parseInt(args[++index]);
                case "--limit" -> limit = Integer.parseInt(args[++index]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }
        final String match = matchFilter;
        List<V3CorpusDay> days = V3CorpusDay.loadAll(root, true).stream()
                .filter(day -> match.isEmpty() || day.matchId().equals(match))
                .limit(limit)
                .toList();
        System.out.println("NEXTDAY_LOADED root=" + root + " days=" + days.size()
                + " match=" + (match.isEmpty() ? "ALL" : match)
                + " warmup=" + warmup + " repeats=" + repeats);
        List<Audit> audits = new ArrayList<>();
        V3NextDayCapacityAudit audit = new V3NextDayCapacityAudit(warmup, repeats);
        for (V3CorpusDay day : days) {
            Audit result = audit.measure(day);
            audits.add(result);
            System.out.println(result.line());
        }
        System.out.println(summary(audits));
    }
}
