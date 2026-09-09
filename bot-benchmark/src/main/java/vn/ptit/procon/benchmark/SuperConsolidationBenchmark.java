package vn.ptit.procon.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.*;
import vn.ptit.procon.domain.agent.*;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.engine.*;
import vn.ptit.procon.planner.v2.*;
import vn.ptit.procon.protocol.*;
import vn.ptit.procon.protocol.dto.*;

public class SuperConsolidationBenchmark {

    public record ArmConfig(
            String name,
            int beamWidth,
            int maxExpanded,
            int maxChildren,
            int maxTargets,
            int maxStageB,
            boolean gateRefuelByGain,
            boolean prioritizeOwn
    ) {}

    public record RunResult(
            String armName,
            String matchId,
            int day,
            String profile,
            int rawOwn,
            int brands,
            Integer hybridScore,
            String supportRoot,
            long planningMillis,
            int expandedStates,
            int stageATerminals,
            int coupledEvals,
            String signature
    ) {}

    public static void main(String[] args) throws Exception {
        System.out.println("=== PTIT PROCON SUPER CONSOLIDATION BENCHMARK ===");

        // Define Arms
        ArmConfig armFull = new ArmConfig("ARM_FULL", 192, 384, 32, 7, 48, true, true);
        ArmConfig armPrev = new ArmConfig("ARM_PREVIOUS", 48, 64, 24, 4, 16, false, false);
        ArmConfig a1 = new ArmConfig("A1_BEAM_96", 96, 384, 32, 7, 48, true, true);
        ArmConfig a2 = new ArmConfig("A2_EXP_192", 192, 192, 32, 7, 48, true, true);
        ArmConfig a3 = new ArmConfig("A3_CHILD_24", 192, 384, 24, 7, 48, true, true);
        ArmConfig a4 = new ArmConfig("A4_TARG_4", 192, 384, 32, 4, 48, true, true);
        ArmConfig a5 = new ArmConfig("A5_STAGEB_16", 192, 384, 32, 7, 16, true, true);
        ArmConfig a6 = new ArmConfig("A6_GATE_FALSE", 192, 384, 32, 7, 48, false, true);
        ArmConfig a7 = new ArmConfig("A7_PRIORITIZE_FALSE", 192, 384, 32, 7, 48, true, false);
        ArmConfig armMinStrong = new ArmConfig("ARM_MIN_STRONG", 96, 384, 32, 7, 16, true, true);

        List<ArmConfig> arms = List.of(armFull, armPrev, a1, a2, a3, a4, a5, a6, a7, armMinStrong);

        // Select representative persistent matches
        List<String> matchesA = List.of("m-14662", "m-15182", "m-15187");
        List<String> matchesB = List.of("m-14667", "m-14689", "m-15186");

        ObjectMapper mapper = new ObjectMapper();
        DaySimulator simulator = new DaySimulator();
        File corpusDir = new File(System.getProperty("user.home") + "/.procon-autotune/corpus");

        // Load all test states
        List<StateRecord> testStates = new ArrayList<>();
        for (String m : matchesA) loadMatchStates(corpusDir, m, "A", mapper, testStates);
        for (String m : matchesB) loadMatchStates(corpusDir, m, "B", mapper, testStates);

        System.out.printf("Loaded %d test states (%d Profile A, %d Profile B)%n",
                testStates.size(),
                testStates.stream().filter(s -> s.profile.equals("A")).count(),
                testStates.stream().filter(s -> s.profile.equals("B")).count());

        Map<String, List<RunResult>> resultsByArm = new LinkedHashMap<>();
        for (ArmConfig arm : arms) {
            resultsByArm.put(arm.name(), new ArrayList<>());
        }

        // Run benchmark across all states and arms
        for (int i = 0; i < testStates.size(); i++) {
            StateRecord sr = testStates.get(i);
            System.out.printf("[%d/%d] Evaluating state %s day %d (%s)...%n",
                    i + 1, testStates.size(), sr.matchId, sr.day, sr.profile);

            for (ArmConfig arm : arms) {
                RunResult res = evaluateArmOnState(arm, sr, simulator);
                resultsByArm.get(arm.name()).add(res);
            }
        }

        // Output Comprehensive Ablation Analysis
        System.out.println("\n=======================================================");
        System.out.println("                 ABLATION RESULTS SUMMARY               ");
        System.out.println("=======================================================\n");

        List<RunResult> fullResults = resultsByArm.get("ARM_FULL");

        for (ArmConfig arm : arms) {
            List<RunResult> armResults = resultsByArm.get(arm.name());
            analyzeArm(arm.name(), armResults, fullResults);
        }

        // Profile-specific breakdown for ARM_FULL vs ARM_PREVIOUS and key arms
        System.out.println("\n=======================================================");
        System.out.println("              PROFILE-SPECIFIC COMPARISON               ");
        System.out.println("=======================================================");
        for (ArmConfig arm : arms) {
            analyzeProfileBreakdown(arm.name(), resultsByArm.get(arm.name()), fullResults);
        }

        // Winner Witnesses for Quality Differences
        System.out.println("\n=======================================================");
        System.out.println("                 WINNER-WITNESS AUDIT                   ");
        System.out.println("=======================================================");
        for (ArmConfig arm : arms) {
            if (arm.name().equals("ARM_FULL")) continue;
            findWitnesses(arm.name(), resultsByArm.get(arm.name()), fullResults);
        }

        // Anytime Quality Curve on Representative States
        System.out.println("\n=======================================================");
        System.out.println("                 ANYTIME QUALITY CURVES                 ");
        System.out.println("=======================================================");
        List<StateRecord> anytimeStates = List.of(
                testStates.stream().filter(s -> s.matchId.equals("m-15187") && s.day == 0).findFirst().orElseThrow(),
                testStates.stream().filter(s -> s.matchId.equals("m-15186") && s.day == 0).findFirst().orElseThrow(),
                testStates.stream().filter(s -> s.matchId.equals("m-15186") && s.day == 1).findFirst().orElseThrow()
        );
        for (StateRecord as : anytimeStates) {
            evaluateAnytimeCurve(as, simulator);
        }
    }

