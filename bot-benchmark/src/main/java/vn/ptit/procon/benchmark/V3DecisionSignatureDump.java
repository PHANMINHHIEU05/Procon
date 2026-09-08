package vn.ptit.procon.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;

/**
 * Strict behaviour-preservation witness for one code candidate, measured on real captured days.
 *
 * <p>The corpus replay line mixes decisions with wall-clock and work counters, so proving "no decision
 * changed" from it requires stripping timing tokens by regex — and any residue then looks like a decision
 * difference. This tool separates the two concerns at the source instead:
 *
 * <ul>
 *   <li>{@code V3_DECISION} carries EVERY field of {@link V3ShadowEvaluation} that a planner decision is
 *       made of, and nothing else: no milliseconds, no state counts, no memo counters. Two runs of the
 *       same binary produce byte-identical {@code V3_DECISION} output, so a plain {@code diff} of a
 *       baseline dump against a candidate dump is a complete and noise-free verdict.</li>
 *   <li>{@code V3_WORK} carries the deterministic work counters and the wall clock, which are expected to
 *       differ and are read only as the performance half of the comparison.</li>
 * </ul>
 *
 * <p>Reads only. Nothing here runs in the live bot.
 */
public final class V3DecisionSignatureDump {

    private final V3CorpusReplay replay;

    public V3DecisionSignatureDump(long budgetMillis) {
        this.replay = new V3CorpusReplay(budgetMillis);
    }

    /** Every decision-relevant field, in a fixed order, with no timing and no work counter. */
    public static String decisionLine(V3CorpusReplayResult result) {
        V3ShadowEvaluation value = result.evaluation();
        return String.format(Locale.ROOT,
                "V3_DECISION match=%s day=%d size=%s map=%dx%d agents=%d stepBudget=%d parity=%s"
                        + " v2Own=%d v2Brands=%d v2CoupledOwn=%d v2BaselineOpp=%d v2CoupledOpp=%d"
                        + " v2Hybrid4=%d v2Root=%s v2Phys=%s"
                        + " rawOwn=%d rawBrands=%d rawCoupledOwn=%d rawBaselineOpp=%d rawCoupledOpp=%d"
                        + " rawHybrid4=%d rawPhys=%s"
                        + " safeOwn=%d safeHybrid4=%d safePhys=%s"
                        + " fallback=%b v3Root=%s v3Strategic=%s rawVerdict=%s safeVerdict=%s"
                        + " ownDelta=%d hybridDelta4=%d samePhysical=%b pathfinding=%d",
                result.matchId(), result.day(), result.sizeClass(), result.mapWidth(),
                result.mapHeight(), result.agentCount(), result.stepBudget(),
                result.parityOk() ? "OK" : "MISMATCH",
                value.v2OwnSemi(), value.v2Brands(), value.v2CoupledOwn(), value.v2BaselineOpponent(),
                value.v2CoupledOpponent(), value.v2Hybrid4(), value.v2SupportRoot(),
                value.v2PhysicalSignature(),
                value.rawV3OwnSemi(), value.rawV3Brands(), value.rawV3CoupledOwn(),
                value.rawV3BaselineOpponent(), value.rawV3CoupledOpponent(), value.rawV3Hybrid4(),
                value.rawV3PhysicalSignature(),
                value.safeV3OwnSemi(), value.safeV3Hybrid4(), value.safeV3PhysicalSignature(),
                value.v3FallbackUsed(), value.v3SupportRoot(), value.v3StrategicSignature(),
                value.rawVerdict(), value.safeVerdict(), value.ownDelta(), value.hybridDelta4(),
                value.samePhysicalPlan(), value.pathfindingExecutions());
    }

    /** The performance half: deterministic work counters plus the wall clock. */
    public static String workLine(V3CorpusReplayResult result) {
        V3ShadowEvaluation value = result.evaluation();
        return String.format(Locale.ROOT,
                "V3_WORK match=%s day=%d size=%s v3Millis=%d deadlineExceeded=%b states=%d teams=%d"
                        + " materialized=%d coupled=%d",
                result.matchId(), result.day(), result.sizeClass(), value.planningMillis(),
                value.deadlineBudgetExceeded(), value.statesExpanded(),
                value.completeTeamCandidates(), value.materializedPlans(), value.coupledEvaluations());
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(V3CorpusReplay.DEFAULT_CORPUS_DIRECTORY);
        String matchFilter = "";
        long budget = V3CorpusReplay.UNCAPPED_BUDGET_MILLIS;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--corpus" -> root = Path.of(args[++index]);
                case "--match" -> matchFilter = args[++index];
                case "--budget-millis" -> budget = Long.parseLong(args[++index]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }
        final String match = matchFilter;
        List<V3CorpusDay> days = V3CorpusDay.loadAll(root, true).stream()
                .filter(day -> match.isEmpty() || day.matchId().equals(match))
                .toList();
        System.out.println("V3_DECISION_DUMP_LOADED root=" + root + " days=" + days.size()
                + " match=" + (match.isEmpty() ? "ALL" : match) + " budgetMillis=" + budget);
        List<String> work = new ArrayList<>();
        V3DecisionSignatureDump dump = new V3DecisionSignatureDump(budget);
        for (V3CorpusDay day : days) {
            V3CorpusReplayResult result = dump.replay.replay(day);
            System.out.println(decisionLine(result));
            work.add(workLine(result));
        }
        work.forEach(System.out::println);
        System.out.println("V3_DECISION_DUMP_DONE days=" + days.size());
    }
}
