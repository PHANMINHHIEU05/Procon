package vn.ptit.procon.benchmark;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.V3Phase24Fixtures;
import vn.ptit.procon.planner.v3.V3ShadowPlanner;

/**
 * The CPU cost of watching: how much slower is a V2/R3 plan while a V3 shadow task runs beside it?
 *
 * <p>Phase 2.7 puts the shadow on its own worker thread, after the accepted POST. That removes the shadow
 * from the submission path in program order, but not from the machine — the two planners share cores. This
 * audit measures the part that is left.
 *
 * <p>Measurement is INTERLEAVED, and that is the whole method: the two conditions alternate in short blocks
 * ({@link #ROUNDS} rounds of {@link #BLOCK} plans each) and their samples are pooled. A first attempt used
 * one long pass per condition and the numbers were governed by JIT warm-up drift, not by the shadow — the
 * two shadow-free passes of one fixture differed by more than 100%. Alternating charges that drift to both
 * conditions equally; the residual is reported as {@code driftPercent} so the reader can see what is left.
 *
 * <p>The module deliberately depends on {@code bot-planner} only, so no protocol or HTTP type is even on
 * this classpath: the benchmark measures planners, and could not submit an action if it tried.
 */
public final class V3ShadowContentionBenchmark {

    /** Alternations of the two conditions. */
    private static final int ROUNDS = 5;

    /** Production plans measured per block, per condition. */
    private static final int BLOCK = 5;

    /** Plans discarded before measuring. Generous, because a cold JIT drifts far more than a shadow costs. */
    private static final int WARMUP = 12;

    /** The shadow's own bounded wall budget — the runtime default, not a production action deadline. */
    private static final long SHADOW_BUDGET_MILLIS = 4_000;

    /** A pause at each block boundary, so one block's garbage is not collected inside the next. */
    private static final long SETTLE_MILLIS = 100;

    private V3ShadowContentionBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, DayState> fixtures = new LinkedHashMap<>();
        fixtures.put("live-like-m6861", V3Phase24Fixtures.liveLike());
        fixtures.put("large-6-agent-60-step", V3Phase24Fixtures.currentLarge());
        fixtures.put("LARGE_DENSE", V3Phase24Fixtures.largeDense());