    record StateRecord(String matchId, int day, String profile, DayState state) {}

    private static void loadMatchStates(File corpusDir, String matchId, String profile, ObjectMapper mapper, List<StateRecord> list) throws Exception {
        File mDir = new File(corpusDir, matchId);
        if (!mDir.exists()) return;
        File setupFile = new File(mDir, "setup.json");
        if (!setupFile.exists()) return;
        SetupDto setupDto = mapper.readValue(setupFile, SetupDto.class);
        StaticMatchData matchData = new SetupMapper().toDomain(setupDto);
        int agentCount = setupDto.agents().size();
        List<AgentKind> assignment = new ArrayList<>();
        for (int i = 0; i < agentCount - 1; i++) assignment.add(AgentKind.PATROL);
        assignment.add(AgentKind.REFUEL);

        for (int d = 0; d < 4; d++) {
            File stateFile = new File(mDir, "day-" + d + "/state.json");
            if (!stateFile.exists()) continue;
            DayStateDto stateDto = mapper.readValue(stateFile, DayStateDto.class);
            DayState state = new DayStateMapper().toDomain(stateDto, matchData, assignment);
            list.add(new StateRecord(matchId, d, profile, state));
        }
    }

    private static RunResult evaluateArmOnState(ArmConfig arm, StateRecord sr, DaySimulator simulator) {
        System.setProperty("procon.v2.beam_width", String.valueOf(arm.beamWidth()));
        System.setProperty("procon.v2.max_expanded_states", String.valueOf(arm.maxExpanded()));
        System.setProperty("procon.v2.max_children", String.valueOf(arm.maxChildren()));
        System.setProperty("procon.v2.max_targets", String.valueOf(arm.maxTargets()));
        System.setProperty("procon.v2.max_stage_b", String.valueOf(arm.maxStageB()));
        System.setProperty("procon.v2.gate_refuel_by_gain", String.valueOf(arm.gateRefuelByGain()));
        System.setProperty("procon.prioritize_own", String.valueOf(arm.prioritizeOwn()));
        System.setProperty("procon.v2.competitive_policy", "FINAL_FIXED");
        System.setProperty("procon.planner.max_millis", "3600");
        System.setProperty("procon.planner.safety_margin_millis", "400");

        JointTeamBeamR3Config config = JointTeamBeamR3Config.defaults();
        JointTeamBeamR3Planner planner = new JointTeamBeamR3Planner(config);

        long t0 = System.currentTimeMillis();
        JointTeamBeamR3Result r3Result = planner.planWithStats(sr.state());
        long planningMillis = System.currentTimeMillis() - t0;

        TeamPlan plan = r3Result.plan();
        ValidDaySimulationResult simRes = (ValidDaySimulationResult) simulator.simulate(sr.state(), plan);

        int rawOwn = simRes.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum();
        int brands = simRes.brandsCollected().size();
        Integer hybridScore = r3Result.beamResult() != null && r3Result.beamResult().evaluation() != null
                ? r3Result.beamResult().evaluation().hybrid().hybridMarginScore4() : null;
        String supportRoot = r3Result.stats().selectedSupportSkeletonSignature();
        int expanded = r3Result.beamResult() != null ? r3Result.beamResult().stats().expandedStates() : 0;
        int stageA = r3Result.stats().stageATerminals();
        int coupled = r3Result.stats().stageBEvaluated();
        String sig = plan.actionsByAgent().toString();

        return new RunResult(arm.name(), sr.matchId, sr.day, sr.profile, rawOwn, brands, hybridScore,
                supportRoot, planningMillis, expanded, stageA, coupled, sig);
    }

