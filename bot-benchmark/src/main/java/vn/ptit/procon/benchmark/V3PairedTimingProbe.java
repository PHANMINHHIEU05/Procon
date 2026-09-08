package vn.ptit.procon.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.StrategicSearchConfig;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;
import vn.ptit.procon.planner.v3.V3ShadowPlanner;

/**
 * ITERATION 6 PAIRED WALL-CLOCK PROBE — one arm, one captured day, N timed repeats in one JVM.
 *
 * <p>JVM run-to-run variance on this machine was measured LARGER than the effect under test, so a single
 * A/B sample is worthless. This tool is therefore deliberately minimal and does exactly one arm: the
 * interleaving (warm A, warm B, then A1 B1 / B2 A2 / A3 B3 / B4 A4 / A5 B5) happens ACROSS JVM launches,
 * driven by the shell, because two bytecode versions of one class cannot coexist in a single JVM. Each
 * launch pays its own warm-up so no arm inherits the other's JIT state.
 *
 * <p>Only the V3 evaluation is timed. The incumbent V2/R3 plan is computed once and reused: it is
 * arm-independent by construction (the memo lives in the V3 graph builder), so timing it would add noise
 * without adding information.
 *
 * <p>Every repeat also prints its own decision signature, so a run whose timing is quoted is simultaneously
 * shown to have produced the same decision on every repeat.
 */
public final class V3PairedTimingProbe {

    private V3PairedTimingProbe() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(V3CorpusReplay.DEFAULT_CORPUS_DIRECTORY);
        String matchFilter = "";
        String arm = "UNLABELLED";
        int dayFilter = -1;
        int repeats = 1;
        long budget = V3CorpusReplay.UNCAPPED_BUDGET_MILLIS;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--corpus" -> root = Path.of(args[++index]);
                case "--match" -> matchFilter = args[++index];
                case "--arm" -> arm = args[++index];
                case "--day" -> dayFilter = Integer.parseInt(args[++index]);
                case "--repeats" -> repeats = Integer.parseInt(args[++index]);
                case "--budget-millis" -> budget = Long.parseLong(args[++index]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }
        final String match = matchFilter;
        final int wanted = dayFilter;
        List<V3CorpusDay> days = V3CorpusDay.loadAll(root, true).stream()
                .filter(day -> match.isEmpty() || day.matchId().equals(match))
                .filter(day -> wanted < 0 || day.day() == wanted)
                .toList();
        if (days.isEmpty()) {
            System.out.println("PAIRED_EMPTY root=" + root + " match=" + match + " day=" + wanted);
            return;
        }
        StrategicSearchConfig config = V3ShadowPlanner.shadowConfig(budget);
        for (V3CorpusDay day : days) {
            measure(day, arm, repeats, config);
        }
    }

    private static void measure(V3CorpusDay day, String arm, int repeats, StrategicSearchConfig config) {
        DayState state = day.rebuild();
        TeamPlan incumbent = new JointTeamBeamR3Planner().plan(state);
        V3ShadowPlanner planner = new V3ShadowPlanner();
        planner.evaluate(state, incumbent, config);
        List<Long> samples = new ArrayList<>();
        for (int repeat = 1; repeat <= repeats; repeat++) {
            long started = System.nanoTime();
            V3ShadowEvaluation evaluation = planner.evaluate(state, incumbent, config);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            samples.add(elapsedMillis);
            System.out.printf(Locale.ROOT, "PAIRED_SAMPLE arm=%s match=%s day=%d size=%s repeat=%d"
                            + " wallMillis=%d reportedMillis=%d rawHybrid4=%d rawPhys=%s safeHybrid4=%d"
                            + " pathfinding=%d%n",
                    arm, day.matchId(), day.day(), day.sizeClass(), repeat, elapsedMillis,
                    evaluation.planningMillis(), evaluation.rawV3Hybrid4(),
                    evaluation.rawV3PhysicalSignature(), evaluation.safeV3Hybrid4(),
                    evaluation.pathfindingExecutions());
        }
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        System.out.printf(Locale.ROOT, "PAIRED_MEDIAN arm=%s match=%s day=%d size=%s repeats=%d"
                        + " medianMillis=%d minMillis=%d maxMillis=%d%n",
                arm, day.matchId(), day.day(), day.sizeClass(), samples.size(),
                sorted.get(sorted.size() / 2), sorted.get(0), sorted.get(sorted.size() - 1));
    }
}
