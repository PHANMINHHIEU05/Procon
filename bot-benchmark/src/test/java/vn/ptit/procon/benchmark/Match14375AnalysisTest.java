package vn.ptit.procon.benchmark;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;

public class Match14375AnalysisTest {

    @Test
    void testAnalyzeM14375() throws Exception {
        Path root = Path.of(System.getProperty("user.home") + "/.procon-autotune/corpus");
        List<V3CorpusDay> allDays = V3CorpusDay.loadAll(root, true);
        List<V3CorpusDay> matchDays = allDays.stream()
                .filter(d -> d.matchId().equals("m-14375"))
                .sorted((a, b) -> Integer.compare(a.day(), b.day()))
                .toList();

        System.out.println("Loaded days for m-14375: " + matchDays.size());
        DaySimulator sim = new DaySimulator();
        JointTeamBeamR3Planner planner = new JointTeamBeamR3Planner();

        for (V3CorpusDay corpusDay : matchDays) {
            int d = corpusDay.day();
            DayState state = corpusDay.rebuild();
            TeamPlan plan = planner.plan(state);

            ValidDaySimulationResult simResult = (ValidDaySimulationResult) sim.simulate(state, plan);
            int totalCollections = simResult.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum();

            System.out.printf("=== DAY %d ===%n", d);
            System.out.printf("Total collections: %d%n", totalCollections);
            simResult.portionsCollectedByAgent().forEach((aid, count) -> {
                System.out.printf("  Agent %d: collected %d portions%n", aid.value(), count);
            });

            System.out.println("Final agent states:");
            for (AgentState a : simResult.finalAgents()) {
                System.out.printf("  Agent %d (%s) at pos %d, fuel=%s%n",
                        a.id().value(), a.kind(), a.position().value(),
                        a.fuel() instanceof FiniteFuel f ? f.amount() : "INF");
            }
        }
    }
}