    private static void analyzeArm(String name, List<RunResult> results, List<RunResult> full) {
        int wins = 0, ties = 0, losses = 0;
        List<Integer> ownDeltas = new ArrayList<>();
        List<Integer> hybridDeltas = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        long totalExpanded = 0, totalStageA = 0, totalCoupled = 0;

        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            RunResult f = full.get(i);
            int deltaOwn = r.rawOwn() - f.rawOwn();
            ownDeltas.add(deltaOwn);
            if (deltaOwn > 0) wins++;
            else if (deltaOwn == 0) ties++;
            else losses++;

            if (r.hybridScore() != null && f.hybridScore() != null) {
                hybridDeltas.add(r.hybridScore() - f.hybridScore());
            }
            times.add(r.planningMillis());
            totalExpanded += r.expandedStates();
            totalStageA += r.stageATerminals();
            totalCoupled += r.coupledEvals();
        }

        Collections.sort(times);
        long p50 = times.get(times.size() / 2);
        long p90 = times.get((int) (times.size() * 0.9));
        long max = times.get(times.size() - 1);

        double meanOwnDelta = ownDeltas.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        Collections.sort(ownDeltas);
        int medianOwnDelta = ownDeltas.get(ownDeltas.size() / 2);

        double meanHybridDelta = hybridDeltas.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        Collections.sort(hybridDeltas);
        int medianHybridDelta = hybridDeltas.isEmpty() ? 0 : hybridDeltas.get(hybridDeltas.size() / 2);

        String classification;
        if (name.equals("ARM_FULL")) {
            classification = "BASELINE_FULL";
        } else if (losses > wins && (meanOwnDelta < -0.2 || medianOwnDelta < 0)) {
            classification = "ESSENTIAL_QUALITY";
        } else if (losses > wins) {
            classification = "USEFUL_QUALITY";
        } else if (losses == 0 && wins == 0) {
            classification = "REDUNDANT";
        } else if (wins > losses) {
            classification = "HARMFUL";
        } else {
            classification = "REDUNDANT";
        }

