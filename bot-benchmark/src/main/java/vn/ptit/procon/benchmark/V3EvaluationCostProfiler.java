package vn.ptit.procon.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.CoupledCompetitiveBaseline;
import vn.ptit.procon.planner.CoupledCompetitiveRollout;
import vn.ptit.procon.planner.NextDayHarvestCapacityCalculator;
import vn.ptit.procon.planner.OpponentCommitmentForecast;
import vn.ptit.procon.planner.OpponentIntentConfig;
import vn.ptit.procon.planner.OpponentIntentForecaster;
import vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights;
import vn.ptit.procon.planner.SemiCommitmentForecastEvaluator;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.FrozenObjectiveEvaluator;

/**
 * PART 9: WHERE inside one objective evaluation the milliseconds go.
 *
 * <p>The corpus profiler establishes that V3's LARGE-state cost is dominated by the terminal stage, and that
 * the terminal stage is 64 calls to {@link FrozenObjectiveEvaluator#evaluate}. This tool takes the next step
 * and decomposes ONE such call, by timing the same public collaborators the evaluator itself calls, in the
 * same order, on the same rebuilt live state and the same real V2/R3 plan:
 *
 * <ol>
 *   <li>{@link PlanValidator#validate},</li>
 *   <li>{@link DaySimulator#simulate},</li>
 *   <li>{@link SemiCommitmentForecastEvaluator#evaluate},</li>
 *   <li>{@link CoupledCompetitiveRollout#evaluate},</li>
 *   <li>{@link NextDayHarvestCapacityCalculator#evaluate}.</li>
 * </ol>
 *
 * <p>The remainder — the whole-evaluate median minus those five — is the evaluator's own bookkeeping (the
 * event streams in its private {@code base}, the plan signature and the hybrid assembly). Nothing in
 * {@code src/main} is touched, and nothing here is used by the live bot: this class only reads.
 *
 * <p>The per-stage figures are medians over repeated calls on a warmed JIT, because a single call at this
 * scale is dominated by class loading and by the first-call interpreter cost.
 */
public final class V3EvaluationCostProfiler {

    private static final int DEFAULT_WARMUP = 5;
    private static final int DEFAULT_REPEATS = 15;

    private final int warmup;
    private final int repeats;

    public V3EvaluationCostProfiler() {
        this(DEFAULT_WARMUP, DEFAULT_REPEATS);
    }

    /**
     * A 24x24/8-agent day costs seconds per evaluation, so the sample count has to be tunable: 20 calls per
     * stage there would cost more wall-clock than the finding is worth.
     */
    public V3EvaluationCostProfiler(int warmup, int repeats) {
        if (warmup < 0 || repeats < 1) {
            throw new IllegalArgumentException("warmup must be >= 0 and repeats >= 1");
        }
        this.warmup = warmup;
        this.repeats = repeats;
    }

