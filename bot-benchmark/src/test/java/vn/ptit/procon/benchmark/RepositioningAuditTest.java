package vn.ptit.procon.benchmark;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.planner.WeightedRouteFinder;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.FrozenObjectiveEvaluator;

public class RepositioningAuditTest {

    @Test
    void testRepositioningGainOnM13291() throws Exception {
        Path root = Path.of(System.getProperty("user.home") + "/.procon-autotune/corpus");
        List<V3CorpusDay> allDays = V3CorpusDay.loadAll(root, true);
        V3CorpusDay day0Corpus = allDays.stream().filter(d -> d.matchId().equals("m-13291") && d.day() == 0).findFirst().get();
        V3CorpusDay day1Corpus = allDays.stream().filter(d -> d.matchId().equals("m-13291") && d.day() == 1).findFirst().get();

        DayState state0 = day0Corpus.rebuild();
        JointTeamBeamR3Planner planner0 = new JointTeamBeamR3Planner();
        TeamPlan plan0 = planner0.plan(state0);

        DaySimulator sim = new DaySimulator();
        ValidDaySimulationResult res0 = (ValidDaySimulationResult) sim.simulate(state0, plan0);
        System.out.println("Day 0 Baseline End Positions:");
        for (AgentState a : res0.finalAgents()) {
            System.out.printf("  Agent %d (%s) at pos %d, fuel=%s%n",
                    a.id().value(), a.kind(), a.position().value(),
                    a.fuel() instanceof FiniteFuel f ? f.amount() : "INF");
        }

        Map<AgentId, List<AgentAction>> newActions = new LinkedHashMap<>(plan0.actionsByAgent());
        WeightedRouteFinder router = new WeightedRouteFinder();

        AgentState endAgent1 = res0.finalAgents().stream().filter(a -> a.id().value() == 1).findFirst().get();
        AgentState endAgent4 = res0.finalAgents().stream().filter(a -> a.id().value() == 4).findFirst().get();

        Optional<Route> r1 = router.find(state0, endAgent1, new Position(230));
        Optional<Route> r4 = router.find(state0, endAgent4, new Position(167));

        System.out.println("Route for Agent 1 to 230: " + r1.map(r -> "steps=" + r.stepsUsed() + " fuel=" + r.fuelUsed()).orElse("NONE"));
        System.out.println("Route for Agent 4 to 167: " + r4.map(r -> "steps=" + r.stepsUsed() + " fuel=" + r.fuelUsed()).orElse("NONE"));

        if (r1.isPresent()) {
            List<AgentAction> a1 = new ArrayList<>(plan0.actionsFor(new AgentId(1)));
            WaitAction last = (WaitAction) a1.removeLast();
            a1.addAll(r1.get().toMoveActions());
            int remainingWait = last.steps() - r1.get().stepsUsed();
            if (remainingWait > 0) a1.add(new WaitAction(remainingWait));
            newActions.put(new AgentId(1), a1);
        }

        if (r4.isPresent()) {
            List<AgentAction> a4 = new ArrayList<>(plan0.actionsFor(new AgentId(4)));
            WaitAction last = (WaitAction) a4.removeLast();
            a4.addAll(r4.get().toMoveActions());
            int remainingWait = last.steps() - r4.get().stepsUsed();
            if (remainingWait > 0) a4.add(new WaitAction(remainingWait));
            newActions.put(new AgentId(4), a4);
        }

        TeamPlan repPlan0 = new TeamPlan(newActions);
        var val = new PlanValidator().validate(state0, repPlan0);
        System.out.println("Repositioned Plan 0 Valid? " + val.valid() + " failure=" + val.failure());

        ValidDaySimulationResult repRes0 = (ValidDaySimulationResult) sim.simulate(state0, repPlan0);
        System.out.println("Day 0 Repositioned End Positions:");
        for (AgentState a : repRes0.finalAgents()) {
            System.out.printf("  Agent %d (%s) at pos %d, fuel=%s%n",
                    a.id().value(), a.kind(), a.position().value(),
                    a.fuel() instanceof FiniteFuel f ? f.amount() : "INF");
        }

        DayState baselineState1 = day1Corpus.rebuild();
        DayState repState1 = new DayState(
                baselineState1.matchData(),
                baselineState1.day(),
                repRes0.finalAgents(),
                baselineState1.roadTraffic(),
                baselineState1.spotStock(),
                baselineState1.observedOthers());

        JointTeamBeamR3Planner planner = new JointTeamBeamR3Planner();
        TeamPlan basePlan1 = planner.plan(baselineState1);
        TeamPlan repPlan1 = planner.plan(repState1);
        JointTeamBeamR3Planner plannerCurrent = new JointTeamBeamR3Planner(
                vn.ptit.procon.planner.v2.JointTeamBeamR3Config.defaults().withCompetitiveTargetPolicy(
                        vn.ptit.procon.planner.v2.CompetitiveTargetPolicy.CURRENT));
        JointTeamBeamR3Planner plannerFinalFixed = new JointTeamBeamR3Planner(
                vn.ptit.procon.planner.v2.JointTeamBeamR3Config.defaults().withCompetitiveTargetPolicy(
                        vn.ptit.procon.planner.v2.CompetitiveTargetPolicy.FINAL_FIXED));

        TeamPlan planCurrent = plannerCurrent.plan(baselineState1);
        TeamPlan planFinalFixed = plannerFinalFixed.plan(baselineState1);
        TeamPlan planRepCurrent = plannerCurrent.plan(repState1);
        TeamPlan planRepFinalFixed = plannerFinalFixed.plan(repState1);

        FrozenObjectiveEvaluator evalBase = new FrozenObjectiveEvaluator(baselineState1);
        FrozenObjectiveEvaluator evalRep = new FrozenObjectiveEvaluator(repState1);

        var scoreBase = evalBase.evaluate(basePlan1).get();
        var scoreRep = evalRep.evaluate(repPlan1).get();
        var scoreCurrent = evalBase.evaluate(planCurrent).get();
        var scoreFinalFixed = evalBase.evaluate(planFinalFixed).get();
        var scoreRepCurrent = evalRep.evaluate(planRepCurrent).get();
        var scoreRepFinalFixed = evalRep.evaluate(planRepFinalFixed).get();

        System.out.printf("DAY 1 BASELINE SCORE: OwnSemi=%d, Brands=%d, Hybrid4=%d%n",
                scoreBase.ownSemiCollections(), scoreBase.ownSemiBrands(), scoreBase.hybridMarginScore4());
        System.out.printf("DAY 1 REPOSITIONED SCORE: OwnSemi=%d, Brands=%d, Hybrid4=%d%n",
                scoreRep.ownSemiCollections(), scoreRep.ownSemiBrands(), scoreRep.hybridMarginScore4());
        System.out.printf("DAY 1 BASE CURRENT: OwnSemi=%d, Brands=%d, Hybrid4=%d%n",
                scoreCurrent.ownSemiCollections(), scoreCurrent.ownSemiBrands(), scoreCurrent.hybridMarginScore4());
        System.out.printf("DAY 1 BASE FINAL_FIXED: OwnSemi=%d, Brands=%d, Hybrid4=%d%n",
                scoreFinalFixed.ownSemiCollections(), scoreFinalFixed.ownSemiBrands(), scoreFinalFixed.hybridMarginScore4());
        System.out.printf("DAY 1 REP CURRENT: OwnSemi=%d, Brands=%d, Hybrid4=%d%n",
                scoreRepCurrent.ownSemiCollections(), scoreRepCurrent.ownSemiBrands(), scoreRepCurrent.hybridMarginScore4());
        System.out.printf("DAY 1 REP FINAL_FIXED: OwnSemi=%d, Brands=%d, Hybrid4=%d%n",
                scoreRepFinalFixed.ownSemiCollections(), scoreRepFinalFixed.ownSemiBrands(), scoreRepFinalFixed.hybridMarginScore4());
    }
}