        // Both planners print their own diagnostics. Silence stdout for the whole audit and write the
        // report through the original stream, so no planner line can interleave with a measurement.
        PrintStream report = System.out;
        try (PrintStream suppressed = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(suppressed);
            report.printf(Locale.ROOT, "V3_SHADOW_CONTENTION_CONFIG rounds=%d block=%d samplesPerCondition=%d"
                            + " warmup=%d shadowBudgetMillis=%d cores=%d%n", ROUNDS, BLOCK, ROUNDS * BLOCK,
                    WARMUP, SHADOW_BUDGET_MILLIS, Runtime.getRuntime().availableProcessors());
            for (Map.Entry<String, DayState> fixture : fixtures.entrySet()) {
                audit(report, fixture.getKey(), fixture.getValue());
            }
        } finally {
            System.setOut(report);
        }
    }

    /** One fixture: alternating blocks of V2 alone and V2 beside a looping shadow, then one report line. */
    private static void audit(PrintStream report, String fixture, DayState state) throws Exception {
        JointTeamBeamR3Planner production = new JointTeamBeamR3Planner();
        TeamPlan incumbent = production.plan(state);
        for (int warmup = 0; warmup < WARMUP; warmup++) {
            production.plan(state);
        }

        double[] alone = new double[ROUNDS * BLOCK];
        double[] beside = new double[ROUNDS * BLOCK];
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        for (int round = 0; round < ROUNDS; round++) {
            measureInto(production, state, alone, round * BLOCK);
            Thread.sleep(SETTLE_MILLIS);
            Shadow shadow = Shadow.started(state, incumbent, evaluations, failures);
            try {
                measureInto(production, state, beside, round * BLOCK);
            } finally {
                shadow.stop();
            }
            Thread.sleep(SETTLE_MILLIS);
        }

        Pass alonePass = Pass.of(alone);
        Pass besidePass = Pass.of(beside);
        int half = ROUNDS / 2 * BLOCK;
        report.printf(Locale.ROOT, "V3_SHADOW_CONTENTION fixture=%s %s %s meanDeltaMs=%s meanDeltaPercent=%s"
                        + " medianDeltaPercent=%s p95DeltaPercent=%s driftPercent=%s shadowEvaluations=%d"
                        + " shadowFailures=%d%n", fixture, alonePass.render("v2Alone"),
                besidePass.render("v2BesideShadow"), millis(besidePass.mean() - alonePass.mean()),
                percent(besidePass.mean(), alonePass.mean()),
                percent(besidePass.median(), alonePass.median()),
                percent(besidePass.quantile(0.95), alonePass.quantile(0.95)),
                percent(meanOf(alone, half, alone.length), meanOf(alone, 0, half)), evaluations.get(),
                failures.get());
    }

    /** {@link #BLOCK} production plans of the very same state, each timed on its own. */
    private static void measureInto(JointTeamBeamR3Planner production, DayState state, double[] samples,
            int from) {
        for (int index = from; index < from + BLOCK; index++) {
            long started = System.nanoTime();
            TeamPlan plan = production.plan(state);
            long elapsed = System.nanoTime() - started;
            if (plan.actionsFor(state.agents().get(0).id()) == null) {
                throw new IllegalStateException("The production planner left an agent unplanned");
            }
            samples[index] = elapsed / 1_000_000.0;
        }
    }

    /** The runtime's shadow worker in miniature: one thread, one evaluation at a time, no authority. */
    private static final class Shadow {

        private final AtomicBoolean stopping = new AtomicBoolean();
        private final Thread worker;

        private Shadow(DayState state, TeamPlan incumbent, AtomicInteger completed, AtomicInteger failed) {
            worker = new Thread(() -> {
                V3ShadowPlanner planner = new V3ShadowPlanner();
                while (!stopping.get()) {
                    try {
                        planner.evaluate(state, incumbent,
                                V3ShadowPlanner.shadowConfig(SHADOW_BUDGET_MILLIS));
                        completed.incrementAndGet();
                    } catch (RuntimeException failure) {
                        failed.incrementAndGet();
                    }
                }
            }, "v3-shadow-contention");
            worker.setDaemon(true);
        }

        private static Shadow started(DayState state, TeamPlan incumbent, AtomicInteger completed,
                AtomicInteger failed) {
            Shadow shadow = new Shadow(state, incumbent, completed, failed);
            shadow.worker.start();
            return shadow;
        }

        /** Ask the shadow to stop and WAIT for it, so its work cannot bleed into the next alone block. */
        private void stop() throws InterruptedException {
            stopping.set(true);
            worker.join(8 * SHADOW_BUDGET_MILLIS);
            if (worker.isAlive()) {
                throw new IllegalStateException("The shadow evaluation outlived its own bounded budget");
            }
        }
    }

    /** One condition's pooled plan durations in milliseconds, sorted so the quantiles are a lookup. */
    private record Pass(double[] sorted) {

        private static Pass of(double[] samples) {
            double[] sorted = samples.clone();
            Arrays.sort(sorted);
            return new Pass(sorted);
        }

        private double mean() {
            return meanOf(sorted, 0, sorted.length);
        }

        private double median() {
            return quantile(0.5);
        }

        private double quantile(double fraction) {
            int index = (int) Math.ceil(fraction * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
        }

        private String render(String label) {
            return String.format(Locale.ROOT, "%sMeanMs=%s %sMedianMs=%s %sP95Ms=%s %sMaxMs=%s", label,
                    millis(mean()), label, millis(median()), label, millis(quantile(0.95)), label,
                    millis(sorted[sorted.length - 1]));
        }
    }

    private static double meanOf(double[] samples, int from, int to) {
        double total = 0;
        for (int index = from; index < to; index++) {
            total += samples[index];
        }
        return to > from ? total / (to - from) : 0;
    }

    private static String millis(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** The measured value against its baseline, as a signed percentage. */
    private static String percent(double value, double baseline) {
        return baseline <= 0 ? "NA"
                : String.format(Locale.ROOT, "%+.1f", 100 * (value - baseline) / baseline);
    }
}