    /** Microsecond costs of one evaluation, decomposed. */
    public record Cost(String matchId, int day, String sizeClass, int agentCount, int stepBudget,
            long wholeMicros, long validateMicros, long simulateMicros, long semiMicros,
            long coupledMicros, long nextDayMicros, int simulatedEvents, int collectedEvents) {

        public long accountedMicros() {
            return validateMicros + simulateMicros + semiMicros + coupledMicros + nextDayMicros;
        }

        public long remainderMicros() {
            return Math.max(0, wholeMicros - accountedMicros());
        }

        private double percent(long part) {
            return wholeMicros == 0 ? 0.0 : 100.0 * part / wholeMicros;
        }

        public String line() {
            return String.format(Locale.ROOT,
                    "EVAL_COST match=%s day=%d size=%s agents=%d stepBudget=%d wholeMicros=%d"
                            + " validateMicros=%d(%.1f%%) simulateMicros=%d(%.1f%%) semiMicros=%d(%.1f%%)"
                            + " coupledMicros=%d(%.1f%%) nextDayMicros=%d(%.1f%%) remainderMicros=%d(%.1f%%)"
                            + " events=%d collected=%d",
                    matchId, day, sizeClass, agentCount, stepBudget, wholeMicros,
                    validateMicros, percent(validateMicros), simulateMicros, percent(simulateMicros),
                    semiMicros, percent(semiMicros), coupledMicros, percent(coupledMicros),
                    nextDayMicros, percent(nextDayMicros), remainderMicros(), percent(remainderMicros()),
                    simulatedEvents, collectedEvents);
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

    /** Decomposes one evaluation of the real V2/R3 plan on one rebuilt live day. */
    public Cost measure(V3CorpusDay day) {
        DayState state = day.rebuild();
        TeamPlan plan = new JointTeamBeamR3Planner().plan(state);

        PlanValidator validator = new PlanValidator();
        DaySimulator simulator = new DaySimulator();
        var forecast = OpponentCommitmentForecast.annotate(new OpponentIntentForecaster().forecast(state));
        CoupledCompetitiveRollout rollout =
                CoupledCompetitiveRollout.forState(state, OpponentIntentConfig.defaults());
        CoupledCompetitiveBaseline baseline = rollout.baseline();
        NextDayHarvestCapacityCalculator nextDay = NextDayHarvestCapacityCalculator.forState(state);
        FrozenObjectiveEvaluator whole = new FrozenObjectiveEvaluator(state);

        ValidDaySimulationResult valid = (ValidDaySimulationResult) simulator.simulate(state, plan);
        long wholeMicros = medianMicros(() -> whole.evaluate(plan));
        long validateMicros = medianMicros(() -> validator.validate(state, plan));
        long simulateMicros = medianMicros(() -> simulator.simulate(state, plan));
        long semiMicros = medianMicros(() -> new SemiCommitmentForecastEvaluator().evaluate(
                state, valid, forecast, SemiCommitmentAdjustmentWeights.defaults()));
        long coupledMicros = medianMicros(() -> rollout.evaluate(baseline, valid));
        long nextDayMicros = medianMicros(() -> nextDay.evaluate(valid));

        long collected = valid.events().stream()
                .filter(vn.ptit.procon.engine.UdonCollectedEvent.class::isInstance).count();
        return new Cost(day.matchId(), day.day(), day.sizeClass(), day.agentCount(), day.stepBudget(),
                wholeMicros, validateMicros, simulateMicros, semiMicros, coupledMicros, nextDayMicros,
                valid.events().size(), (int) collected);
    }

    /** The cohort verdict PART 9's hypothesis must be argued from: HEAVY = MEDIUM + LARGE. */
    public static String summary(List<Cost> costs) {
        List<Cost> large = costs.stream()
                .filter(cost -> cost.agentCount() >= 6 && cost.stepBudget() >= 60)
                .toList();
        List<Cost> scope = large.isEmpty() ? costs : large;
        if (scope.isEmpty()) {
            return "EVAL_COST_SUMMARY cohort=NONE states=0";
        }
        long whole = 0, validate = 0, simulate = 0, semi = 0, coupled = 0, nextDay = 0, remainder = 0;
        for (Cost cost : scope) {
            whole += cost.wholeMicros();
            validate += cost.validateMicros();
            simulate += cost.simulateMicros();
            semi += cost.semiMicros();
            coupled += cost.coupledMicros();
            nextDay += cost.nextDayMicros();
            remainder += cost.remainderMicros();
        }
        int states = scope.size();
        String dominant = "REMAINDER";
        long best = remainder;
        if (validate > best) { dominant = "VALIDATE"; best = validate; }
        if (simulate > best) { dominant = "SIMULATE"; best = simulate; }
        if (semi > best) { dominant = "SEMI_COMMITMENT"; best = semi; }
        if (coupled > best) { dominant = "COUPLED_ROLLOUT"; best = coupled; }
        if (nextDay > best) { dominant = "NEXT_DAY_CAPACITY"; }
        return String.format(Locale.ROOT,
                "EVAL_COST_SUMMARY cohort=%s states=%d meanWholeMicros=%d meanValidateMicros=%d"
                        + " meanSimulateMicros=%d meanSemiMicros=%d meanCoupledMicros=%d"
                        + " meanNextDayMicros=%d meanRemainderMicros=%d dominant=%s",
                large.isEmpty() ? "ALL" : "HEAVY", states, whole / states, validate / states,
                simulate / states, semi / states, coupled / states, nextDay / states,
                remainder / states, dominant);
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
        System.out.println("EVAL_COST_LOADED root=" + root + " days=" + days.size()
                + " match=" + (match.isEmpty() ? "ALL" : match)
                + " warmup=" + warmup + " repeats=" + repeats);
        List<Cost> costs = new ArrayList<>();
        V3EvaluationCostProfiler profiler = new V3EvaluationCostProfiler(warmup, repeats);
        for (V3CorpusDay day : days) {
            Cost cost = profiler.measure(day);
            costs.add(cost);
            System.out.println(cost.line());
        }
        System.out.println(summary(costs));
    }
}
