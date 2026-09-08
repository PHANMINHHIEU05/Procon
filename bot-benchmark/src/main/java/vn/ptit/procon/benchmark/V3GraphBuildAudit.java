package vn.ptit.procon.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.V3WorkAuditProbe;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.StrategicSearchConfig;
import vn.ptit.procon.planner.v3.V3ShadowPlanner;

/**
 * ITERATION 6 STEP 1 — the causal graph-build / route-query audit.
 *
 * <p>Runs the real V3 shadow planner over real captured server day states with the diagnostics probe on, then
 * prints the counters the iteration-6 decision tree needs. It answers, per state and for the whole corpus:
 * how many graph builds were REQUESTED, how many distinct semantically complete graph inputs those requests
 * covered, how many route-finder calls each build issued, how many distinct route queries those calls
 * covered, and how many distinct (origin, fuel) Dijkstra sources those queries came from.
 *
 * <p>The last figure is the one iteration 5 never had: a per-origin query count above 1 means the finder
 * re-runs the same single-source search once per goal, which is a count of redundant operations rather than a
 * guess about their unit cost.
 */
public final class V3GraphBuildAudit {

    private V3GraphBuildAudit() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(V3CorpusReplay.DEFAULT_CORPUS_DIRECTORY);
        String matchFilter = "";
        int limit = Integer.MAX_VALUE;
        long budgetMillis = 60_000L;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--corpus" -> root = Path.of(args[++index]);
                case "--match" -> matchFilter = args[++index];
                case "--limit" -> limit = Integer.parseInt(args[++index]);
                case "--budget-millis" -> budgetMillis = Long.parseLong(args[++index]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }
        final String match = matchFilter;
        List<V3CorpusDay> days = V3CorpusDay.loadAll(root, true).stream()
                .filter(day -> match.isEmpty() || day.matchId().equals(match))
                .limit(limit)
                .toList();
        System.out.println("V3AUDIT_LOADED root=" + root + " days=" + days.size()
                + " match=" + (match.isEmpty() ? "ALL" : match) + " budgetMillis=" + budgetMillis);

        StrategicSearchConfig config = V3ShadowPlanner.shadowConfig(budgetMillis);
        V3WorkAuditProbe.enable();
        for (V3CorpusDay day : days) {
            DayState state = day.rebuild();
            TeamPlan incumbent = new JointTeamBeamR3Planner().plan(state);
            V3WorkAuditProbe.reset();
            long started = System.nanoTime();
            new V3ShadowPlanner().evaluate(state, incumbent, config);
            long millis = (System.nanoTime() - started) / 1_000_000L;
            System.out.printf(Locale.ROOT, "V3AUDIT_STATE match=%s day=%d size=%s agents=%d spots=%d"
                            + " stepBudget=%d wallMillis=%d%n",
                    day.matchId(), day.day(), day.sizeClass(), day.agentCount(),
                    state.matchData().udonSpots().size(), state.stepBudget(), millis);
            System.out.print(V3WorkAuditProbe.report(day.matchId() + "#" + day.day()));
            System.out.flush();
        }
        V3WorkAuditProbe.disable();
    }
}