        System.out.printf("--- %s [%s] ---%n", name, classification);
        System.out.printf("  vs FULL: Wins=%d, Ties=%d, Losses=%d | MeanOwnDelta=%.3f, MedianOwnDelta=%d%n",
                wins, ties, losses, meanOwnDelta, medianOwnDelta);
        System.out.printf("  HybridDelta: Mean=%.3f, Median=%d%n", meanHybridDelta, medianHybridDelta);
        System.out.printf("  Planning Latency: p50=%d ms, p90=%d ms, max=%d ms%n", p50, p90, max);
        System.out.printf("  Total Work: Expanded=%d, Materialized(StageA)=%d, Coupled=%d%n%n",
                totalExpanded, totalStageA, totalCoupled);
    }

    private static void analyzeProfileBreakdown(String name, List<RunResult> results, List<RunResult> full) {
        int ownA = 0, fullOwnA = 0, ownB = 0, fullOwnB = 0;
        long timeA = 0, timeB = 0;
        int countA = 0, countB = 0;

        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            RunResult f = full.get(i);
            if (r.profile().equals("A")) {
                ownA += r.rawOwn();
                fullOwnA += f.rawOwn();
                timeA += r.planningMillis();
                countA++;
            } else {
                ownB += r.rawOwn();
                fullOwnB += f.rawOwn();
                timeB += r.planningMillis();
                countB++;
            }
        }
        System.out.printf("%s -> Profile A: Total=%d (Full=%d, Delta=%+d, AvgTime=%d ms) | Profile B: Total=%d (Full=%d, Delta=%+d, AvgTime=%d ms)%n",
                name, ownA, fullOwnA, ownA - fullOwnA, countA > 0 ? timeA / countA : 0,
                ownB, fullOwnB, ownB - fullOwnB, countB > 0 ? timeB / countB : 0);
    }

    private static void findWitnesses(String armName, List<RunResult> results, List<RunResult> full) {
        int count = 0;
        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            RunResult f = full.get(i);
            if (r.rawOwn() != f.rawOwn() || (r.hybridScore() != null && !r.hybridScore().equals(f.hybridScore()))) {
                System.out.printf("CONFIG_WITNESS arm=%s match=%s day=%d profile=%s fullOwn=%d ablatedOwn=%d deltaOwn=%+d fullHybrid=%s ablatedHybrid=%s timeFull=%dms timeAblated=%dms expandedFull=%d expandedAblated=%d%n",
                        armName, r.matchId(), r.day(), r.profile(), f.rawOwn(), r.rawOwn(), r.rawOwn() - f.rawOwn(),
                        f.hybridScore(), r.hybridScore(), f.planningMillis(), r.planningMillis(), f.expandedStates(), r.expandedStates());
                count++;
                if (count >= 3) break;
            }
        }
        if (count == 0) {
            System.out.printf("NO_QUALITY_WITNESS arm=%s (identical quality across all %d test states)%n", armName, results.size());
        }
    }

    private static void evaluateAnytimeCurve(StateRecord sr, DaySimulator simulator) {
        System.out.printf("ANYTIME_CURVE match=%s day=%d profile=%s:%n", sr.matchId, sr.day, sr.profile);
        int[] deadlines = {500, 1000, 1500, 2000, 2500, 3000, 3600};
        for (int d : deadlines) {
            System.setProperty("procon.v2.beam_width", "192");
            System.setProperty("procon.v2.max_expanded_states", "384");
            System.setProperty("procon.v2.max_children", "32");
            System.setProperty("procon.v2.max_targets", "7");
            System.setProperty("procon.v2.max_stage_b", "48");
            System.setProperty("procon.v2.gate_refuel_by_gain", "true");
            System.setProperty("procon.prioritize_own", "true");
            System.setProperty("procon.v2.competitive_policy", "FINAL_FIXED");
            System.setProperty("procon.planner.max_millis", String.valueOf(d));
            System.setProperty("procon.planner.safety_margin_millis", String.valueOf(Math.min(200, d / 4)));

            JointTeamBeamR3Config config = JointTeamBeamR3Config.defaults();
            JointTeamBeamR3Planner planner = new JointTeamBeamR3Planner(config);

            long t0 = System.currentTimeMillis();
            JointTeamBeamR3Result res = planner.planWithStats(sr.state());
            long elapsed = System.currentTimeMillis() - t0;
            ValidDaySimulationResult sim = (ValidDaySimulationResult) simulator.simulate(sr.state(), res.plan());
            int own = sim.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum();
            Integer hybrid = res.beamResult() != null && res.beamResult().evaluation() != null
                    ? res.beamResult().evaluation().hybrid().hybridMarginScore4() : null;
            int exp = res.beamResult() != null ? res.beamResult().stats().expandedStates() : 0;
            System.out.printf("  t=%dms (actual=%dms): own=%d, hybrid=%s, expanded=%d, deadlineTriggered=%s%n",
                    d, elapsed, own, hybrid, exp, res.stats().planningDeadlineTriggered());
        }
    }
}
