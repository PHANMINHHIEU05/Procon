package vn.ptit.procon.benchmark;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.TeamCoordinatorPlanner;
import vn.ptit.procon.planner.v2.JointTeamBeamConfig;
import vn.ptit.procon.planner.v2.JointTeamBeamPlanner;
import vn.ptit.procon.planner.v2.JointTeamBeamResult;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Config;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Result;
import vn.ptit.procon.planner.v2.V2SearchPolicy;
import vn.ptit.procon.planner.v2.V2CollectionAuditMode;
import vn.ptit.procon.planner.v2.V2CollectionSearchAudit;
import vn.ptit.procon.planner.v2.CompetitiveTargetPolicy;
import vn.ptit.procon.planner.v2.StageBRecallAudit;
import vn.ptit.procon.planner.v2.StageBRecallAuditMode;
import vn.ptit.procon.planner.v2.TerminalObjectiveAudit;
import vn.ptit.procon.planner.HybridCalibratedMarginEvaluation;
import vn.ptit.procon.planner.v3.StrategicOracle;
import vn.ptit.procon.planner.v3.StrategicOracleResult;
import vn.ptit.procon.planner.v3.V3Phase0Config;
import vn.ptit.procon.planner.v3.V3RepresentationConfig;
import vn.ptit.procon.planner.v3.V3RepresentationOracle;
import vn.ptit.procon.planner.v3.V3RepresentationResult;
import vn.ptit.procon.planner.v3.V3EdgeRetentionPolicy;
import vn.ptit.procon.planner.v3.StrategicOpportunityGraph;
import vn.ptit.procon.planner.v3.StrategicOpportunityGraphBuilder;
import vn.ptit.procon.planner.v3.V3ExactEdgeCoverage;
import vn.ptit.procon.planner.v3.StrategicSearchConfig;
import vn.ptit.procon.planner.v3.StrategicSearchResult;
import vn.ptit.procon.planner.v3.StrategicTeamSearch;
import vn.ptit.procon.planner.v3.V3TerminalParityAudit;
import vn.ptit.procon.planner.v3.V3Phase24Analysis;
import vn.ptit.procon.planner.v3.V3Phase24ReportPrinter;
import vn.ptit.procon.planner.oracle.ExactOracleConfig;
import vn.ptit.procon.planner.oracle.ExactOracleResult;
import vn.ptit.procon.planner.oracle.ExactRepresentabilityAuditor;
import vn.ptit.procon.planner.oracle.ExactStrategicOracle;

/** Offline deterministic R2 sweep. It deliberately has no runtime, token, or submission path. */
public final class JointTeamBeamBenchmark {
    private static final AgentId PATROL_0 = new AgentId(0);
    private static final AgentId PATROL_1 = new AgentId(1);
    private static final AgentId PATROL_2 = new AgentId(2);
    private static final AgentId REFUEL_0 = new AgentId(9);
    private static final String R1_CONTROL = "R1_CONTROL";

    private JointTeamBeamBenchmark() {
    }

    public static void main(String[] args) {
        List<Scenario> scenarios = scenarios();
        Map<String, Map<String, Observation>> observations = new LinkedHashMap<>();
        for (Sweep sweep : primarySweeps()) {
            Map<String, Observation> perScenario = new LinkedHashMap<>();
            for (Scenario scenario : scenarios) {
                Observation first = observe(scenario.state(), sweep.config());
                Observation repeat = observe(scenario.state(), sweep.config());
                if (!first.signature().equals(repeat.signature())) {
                    throw new IllegalStateException("Non-deterministic V2 terminal for " + sweep.name()
                            + " / " + scenario.name());
                }
                perScenario.put(scenario.name(), first);
            }
            observations.put(sweep.name(), perScenario);
        }

        System.out.printf("%-22s %-24s %4s %4s %4s %4s %4s %4s %4s %5s %5s %5s %5s %5s%n",
                "fixture", "policy", "semi", "cOwn", "baseO", "cOpp", "hyb", "child", "unique",
                "term", "depth", "dSemi", "dHyb", "dChild");
        for (Scenario scenario : scenarios) {
            TeamPlan greedy = quietly(() -> new TeamCoordinatorPlanner().plan(scenario.state()));
            int greedyCollections = scenario.collections(greedy);
            for (Sweep sweep : primarySweeps()) {
                Observation value = observations.get(sweep.name()).get(scenario.name());
                Observation baseline = observations.get(R1_CONTROL).get(scenario.name());
                System.out.printf("%-22s %-24s %4d %4d %4d %4d %4d %4d %4d %5d %5d %+5d %+5d %+5d%n",
                        scenario.name(), sweep.name(), value.semiCollections(), value.coupledOwnCollections(),
                        value.baselineOpponentCollections(), value.coupledOpponentCollections(),
                        value.hybridMargin(), value.generatedChildren(), value.unique(), value.terminalPlans(),
                        value.maxDepth(), value.semiCollections() - baseline.semiCollections(),
                        value.hybridMargin() - baseline.hybridMargin(),
                        value.generatedChildren() - baseline.generatedChildren());
                System.out.printf("  audit dup=%d dom=%d terminalStates=%d stageB=%d depth=%d catalogPf=%d searchPf=%d searchMs=%d terminalMs=%d totalMs=%d securedByDepth=%s%n",
                        value.duplicates(), value.dominance(), value.terminalStates(), value.terminalPlans(),
                        value.maxDepth(), value.catalogPathfinding(), value.searchPathfinding(),
                        value.searchMillis(), value.terminalMillis(), value.millis(),
                        value.bestSecuredByDepth());
            }
            if (scenario.name().equals("global-vs-greedy") && greedyCollections != 1) {
                throw new IllegalStateException("Global-vs-greedy fixture must retain the greedy 1 baseline");
            }
            if (scenario.name().equals("global-vs-greedy")
                    && observations.get(R1_CONTROL).get(scenario.name()).semiCollections() != 2) {
                throw new IllegalStateException("V2 must retain global-vs-greedy 1 -> 2");
            }
        }
        runSecondaryTerminalAblation(scenarios);
        runStageBRecallAudit(scenarios);
        runTerminalObjectiveAudit(scenarios);
        runR3TerminalShortlistBenchmark(scenarios);
        runV2VsR3Comparison(scenarios);
        runV3StrategicOracle(scenarios);
        runV3Phase1Representation(scenarios);
        runV3Phase2StrategicSearch(scenarios);
        runV3Phase23OfflineGeneralization(scenarios);
        runExactStrategicOracle(scenarios);
        runCompetitiveTargetAblation(scenarios);
        if (Arrays.asList(args).contains("--collection-audit")) runCollectionSearchOracle(scenarios);
        if (Arrays.asList(args).contains("--phase24-audit")) runV3Phase24RawSearchRobustness();
    }

    /** Phase 2.4 baseline-replayability audit.  Opt-in so the default sweep output is unchanged. */
    private static void runV3Phase24RawSearchRobustness() {
        System.out.print(V3Phase24ReportPrinter.render(new V3Phase24Analysis().table()));
    }

    private static Observation observe(DayState state, JointTeamBeamConfig config) {
        JointTeamBeamResult result = quietly(() -> new JointTeamBeamPlanner(config).planWithStats(state));
        var hybrid = result.evaluation().hybrid();
        return new Observation(hybrid.ownSemiCollections(), hybrid.coupledOwnCollections(),
                hybrid.opponentBaselineCollections(), hybrid.coupledOpponentCollections(),
                hybrid.hybridMarginScore4(), result.stats().expandedStates(), result.stats().generatedChildren(),
                result.stats().uniqueStates(), result.stats().duplicateStatesRejected(),
                result.stats().dominatedStatesRejected(), result.stats().terminalStates(),
                result.stats().terminalPlansEvaluated(), result.stats().maxDepthReached(),
                result.stats().catalogPathfindingExecutions(), result.stats().searchPathfindingExecutions(),
                result.stats().searchMillis(), result.stats().terminalEvaluationMillis(),
                result.stats().planningMillis(), result.evaluation().base().deterministicSignature(),
                result.depthSummaries().stream().map(value -> value.strategicDecisionDepth() + ":"
                        + value.bestSecuredCollections()).collect(java.util.stream.Collectors.joining(",")));
    }

    private static List<Sweep> primarySweeps() {
        return List.of(
                new Sweep(R1_CONTROL, config(0, V2SearchPolicy.R1_CONTROL)),
                new Sweep("R1+R2_PARTIAL_ORDERING", config(0, V2SearchPolicy.r1ControlWithPartialOrdering())),
                new Sweep("R1+R2_POTENTIAL_BOUND", config(0, V2SearchPolicy.r1ControlWithPotentialBound())),
                new Sweep("R1+R2_STOP_TERMINAL", config(0, V2SearchPolicy.r1ControlWithStopTerminalHandling())),
                new Sweep("R1+R2_SAFE_DOMINANCE", config(0, V2SearchPolicy.r1ControlWithSafeDominance())),
                new Sweep("R1+R2_BRANCHING_EQ", config(0, V2SearchPolicy.r1ControlWithBranchingPolicy())),
                new Sweep("FULL_R2", config(0, V2SearchPolicy.FULL_R2)));
    }

    private static JointTeamBeamConfig config(int terminalLimit, V2SearchPolicy policy) {
        return new JointTeamBeamConfig(48, 64, 24, 4, terminalLimit, policy);
    }

    private static void runSecondaryTerminalAblation(List<Scenario> scenarios) {
        System.out.println("SECONDARY_TERMINAL_ABLATION policy=R1_CONTROL budget=48/64/24/4");
        for (Scenario scenario : scenarios) {
            Observation exhaustive = observe(scenario.state(), config(0, V2SearchPolicy.R1_CONTROL));
            for (Sweep sweep : List.of(new Sweep("K64", config(64, V2SearchPolicy.R1_CONTROL)),
                    new Sweep("K32", config(32, V2SearchPolicy.R1_CONTROL)),
                    new Sweep("K16", config(16, V2SearchPolicy.R1_CONTROL)))) {
                Observation shortlist = observe(scenario.state(), sweep.config());
                System.out.printf("%-22s %-4s signatureMatch=%-5s semi=%d/%d hybrid=%d/%d full=%d/%d terminalMs=%d/%d%n",
                        scenario.name(), sweep.name(), exhaustive.signature().equals(shortlist.signature()),
                        exhaustive.semiCollections(), shortlist.semiCollections(), exhaustive.hybridMargin(),
                        shortlist.hybridMargin(), exhaustive.terminalPlans(), shortlist.terminalPlans(),
                        exhaustive.terminalMillis(), shortlist.terminalMillis());
            }
        }
    }

    private static void runStageBRecallAudit(List<Scenario> scenarios) {
        System.out.println("STAGE_B_RECALL_AUDIT policy=R1_CONTROL productionK=16");
        for (Scenario scenario : scenarios) {
            JointTeamBeamConfig config = config(16, V2SearchPolicy.R1_CONTROL)
                    .withStageBRecallAuditMode(StageBRecallAuditMode.EXHAUSTIVE);
            JointTeamBeamResult result = observeResult(scenario.state(), config);
            StageBRecallAudit audit = result.stageBRecallAudit();
            StageBRecallTerminalView production = view(audit.productionWinner());
            StageBRecallTerminalView exhaustive = view(audit.exhaustiveWinner());
            int winnerRank = exhaustive.stageARank();
            System.out.printf("STAGE_B_RECALL fixture=%s unique=%d K8=%d K16=%d K24=%d K32=%d exhaustive=%d "
                            + "winnerStageARank=%d present=%s/%s/%s/%s top3=%d top5=%d "
                            + "K16semi=%d exhaustiveSemi=%d K16cOwn=%d exhaustivecOwn=%d gap=%d oracleMs=%d "
                            + "productionSignature=%s exhaustiveSignature=%s%n",
                    scenario.name(), audit.uniquePhysicalTerminalCount(), audit.k8BestHybrid(),
                    audit.k16BestHybrid(), audit.k24BestHybrid(), audit.k32BestHybrid(),
                    audit.exhaustiveBestHybrid(), winnerRank, winnerRank <= 8, winnerRank <= 16,
                    winnerRank <= 24, winnerRank <= 32, audit.top3Recall(), audit.top5Recall(),
                    production.ownSemiCollections(), exhaustive.ownSemiCollections(),
                    production.coupledOwnCollections(), exhaustive.coupledOwnCollections(),
                    audit.hybridGapScore4(), audit.exhaustiveEvaluationMillis(),
                    production.signature(), exhaustive.signature());
            if (scenario.name().equals("global-vs-greedy")
                    && result.evaluation().hybrid().ownSemiCollections() != 2) {
                throw new IllegalStateException("Stage-B audit changed the global-vs-greedy production result");
            }
            if (scenario.name().equals("5x5-raw-kind-zero")
                    && result.evaluation().hybrid().hybridMarginScore4() != 29) {
                throw new IllegalStateException("Stage-B audit changed the target-portfolio regression gate");
            }
        }
    }

    private static void runTerminalObjectiveAudit(List<Scenario> scenarios) {
        System.out.println("TERMINAL_OBJECTIVE_AUDIT policy=R1_CONTROL shadow=EXHAUSTIVE");
        for (Scenario scenario : scenarios) {
            JointTeamBeamConfig config = config(16, V2SearchPolicy.R1_CONTROL)
                    .withStageBRecallAuditMode(StageBRecallAuditMode.EXHAUSTIVE);
            JointTeamBeamResult result = observeResult(scenario.state(), config);
            TerminalObjectiveAudit audit = result.terminalObjectiveAudit();
            System.out.printf("OBJECTIVE fixture=%s terminals=%d pareto=%d selectedPareto=%s dominated=%s "
                            + "selectedSemi=%d selectedcOwn=%d selectedBaseOpp=%d selectedcOpp=%d "
                            + "selectedCoupledMargin=%d coupledOracleMargin=%d regret=%d stability=%.3f "
                            + "distinctWinners=%d selectedSignature=%s coupledOracleSignature=%s%n",
                    scenario.name(), audit.terminalCount(), audit.paretoCount(),
                    audit.productionWinnerOnParetoFrontier(), audit.productionWinnerDominated(),
                    audit.productionOwnSemiCollections(), audit.productionCoupledOwnCollections(),
                    audit.productionBaselineOpponentCollections(), audit.productionCoupledOpponentCollections(),
                    audit.productionCoupledMargin(), audit.coupledOracleMargin(), audit.selectionRegret(),
                    audit.productionWinnerStability(), audit.distinctWeightWinners(),
                    audit.productionWinnerSignature(), audit.coupledOracleSignature());
            System.out.printf("OBJECTIVE_CALIBRATION fixture=%s ownError=%d opponentError=%d marginError=%d "
                            + "current=%s alternatives=%s%n", scenario.name(), audit.ownCalibrationError(),
                    audit.opponentCalibrationError(), audit.marginCalibrationError(),
                    audit.alternativeObjectiveWinners().get("CURRENT_3_1"),
                    audit.alternativeObjectiveWinners());
        }
        System.out.println("HISTORICAL_CALIBRATION source=user-provided-live-aggregates");
        System.out.println("HISTORICAL match=m-6854 predictedOwn=44 actualOwn=44 predictedOpponent=55 "
                + "actualOpponent=49 predictedMargin=-11 actualMargin=-5 absOwnError=0 absOpponentError=6 absMarginError=6");
        System.out.println("HISTORICAL match=m-6861 predictedOwn=43.75 actualOwn=47 predictedOpponent=55 "
                + "actualOpponent=57 predictedMargin=-11.25 actualMargin=-10 absOwnError=3.25 "
                + "absOpponentError=2 absMarginError=1.25");
    }

    private static JointTeamBeamResult observeResult(DayState state, JointTeamBeamConfig config) {
        return quietly(() -> new JointTeamBeamPlanner(config).planWithStats(state));
    }

    private static StageBRecallTerminalView view(vn.ptit.procon.planner.v2.StageBRecallTerminal terminal) {
        return new StageBRecallTerminalView(terminal.stageARank(), terminal.ownSemiCollections(),
                terminal.coupledOwnCollections() == null ? -1 : terminal.coupledOwnCollections(),
                terminal.physicalSignature());
    }

    private static void runR3TerminalShortlistBenchmark(List<Scenario> scenarios) {
        System.out.println("R3_TERMINAL_SHORTLIST_BENCHMARK budget=48/64/24/4");
        for (Scenario scenario : scenarios) {
            for (int limit : List.of(0, 64, 32, 16)) {
                JointTeamBeamR3Config defaults = JointTeamBeamR3Config.defaults();
                int configuredLimit = limit == 0 ? 4_096 : limit;
                JointTeamBeamR3Config config = new JointTeamBeamR3Config(60_000,
                        1, configuredLimit, configuredLimit,
                        defaults.maxDepth1Candidates(), defaults.maxPartialToursPerDepth(),
                        defaults.maxExtensionsPerPartial(), defaults.maxPartialToursGenerated(),
                        defaults.maxSkeletonsConsidered(), defaults.maxSkeletonsValidated(), defaults.maxSkeletonsRetained());
                JointTeamBeamR3Result result = quietly(() -> new JointTeamBeamR3Planner(config).planWithStats(scenario.state()));
                var hybrid = result.beamResult().evaluation().hybrid();
                System.out.printf("%-22s %-10s signature=%s semiBrands=%d semi=%d hybrid=%d stageB=%d wallMs=%d%n",
                        scenario.name(), limit == 0 ? "EXHAUSTIVE" : "K" + limit,
                        result.beamResult().evaluation().base().deterministicSignature(), hybrid.ownSemiBrands(),
                        hybrid.ownSemiCollections(), hybrid.hybridMarginScore4(), result.stats().stageBEvaluated(),
                        result.stats().wallPlanningMillis());
            }
        }
    }

    private static void runV2VsR3Comparison(List<Scenario> scenarios) {
        System.out.println("R3_V2_COMPARISON policy=R1_CONTROL budget=48/64/24/4");
        for (Scenario scenario : scenarios) {
            JointTeamBeamResult v2 = quietly(() -> new JointTeamBeamPlanner(
                    config(0, V2SearchPolicy.R1_CONTROL)).planWithStats(scenario.state()));
            JointTeamBeamR3Result r3 = quietly(() -> new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                    .withRootFamilyAuditMode(vn.ptit.procon.planner.v2.R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY))
                    .planWithStats(scenario.state()));
            var v2Hybrid = v2.evaluation().hybrid();
            var r3Hybrid = r3.beamResult().evaluation().hybrid();
            System.out.printf("fixture=%s V2 brands=%d semi=%d hybrid=%d planningMs=%d signature=%s%n",
                    scenario.name(), v2Hybrid.ownSemiBrands(), v2Hybrid.ownSemiCollections(),
                    v2Hybrid.hybridMarginScore4(), v2.stats().planningMillis(),
                    v2.evaluation().base().deterministicSignature());
            System.out.printf("fixture=%s R3 brands=%d semi=%d hybrid=%d services=%d patrols=%s "
                            + "considered=%d validated=%d retained=%d stageB=%d deadline=%s wallMs=%d "
                            + "tourSearchPf=%d beamSearchPf=%d signature=%s%n",
                    scenario.name(), r3Hybrid.ownSemiBrands(), r3Hybrid.ownSemiCollections(),
                    r3Hybrid.hybridMarginScore4(), r3.stats().selectedSupportServiceCount(),
                    r3.stats().selectedSupportedPatrols(), r3.stats().skeletonsConsidered(),
                    r3.stats().skeletonsValidated(), r3.stats().skeletonsRetained(),
                    r3.stats().stageBEvaluated(), r3.stats().planningDeadlineTriggered(),
                    r3.stats().wallPlanningMillis(), r3.stats().tourSearchPathfindingExecutions(),
                    r3.stats().beamSearchPathfindingExecutions(),
                    r3.beamResult().evaluation().base().deterministicSignature());
            for (var family : r3.rootFamilyAudit().families()) {
                System.out.printf("fixture=%s R3_FAMILY services=%d available=%s physical=%d semi=%d hybrid=%d "
                                + "stageB=%d signature=%s%n", scenario.name(), family.serviceCount(), family.available(),
                        family.uniquePhysicalTerminals(), family.bestFullyEvaluatedOwnSemiCollections(),
                        family.bestFullyEvaluatedHybridMarginScore4(), family.stageBEvaluatedForFamily(),
                        family.bestPhysicalSignature());
            }
            System.out.printf("fixture=%s R3_SELECTED services=%d gainVsNoRefuelSemi=%s gainVsNoRefuelHybrid=%s "
                            + "globalDedup=%.3f%n", scenario.name(), r3.rootFamilyAudit().selectedProvenance()
                            .supportServiceCount(), r3.rootFamilyAudit().selectedGainVsNoRefuelSemi(),
                    r3.rootFamilyAudit().selectedGainVsNoRefuelHybridScore4(),
                    r3.rootFamilyAudit().globalPhysicalDedupRatio());
            if (scenario.name().equals("global-vs-greedy")
                    && r3Hybrid.ownSemiCollections() < 2) {
                throw new IllegalStateException("R3 regressed the global-vs-greedy joint-search gate");
            }
        }
    }

    private static void runV3StrategicOracle(List<Scenario> scenarios) {
        System.out.println("V3_STRATEGIC_HEADROOM_AUDIT baseline=V2_R3 oracle=PHASE_0");
        StrategicOracle oracle = new StrategicOracle();
        for (Scenario scenario : v3Scenarios(scenarios)) {
            JointTeamBeamR3Result r3 = quietly(() -> new JointTeamBeamR3Planner().planWithStats(scenario.state()));
            var v2 = r3.beamResult().evaluation().hybrid();
            StrategicOracleResult v3 = quietly(() -> oracle.solve(scenario.state(), V3Phase0Config.defaults(), List.of(r3.plan())));
            var value = v3.winner();
            var d = v3.diagnostics();
            int semiHeadroom = value.ownSemiCollections() - v2.ownSemiCollections();
            int coupledHeadroom = value.coupledOwnCollections() - v2.coupledOwnCollections();
            int hybridHeadroom = value.hybridMarginScore4() - v2.hybridMarginScore4();
            int objectiveComparison = HybridCalibratedMarginEvaluation.compare(value.objective(), v2);
            String reason = objectiveComparison < 0 ? "BETTER_THAN_V2_R3" : "NO_HEADROOM_VS_V2_R3";
            System.out.printf("V3_STRATEGIC_HEADROOM fixture=%s opportunities=%d regions=%d chains=%d "
                            + "v2Semi=%d v2CoupledOwn=%d v2Hybrid4=%d v3OracleSemi=%d v3OracleCoupledOwn=%d "
                            + "v3OracleHybrid4=%d semiHeadroom=%d coupledHeadroom=%d hybridHeadroom4=%d "
                            + "samePhysicalPlan=%s allocationsConsidered=%d materialized=%d valid=%d capReached=%s "
                            + "headroomReason=%s signature=%s%n", scenario.name(), d.collectibleOpportunityCount(),
                    d.regionCount(), d.retainedChains(), v2.ownSemiCollections(), v2.coupledOwnCollections(),
                    v2.hybridMarginScore4(), value.ownSemiCollections(), value.coupledOwnCollections(),
                    value.hybridMarginScore4(), semiHeadroom, coupledHeadroom, hybridHeadroom,
                    r3.beamResult().evaluation().base().deterministicSignature().equals(value.physicalSignature()),
                    d.allocationsConsidered(), d.allocationsMaterialized(), d.allocationsValid(), d.oracleCapReached(),
                    reason, value.physicalSignature());
            System.out.printf("V3_HEADROOM_EXPLANATION fixture=%s oracleVsV2Comparator=%d oracleVsNoRefuel=%s "
                            + "reason=%s graphCoverage=%.3f capReached=%s%n", scenario.name(), objectiveComparison,
                    v3.headroomReason().equals("BETTER_PATROL_ASSIGNMENT"), reason, d.coverageRatio(), d.oracleCapReached());
            System.out.printf("V3_REPRESENTATION_COVERAGE fixture=%s collectible=%d assigned=%d unassigned=%d "
                            + "generatedChains=%d retainedChains=%d represented=%d neverRepresented=%d ratio=%.3f "
                            + "graphBuildPathfindingExecutions=%d oracleSearchPathfindingExecutions=%d "
                            + "materializationPathfindingExecutions=%d graphMs=%d chainMs=%d allocationMs=%d materialMs=%d "
                            + "evaluationMs=%d totalMs=%d%n", scenario.name(), d.collectibleOpportunityCount(),
                    d.opportunitiesAssignedToRegions(), d.unassignedOpportunities(), d.generatedChains(), d.retainedChains(),
                    d.opportunitiesRepresentedInAnyChain(), d.opportunitiesNeverRepresented(), d.coverageRatio(),
                    d.graphBuildPathfindingExecutions(), d.oracleSearchPathfindingExecutions(),
                    d.materializationPathfindingExecutions(), d.graphBuildMillis(), d.chainGenerationMillis(),
                    d.allocationEnumerationMillis(), d.materializationMillis(), d.evaluationMillis(), d.totalOracleMillis());
        }
    }

    private static void runV3Phase1Representation(List<Scenario> scenarios) {
        System.out.println("V3_PHASE1_REPRESENTATION_AUDIT baseline=V2_R3 old=PHASE_0 new=DIVERSE_GRAPH");
        V3RepresentationOracle oracle = new V3RepresentationOracle();
        for (Scenario scenario : v3Scenarios(scenarios)) {
            StrategicOpportunityGraph graph = quietly(() -> new StrategicOpportunityGraphBuilder()
                    .build(scenario.state(), V3EdgeRetentionPolicy.DIVERSE_GRAPH));
            if (scenario.name().equals("large-6-agent-60-step")) {
                System.out.printf("V3_PHASE1_LARGE_REPRESENTATION fixture=%s opportunities=%d regions=%d possibleEdges=%d retainedEdges=%d "
                                + "avgOutgoing=%.3f maxOutgoing=%d localEdges=%d crossRegionEdges=%d longHopEdges=%d "
                                + "graphBuildPathfindingExecutions=%d representationSearchPathfindingExecutions=0%n",
                        scenario.name(), graph.opportunities().size(), graph.regions().size(), graph.possibleDirectedEdges(),
                        graph.retainedStrategicEdges(), graph.averageOutgoingEdges(), graph.maxOutgoingEdges(), graph.localEdges(),
                        graph.crossRegionEdges(), graph.longHopEdges(), graph.graphBuildPathfindingExecutions());
                continue;
            }
            JointTeamBeamR3Result r3Seed = quietly(() -> new JointTeamBeamR3Planner().planWithStats(scenario.state()));
            // Keep the representation oracle unseeded.  Passing the production/support
            // incumbent here would make its headline score a seed score, not a graph score.
            V3RepresentationResult result = quietly(() -> oracle.solve(scenario.state(),
                    new V3RepresentationConfig(5, 96, 512, 512), List.of()));
            var v2 = r3Seed.beamResult().evaluation().hybrid();
            StrategicOracleResult old = quietly(() -> new StrategicOracle().solve(scenario.state(),
                    V3Phase0Config.defaults(), List.of(r3Seed.plan())));
            var d = result.diagnostics();
            System.out.printf("V3_PHASE1_REPRESENTATION fixture=%s opportunities=%d regions=%d possibleEdges=%d retainedEdges=%d "
                            + "avgOutgoing=%.3f maxOutgoing=%d localEdges=%d crossRegionEdges=%d longHopEdges=%d "
                            + "v2Own=%d oldV3Own=%d newV3Own=%d v2Hybrid4=%d oldV3Hybrid4=%d newV3Hybrid4=%d "
                            + "oracleFoundExact=%s signature=%s graphMs=%d oracleMs=%d searchPathfinding=0%n",
                    scenario.name(), d.opportunityCount(), graph.regions().size(), d.possibleDirectedEdges(), d.retainedEdges(),
                    d.averageOutgoingEdges(), d.maxOutgoingEdges(), d.localEdges(), d.crossRegionEdges(), d.longHopEdges(),
                    v2.ownSemiCollections(), old.winner().ownSemiCollections(), result.winner().ownSemiCollections(),
                    v2.hybridMarginScore4(), old.winner().hybridMarginScore4(), result.winner().hybridMarginScore4(),
                    result.winner().ownSemiCollections() >= v2.ownSemiCollections(), result.winner().physicalSignature(),
                    d.graphBuildMillis(), d.representationOracleMillis());
            if (scenario.name().equals("5x5-raw-kind-zero")) {
                V3ExactEdgeCoverage coverage = V3ExactEdgeCoverage.audit(graph, List.of(
                        List.of(new Position(1), new Position(8), new Position(13)),
                        List.of(new Position(11), new Position(16), new Position(6)),
                        List.of(new Position(18), new Position(3))));
                System.out.printf("V3_EXACT_EDGE_COVERAGE fixture=%s exactTransitionCount=%d coveredTransitionCount=%d "
                                + "missingTransitionCount=%d coverageRatio=%.3f firstMissingTransition=%s edgeBudget=%s%n",
                        scenario.name(), coverage.exactTransitionCount(), coverage.coveredTransitionCount(),
                        coverage.missingTransitionCount(), coverage.coverageRatio(), coverage.firstMissingTransition(),
                        vn.ptit.procon.planner.v3.V3StrategicRepresentationAudit.edgeBudget(graph));
            }
        }
    }

    private static void runV3Phase2StrategicSearch(List<Scenario> scenarios) {
        System.out.println("V3_PHASE2_STRATEGIC_SEARCH baseline=V2_R3 graph=DIVERSE_GRAPH terminal=K16");
        StrategicTeamSearch search = new StrategicTeamSearch();
        for (Scenario scenario : v3Scenarios(scenarios)) {
            JointTeamBeamR3Result seed = quietly(() -> new JointTeamBeamR3Planner().planWithStats(scenario.state()));
            V3RepresentationResult oracle = quietly(() -> new V3RepresentationOracle().solve(scenario.state(),
                    new V3RepresentationConfig(5, 96, 512, 512), List.of()));
            StrategicSearchConfig config = scenario.name().equals("5x5-raw-kind-zero")
                    ? new StrategicSearchConfig(32, 512, 32, 16, 10, 64)
                    : scenario.name().equals("LIVE_LIKE_M6861_V3")
                            ? new StrategicSearchConfig(64, 1024, 64, 16, 12, 128)
                            : new StrategicSearchConfig(32, 128, 32, 16, 10, 16);
            StrategicSearchResult result = quietly(() -> search.solve(scenario.state(), config,
                    List.of(seed.plan()), oracle.winner().ownSemiCollections(), oracle.winner().hybridMarginScore4()));
            var d = result.diagnostics();
            System.out.printf("V3_STRATEGIC_SEARCH_SUMMARY fixture=%s v2Own=%d v2Hybrid4=%d oracleOwn=%d oracleHybrid4=%d "
                            + "boundedOwn=%d boundedHybrid4=%d recoveryRatio=%.3f allocations=%d/%d states=%d/%d/%d dedup=%d "
                            + "terminal=%d valid=%d coupled=%d searchMs=%d materialMs=%d coupledMs=%d deadline=%s pathfinding=%d "
                            + "sameV2=%s sameOracle=%s signature=%s%n", scenario.name(), seed.beamResult().evaluation().hybrid().ownSemiCollections(),
                    seed.beamResult().evaluation().hybrid().hybridMarginScore4(), oracle.winner().ownSemiCollections(),
                    oracle.winner().hybridMarginScore4(), result.winner().ownSemiCollections(), result.winner().hybridMarginScore4(),
                    d.recoveryRatio(), d.allocationsGenerated(), d.allocationsRetained(), d.statesGenerated(), d.statesUnique(),
                    d.statesExpanded(), d.statesDeduped(), d.terminalSkeletons(), d.validPlans(), d.coupledEvaluations(),
                    d.searchMillis(), d.materializationMillis(), d.coupledEvaluationMillis(), d.deadlineExceeded(),
                    d.searchPathfindingExecutions(), result.winner().physicalSignature().equals(seed.beamResult().evaluation().base().deterministicSignature()),
                    result.winner().physicalSignature().equals(oracle.winner().physicalSignature()), result.winner().physicalSignature());
        }
        System.out.println("V3_PHASE2_BUDGET_ABLATION configs=SMALL(16/64/16/8) MEDIUM(32/128/32/16) LARGE(48/256/48/24)");
        Scenario target = scenarios.stream().filter(s -> s.name().equals("5x5-raw-kind-zero")).findFirst().orElseThrow();
        JointTeamBeamR3Result seed = quietly(() -> new JointTeamBeamR3Planner().planWithStats(target.state()));
        for (String value : List.of("SMALL", "MEDIUM", "LARGE")) {
            StrategicSearchConfig config = switch (value) {
                case "SMALL" -> new StrategicSearchConfig(16, 64, 16, 8, 10, 8);
                case "MEDIUM" -> new StrategicSearchConfig(32, 128, 32, 16, 10, 16);
                default -> new StrategicSearchConfig(48, 256, 48, 24, 10, 24);
            };
            StrategicSearchResult result = quietly(() -> new StrategicTeamSearch().solve(target.state(), config, List.of(seed.plan())));
            System.out.printf("V3_BUDGET_ABLATION fixture=%s config=%s own=%d hybrid4=%d expanded=%d terminal=%d searchMs=%d totalMs=%d%n",
                    target.name(), value, result.winner().ownSemiCollections(), result.winner().hybridMarginScore4(),
                    result.diagnostics().statesExpanded(), result.diagnostics().coupledEvaluations(), result.diagnostics().searchMillis(),
                    result.diagnostics().searchMillis() + result.diagnostics().materializationMillis() + result.diagnostics().coupledEvaluationMillis());
        }
    }

    private static void runV3Phase23OfflineGeneralization(List<Scenario> scenarios) {
        System.out.println("V3_PHASE2_3_OFFLINE_GENERALIZATION production=FROZEN_V2_R3 v3=BENCHMARK_ONLY");
        StrategicTeamSearch search = new StrategicTeamSearch();
        List<Scenario> liveLike = List.of(
                scenarios.stream().filter(v -> v.name().equals("5x5-raw-kind-zero")).findFirst().orElseThrow(),
                scenarios.stream().filter(v -> v.name().equals("live-like-m6861")).findFirst().orElseThrow());
        int[][] modes = {{0, 0}, {0, 1}, {1, 0}, {1, 1}};
        for (Scenario scenario : liveLike) {
            StrategicSearchConfig base = phase23Config(scenario);
            System.out.printf("V3_TRAJECTORY_QUOTA_ABLATION fixture=%s%n", scenario.name());
            int[] own = new int[4];
            for (int i = 0; i < modes.length; i++) {
                final boolean trajectory = modes[i][0] == 1;
                final boolean perStateQuota = modes[i][1] == 1;
                StrategicSearchResult result = quietly(() -> search.solve(scenario.state(),
                        base.withAblation(trajectory, perStateQuota), List.of()));
                own[i] = result.winner().ownSemiCollections();
                var d = result.diagnostics();
                System.out.printf("  mode=%s own=%d hybrid4=%d states=%d expanded=%d children=%d terminals=%d valid=%d millis=%d%n",
                        modeName(trajectory, perStateQuota), result.winner().ownSemiCollections(),
                        result.winner().hybridMarginScore4(), d.statesGenerated(), d.statesExpanded(),
                        d.childrenGenerated(), d.terminalSkeletons(), d.validPlans(), d.searchMillis());
            }
            System.out.println("V3_CAUSAL_CLASSIFICATION fixture=" + scenario.name() + " result="
                    + (scenario.name().equals("live-like-m6861")
                            ? causalClassification(scenario.name(), own[0], own[1], own[2], own[3])
                            : "NOT_APPLICABLE_TARGET_ALREADY_REACHED"));
        }
        StrategicSearchResult quotaAudit = quietly(() -> search.solve(
                liveLike.getLast().state(), phase23Config(liveLike.getLast()), List.of()));
        var q = quotaAudit.diagnostics();
        System.out.printf("V3_CHILD_QUOTA_AUDIT expandedStates=%d expectedPerStateCap=%d "
                        + "perStateGeneratedChildren=min:%d median:%d max:%d statesIncorrectlyAffectedByGlobalCap=%d "
                        + "totalChildrenBeforeRetention=%d totalChildrenAfterPerStateCap=%d%n",
                q.statesExpanded(), q.expectedPerStateChildCap(), q.minChildrenPerExpandedState(),
                q.medianChildrenPerExpandedState(), q.maxChildrenPerExpandedState(),
                q.statesIncorrectlyAffectedByGlobalCap(), q.totalChildrenBeforeRetention(),
                q.totalChildrenAfterPerStateCap());

        List<Scenario> suite = phase23Suite(scenarios);
        int wins = 0, ties = 0, losses = 0;
        for (Scenario scenario : suite) {
            JointTeamBeamR3Result baseline = quietly(() -> new JointTeamBeamR3Planner().planWithStats(scenario.state()));
            StrategicSearchResult raw = quietly(() -> search.solve(scenario.state(), phase23Config(scenario), List.of()));
            StrategicSearchResult selected = quietly(() -> search.solve(scenario.state(), phase23Config(scenario),
                    List.of(baseline.plan())));
            var v2 = baseline.beamResult().evaluation().hybrid();
            var rawObjective = raw.rawWinner().objective();
            var chosen = selected.winner().objective();
            int order = HybridCalibratedMarginEvaluation.compare(chosen, v2);
            if (order < 0) wins++; else if (order == 0) ties++; else losses++;
            V3TerminalParityAudit.Result parity = V3TerminalParityAudit.audit(scenario.state(), raw);
            var d = selected.diagnostics();
            System.out.printf("V3_GENERALIZATION fixture=%s group=%s v2Own=%d v2Hybrid4=%d rawV3Own=%d rawV3Hybrid4=%d "
                            + "selectedOwn=%d selectedHybrid4=%d ownDelta=%d hybridDelta4=%d fallbackUsed=%s "
                            + "samePhysicalPlan=%s statesExpanded=%d terminalEvals=%d searchMillis=%d parityMatch=%s "
                            + "parity=%d/%d rawSignature=%s selectedSignature=%s reason=%s%n",
                    scenario.name(), phase23Group(scenario.name()), v2.ownSemiCollections(), v2.hybridMarginScore4(),
                    rawObjective.ownSemiCollections(), rawObjective.hybridMarginScore4(), selected.winner().ownSemiCollections(),
                    selected.winner().hybridMarginScore4(), selected.winner().ownSemiCollections() - v2.ownSemiCollections(),
                    selected.winner().hybridMarginScore4() - v2.hybridMarginScore4(), selected.fallbackUsed(),
                    selected.winner().physicalSignature().equals(baseline.beamResult().evaluation().base().deterministicSignature()),
                    d.statesExpanded(), d.coupledEvaluations(), d.searchMillis(), parity.exact(), parity.parityMatches(),
                    parity.terminalsChecked(), raw.rawWinner().physicalSignature(), selected.winner().physicalSignature(),
                    structuralReason(baseline.plan(), selected.winner().plan(), scenario.state()));
            System.out.printf("V3_TERMINAL_PARITY_SUMMARY fixture=%s terminalsChecked=%d parityMatches=%d "
                            + "parityMismatches=%d firstMismatch=%s%n", scenario.name(), parity.terminalsChecked(),
                    parity.parityMatches(), parity.parityMismatches(), parity.firstMismatch());
            if (scenario.name().equals("live-like-m6861")) printV3Witness(scenario, selected.winner().plan());
            System.out.printf("V3_INCUMBENT_NON_REGRESSION_AUDIT fixture=%s rawV3Own=%d rawV3Hybrid4=%d "
                            + "seedV2Own=%d seedV2Hybrid4=%d selectedOwn=%d selectedHybrid4=%d fallbackUsed=%s%n",
                    scenario.name(), rawObjective.ownSemiCollections(), rawObjective.hybridMarginScore4(),
                    v2.ownSemiCollections(), v2.hybridMarginScore4(), selected.winner().ownSemiCollections(),
                    selected.winner().hybridMarginScore4(), selected.fallbackUsed());
            System.out.printf("V3_STATE_SIZE fixture=%s statesGenerated=%d statesUnique=%d trajectoryCacheEntries=%d "
                            + "chronologyEventsProcessed=%d maxFrontier=%d cacheRequests=%d cacheHits=%d cacheMisses=%d "
                            + "chronologyReplays=%d normalizationMillis=%d%n", scenario.name(), d.statesGenerated(),
                    d.statesUnique(), d.trajectoryCacheEntries(), d.chronologyEventsProcessed(), d.maxFrontierSize(),
                    d.trajectoryCacheRequests(), d.trajectoryCacheHits(), d.trajectoryCacheMisses(), d.chronologyReplays(),
                    d.normalizationMillis());
        }
        System.out.printf("V3_GENERALIZATION_SCORECARD totalFixtures=%d v3Wins=%d ties=%d v3Losses=%d%n",
                suite.size(), wins, ties, losses);
        runV3LatencyTable(search, suite);
        StrategicSearchResult zeroBudget = quietly(() -> search.solve(liveLike.getFirst().state(),
                new StrategicSearchConfig(16, 0, 4, 8, 10, 8), List.of(baselinePlan(liveLike.getFirst()))));
        System.out.printf("V3_FALLBACK_AUDIT fixture=%s zeroBudget=%d fallbackUsed=%s legal=%s%n",
                liveLike.getFirst().name(), zeroBudget.winner().ownSemiCollections(), zeroBudget.fallbackUsed(),
                new DaySimulator().simulate(liveLike.getFirst().state(), zeroBudget.plan()) instanceof ValidDaySimulationResult);
    }

    private static void printV3Witness(Scenario scenario, TeamPlan plan) {
        var simulation = new DaySimulator().simulate(scenario.state(), plan);
        if (!(simulation instanceof ValidDaySimulationResult valid)) return;
        String claims = valid.events().stream().filter(UdonCollectedEvent.class::isInstance)
                .map(UdonCollectedEvent.class::cast)
                .map(event -> event.step() + ":agent" + event.agentId().value() + "@" + event.position().value()
                        + ":" + event.brand().value() + ":remaining=" + event.remainingStock())
                .collect(java.util.stream.Collectors.joining(","));
        System.out.printf("V3_LIVE_LIKE_WITNESS fixture=%s simulatorFinal=%d claims=%s "
                        + "intermediateCollections=%d supportEvents=%d%n", scenario.name(),
                valid.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum(), claims,
                valid.events().stream().filter(UdonCollectedEvent.class::isInstance).count(),
                valid.events().stream().filter(vn.ptit.procon.engine.RefueledEvent.class::isInstance).count());
    }

    private static TeamPlan baselinePlan(Scenario scenario) {
        return quietly(() -> new JointTeamBeamR3Planner().planWithStats(scenario.state()).plan());
    }

    private static StrategicSearchConfig phase23Config(Scenario scenario) {
        return scenario.name().contains("live-like")
                ? new StrategicSearchConfig(64, 1024, 64, 16, 12, 128)
                : scenario.name().contains("large")
                        ? new StrategicSearchConfig(32, 128, 32, 16, 10, 16)
                        : new StrategicSearchConfig(32, 512, 32, 16, 10, 64);
    }

    private static String modeName(boolean trajectory, boolean perState) {
        return (trajectory ? "TRAJECTORY" : "ENDPOINT") + "+" + (perState ? "FIXED_QUOTA" : "OLD_GLOBAL_QUOTA");
    }

    private static String causalClassification(String fixture, int endpointOld, int endpointFixed,
            int trajectoryOld, int trajectoryFixed) {
        int target = fixture.equals("5x5-raw-kind-zero") ? 8 : 9;
        boolean a = endpointOld >= target, b = endpointFixed >= target, c = trajectoryOld >= target, d = trajectoryFixed >= target;
        if (a && b && c && d) return "BOTH_INDEPENDENTLY_SUFFICIENT";
        if (!a && b && !c && d) return "BOTH_REQUIRED";
        if (!a && b && c && d) return "CHILD_QUOTA_PRIMARY";
        if (!a && !b && c && d) return "TRAJECTORY_PRIMARY";
        return "BOTH_REQUIRED";
    }

    private static List<Scenario> phase23Suite(List<Scenario> scenarios) {
        return List.of(scenarios.stream().filter(v -> v.name().equals("global-vs-greedy")).findFirst().orElseThrow(),
                scenarios.stream().filter(v -> v.name().equals("5x5-raw-kind-zero")).findFirst().orElseThrow(),
                scenarios.stream().filter(v -> v.name().equals("live-like-m6861")).findFirst().orElseThrow(),
                scenarios.stream().filter(v -> v.name().equals("large-6-agent-60-step")).findFirst().orElseThrow(),
                mediumRegionRelocation(), mediumSharedStock(), mediumSupportChain(), largeDistributed(), largeDense());
    }

    private static String phase23Group(String name) {
        if (name.startsWith("LARGE") || name.equals("large-6-agent-60-step")) return "LARGE";
        if (name.startsWith("MEDIUM")) return "MEDIUM";
        if (name.equals("5x5-raw-kind-zero") || name.equals("global-vs-greedy")) return "CERTIFIED_SMALL";
        return "LIVE_LIKE";
    }

    private static String structuralReason(TeamPlan baseline, TeamPlan candidate, DayState state) {
        if (new vn.ptit.procon.planner.v3.FrozenObjectiveEvaluator(state).evaluate(candidate).isEmpty()) return "OTHER_UNPROVEN";
        if (baseline.actionsByAgent().equals(candidate.actionsByAgent())) return "NONE";
        List<Integer> baselineClaims = collectionPositions(state, baseline);
        List<Integer> candidateClaims = collectionPositions(state, candidate);
        return baselineClaims.equals(candidateClaims) ? "BETTER_TEAM_ALLOCATION" : "CHRONOLOGY";
    }

    private static void runV3LatencyTable(StrategicTeamSearch search, List<Scenario> suite) {
        for (Scenario scenario : suite) {
            List<Long> samples = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                StrategicSearchResult result = quietly(() -> search.solve(scenario.state(), phase23Config(scenario), List.of()));
                if (i > 0) samples.add(result.diagnostics().searchMillis());
            }
            samples.sort(Long::compareTo);
            long p95 = samples.get(Math.min(samples.size() - 1, (int) Math.ceil(samples.size() * .95) - 1));
            System.out.printf("V3_LATENCY fixture=%s min=%d median=%d p95=%d max=%d%n", scenario.name(),
                    samples.getFirst(), samples.get(samples.size() / 2), p95, samples.getLast());
        }
    }

    private static Scenario mediumRegionRelocation() {
        return new Scenario("MEDIUM_REGION_RELOCATION", state(36, 12, 3, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(11), 8),
                AgentState.patrol(PATROL_2, new Position(24), 8)), List.of(
                spot("A", 1, 1), spot("B", 10, 1), spot("C", 13, 1), spot("D", 22, 1), spot("A", 25, 1)), List.of()));
    }

    private static Scenario mediumSharedStock() {
        return new Scenario("MEDIUM_SHARED_STOCK", state(30, 10, 2, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(9), 8),
                AgentState.patrol(PATROL_2, new Position(10), 8)), List.of(
                spot("A", 2, 2), spot("B", 12, 2), spot("C", 17, 1), spot("D", 19, 1)), List.of()));
    }

    private static Scenario mediumSupportChain() {
        return new Scenario("MEDIUM_SUPPORT_CHAIN", state(30, 12, 2, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(11), 8),
                AgentState.patrol(PATROL_2, new Position(12), 8), AgentState.refuel(REFUEL_0, new Position(6))), List.of(
                spot("A", 1, 1), spot("B", 5, 1), spot("C", 13, 1), spot("D", 18, 1), spot("A", 23, 1)), List.of()));
    }

    private static Scenario largeDistributed() {
        return new Scenario("LARGE_DISTRIBUTED", state(60, 16, 10, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(15), 8),
                AgentState.patrol(PATROL_2, new Position(80), 8), AgentState.patrol(new AgentId(3), new Position(95), 8),
                AgentState.refuel(REFUEL_0, new Position(72))), List.of(
                spot("A", 2, 1), spot("B", 14, 1), spot("C", 35, 1), spot("D", 47, 1), spot("A", 82, 1),
                spot("B", 94, 1), spot("C", 126, 1), spot("D", 143, 1)), List.of()));
    }

    private static Scenario largeDense() {
        return new Scenario("LARGE_DENSE", state(60, 12, 12, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(23), 8),
                AgentState.patrol(PATROL_2, new Position(48), 8), AgentState.patrol(new AgentId(3), new Position(95), 8),
                AgentState.refuel(REFUEL_0, new Position(72))), List.of(
                spot("A", 1, 2), spot("B", 2, 2), spot("C", 13, 1), spot("D", 14, 1), spot("A", 25, 1),
                spot("B", 26, 1), spot("C", 49, 2), spot("D", 50, 1), spot("A", 73, 1), spot("B", 74, 1),
                spot("C", 96, 1), spot("D", 97, 1)), List.of()));
    }

    private static List<Scenario> v3Scenarios(List<Scenario> scenarios) {
        return List.of(
                new Scenario("V3_REGION_SPLIT_TRAP", state(18, 6, 2, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 6),
                        AgentState.patrol(PATROL_1, new Position(11), 6)), List.of(
                        spot("A", 1, 1), spot("B", 4, 1), spot("C", 7, 1), spot("D", 10, 1)), List.of())),
                new Scenario("V3_CHAIN_CONTINUATION_TRAP", state(16, 16, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 8),
                        AgentState.patrol(PATROL_1, new Position(15), 8)), List.of(
                        spot("A", 1, 1), spot("B", 2, 1), spot("C", 3, 1), spot("D", 14, 1)), List.of())),
                new Scenario("V3_TEAM_OVERLAP_TRAP", state(20, 10, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 8),
                        AgentState.patrol(PATROL_1, new Position(9), 8),
                        AgentState.patrol(PATROL_2, new Position(4), 8)), List.of(
                        spot("A", 2, 2), spot("B", 4, 2), spot("C", 6, 2)), List.of())),
                new Scenario("V3_CROSS_REGION_ROUTE", state(24, 12, 2, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 8),
                        AgentState.patrol(PATROL_1, new Position(23), 8),
                        AgentState.patrol(PATROL_2, new Position(11), 8)), List.of(
                        spot("A", 1, 1), spot("B", 10, 1), spot("C", 13, 1), spot("D", 22, 1)), List.of())),
                scenarios.stream().filter(value -> value.name().equals("high-stock")).findFirst().orElseThrow(),
                scenarios.stream().filter(value -> value.name().equals("three-patrol-support")).findFirst().orElseThrow(),
                scenarios.stream().filter(value -> value.name().equals("5x5-raw-kind-zero")).findFirst().orElseThrow(),
                new Scenario("V3_NO_REFUEL_STRATEGIC", state(18, 9, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 8),
                        AgentState.patrol(PATROL_1, new Position(8), 8)), List.of(
                        spot("A", 1, 1), spot("B", 7, 1)), List.of())),
                new Scenario("LIVE_LIKE_M6861_V3", liveLikeM6861Scenario().state()),
                scenarios.stream().filter(value -> value.name().equals("global-vs-greedy")).findFirst().orElseThrow(),
                scenarios.stream().filter(value -> value.name().equals("large-6-agent-60-step")).findFirst().orElseThrow());
    }

    private static void runExactStrategicOracle(List<Scenario> scenarios) {
        System.out.println("EXACT_ORACLE_THREE_WAY baseline=V2_R3 v3=PHASE_0 objective=OWN_COLLECTIONS_THEN_FROZEN_HYBRID");
        ExactStrategicOracle oracle = new ExactStrategicOracle();
        ExactRepresentabilityAuditor representability = new ExactRepresentabilityAuditor();
        int proven = 0, v2Optimal = 0, v3Optimal = 0;
        for (Scenario scenario : exactFixtures(scenarios)) {
            JointTeamBeamR3Result v2 = quietly(() -> new JointTeamBeamR3Planner().planWithStats(scenario.state()));
            boolean supportFixture = scenario.name().contains("SUPPORT") || scenario.name().equals("two-patrol-refuel-tour")
                    || scenario.name().equals("LIVE_LIKE_EXACT_SMALL");
            JointTeamBeamR3Result supportSeed = supportFixture
                    ? quietly(() -> new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                            .withRootFamilyAuditMode(vn.ptit.procon.planner.v2.R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY))
                            .planWithStats(scenario.state())) : v2;
            StrategicOracleResult v3 = quietly(() -> new StrategicOracle().solve(scenario.state(), V3Phase0Config.defaults(), List.of(supportSeed.plan())));
            ExactOracleConfig config = scenario.name().equals("EXACT_5X5_SMALL")
                    ? new ExactOracleConfig(50_000, 1_000, 32, 12, 4, 30)
                    : scenario.name().equals("LARGE_6_AGENT_BOUNDED")
                            ? new ExactOracleConfig(2, 300, 64, 12, 6, 60)
                            : new ExactOracleConfig(100_000, 2_000, 32, 12, 6, 30);
            List<TeamPlan> oracleSeeds = supportFixture
                    ? List.of(v2.plan(), v3.winner().plan(), supportSeed.plan())
                    : List.of(v2.plan(), v3.winner().plan());
            ExactOracleResult exact = quietly(() -> oracle.solve(scenario.state(), config, oracleSeeds));
            var v2Representation = quietly(() -> representability.auditV2(scenario.state(), exact.bestCollectionSkeleton()));
            var v3Representation = representability.auditV3(v3, exact.bestCollectionSkeleton());
            int v2OwnGap = exact.bestFoundOwnSemi() - v2.beamResult().evaluation().hybrid().ownSemiCollections();
            int v2HybridGap = exact.bestFoundHybrid4() - v2.beamResult().evaluation().hybrid().hybridMarginScore4();
            int v3OwnGap = exact.bestFoundOwnSemi() - v3.winner().ownSemiCollections();
            int v3HybridGap = exact.bestFoundHybrid4() - v3.winner().hybridMarginScore4();
            if (exact.ownCollectionOptimalityProven()) proven++;
            if (exact.ownCollectionOptimalityProven() && v2OwnGap <= 0) v2Optimal++;
            if (exact.ownCollectionOptimalityProven() && v3OwnGap <= 0) v3Optimal++;
            String classification = exact.ownCollectionOptimalityProven()
                    ? (v2OwnGap <= 0 ? "SATURATED" : "V2_SEARCH_GAP") : "ORACLE_NOT_PROVEN";
            System.out.printf("EXACT_ORACLE_COMPARISON fixture=%s agents=%d daySteps=%d collectibleTargets=%d "
                            + "v2Semi=%d v2Hybrid4=%d v3Semi=%d v3Hybrid4=%d exactOwnSemi=%d exactHybrid4=%d "
                            + "ownOptimalityProven=%s hybridOptimalityProven=%s v2OwnGap=%d v2HybridGap=%d "
                            + "v3OwnGap=%d v3HybridGap=%d v2ExactSkeletonRepresentable=UNAUDITED "
                            + "v3ExactSkeletonRepresentable=UNAUDITED classification=%s statesExpanded=%d memoHits=%d "
                            + "boundPrunes=%d validLeaves=%d oracleMillis=%d capReached=%s signature=%s%n",
                    scenario.name(), scenario.state().agents().size(), scenario.state().stepBudget(),
                    scenario.state().matchData().udonSpots().size(), v2.beamResult().evaluation().hybrid().ownSemiCollections(),
                    v2.beamResult().evaluation().hybrid().hybridMarginScore4(), v3.winner().ownSemiCollections(),
                    v3.winner().hybridMarginScore4(), exact.bestFoundOwnSemi(), exact.bestFoundHybrid4(),
                    exact.ownCollectionOptimalityProven(), exact.hybridOptimalityProven(), v2OwnGap, v2HybridGap,
                    v3OwnGap, v3HybridGap, classification, exact.diagnostics().statesExpanded(),
                    exact.diagnostics().memoHits(), exact.diagnostics().boundPrunes(), exact.diagnostics().validLeafPlans(),
                    exact.diagnostics().wallMillis(), exact.diagnostics().searchCapReached() || exact.diagnostics().wallCapReached(),
                    exact.bestPhysicalSignature());
            System.out.printf("V2_EXACT_OPTIMUM_REPRESENTABILITY fixture=%s totalOptimalTransitions=%d "
                            + "representableTransitions=%d missingTransitions=%d optimalTargetsPresentInV2CandidateUniverse=%s "
                            + "optimalTransitionsRepresentable=%s optimalSkeletonRepresentable=%s firstMissingTransition=%s reason=%s%n",
                    scenario.name(), v2Representation.totalOptimalTransitions(), v2Representation.representableTransitions(),
                    v2Representation.missingTransitions(), v2Representation.optimalTargetsPresentInV2CandidateUniverse(),
                    v2Representation.optimalTransitionsRepresentable(), v2Representation.optimalSkeletonRepresentable(),
                    v2Representation.firstMissingTransition(), v2Representation.reason());
            System.out.printf("V3_EXACT_OPTIMUM_REPRESENTABILITY fixture=%s optimalOpportunitiesInGraph=%d "
                            + "optimalEdgesInGraph=%d optimalTransitionsInAnyChain=%d optimalRegionTransitionsRepresentable=%s "
                            + "optimalTeamAllocationRepresentable=%s optimalSkeletonRepresentable=%s firstMissingElement=%s reason=%s%n",
                    scenario.name(), v3Representation.optimalOpportunitiesInGraph(), v3Representation.optimalEdgesInGraph(),
                    v3Representation.optimalTransitionsInAnyChain(), v3Representation.optimalRegionTransitionsRepresentable(),
                    v3Representation.optimalTeamAllocationRepresentable(), v3Representation.optimalSkeletonRepresentable(),
                    v3Representation.firstMissingElement(), v3Representation.reason());
            System.out.printf("EXACT_TO_V2_PREFIX_TRACE fixture=%s exactSkeletonRepresentable=%s "
                            + "firstDivergence=%s reason=%s%n", scenario.name(),
                    v2Representation.optimalSkeletonRepresentable(),
                    v2Representation.optimalSkeletonRepresentable() ? "NOT_PROVEN" : v2Representation.firstMissingTransition(),
                    v2Representation.reason());
            System.out.printf("EXACT_TO_V3_REPRESENTATION_TRACE fixture=%s exactSkeletonRepresentable=%s "
                            + "firstDivergence=%s reason=%s%n", scenario.name(),
                    v3Representation.optimalSkeletonRepresentable(),
                    v3Representation.optimalSkeletonRepresentable() ? "NOT_PROVEN" : v3Representation.firstMissingElement(),
                    v3Representation.reason());
            System.out.printf("EXACT_BEST_COLLECTION_SKELETON fixture=%s skeleton=%s%n",
                    scenario.name(), exact.bestCollectionSkeleton());
            printSkeletonDetails(scenario, exact);
            if (scenario.name().equals("EXACT_5X5_SMALL")) {
                System.out.printf("EXACT_V2_VS_8_SKELETON fixture=%s v2Collections=%s exactCollections=%s "
                                + "extraExactCollections=%s enablingDecision=%s%n", scenario.name(),
                        collectionPositions(scenario.state(), v2.plan()),
                        exact.bestCollectionSkeleton().agents().stream().flatMap(value -> value.visits().stream())
                                .map(value -> Integer.toString(value.position())).toList(),
                        exactOnlyPositions(scenario.state(), v2.plan(), exact),
                        exactOnlyPositions(scenario.state(), v2.plan(), exact).isEmpty()
                                ? "NONE" : "EXACT_ROUTE_TO_EXTRA_TARGET");
            }
            System.out.printf("EXACT_ORACLE_CORRECTNESS_AUDIT fixture=%s statesExpanded=%d transitionsGenerated=%d "
                            + "memoHits=%d memoMisses=%d uniqueExactStates=%d boundPrunes=%d illegalTransitionsRejected=%d "
                            + "leafPlans=%d validLeafPlans=%d bestOwn=%d bestHybrid4=%d ownOptimalityProven=%s "
                            + "hybridOptimalityProven=%s searchCapReached=%s wallCapReached=%s routeCatalogBuildPathfindingExecutions=%d "
                            + "oracleSearchPathfindingExecutions=%d materializationPathfindingExecutions=%d%n",
                    scenario.name(), exact.diagnostics().statesExpanded(), exact.diagnostics().transitionsGenerated(),
                    exact.diagnostics().memoHits(), exact.diagnostics().memoMisses(), exact.diagnostics().uniqueExactStates(),
                    exact.diagnostics().boundPrunes(), exact.diagnostics().illegalTransitionsRejected(), exact.diagnostics().leafPlans(),
                    exact.diagnostics().validLeafPlans(), exact.diagnostics().bestOwn(), exact.diagnostics().bestHybrid4(),
                    exact.diagnostics().ownOptimalityProven(), exact.diagnostics().hybridOptimalityProven(),
                    exact.diagnostics().searchCapReached(), exact.diagnostics().wallCapReached(),
                    exact.diagnostics().routeCatalogBuildPathfindingExecutions(), exact.diagnostics().oracleSearchPathfindingExecutions(),
                    exact.diagnostics().materializationPathfindingExecutions());
            var audit = exact.audit();
            System.out.printf("EXACT_ORACLE_INCUMBENT_AUDIT fixture=%s seededV2Own=%d seededV2Hybrid4=%d "
                            + "seededV3Own=%d seededV3Hybrid4=%d initialOracleOwnIncumbent=%d "
                            + "initialOracleHybridIncumbent=%d incumbentSource=%s incumbentPlanValid=%s%n",
                    scenario.name(), audit.seededV2Own(), audit.seededV2Hybrid4(), audit.seededV3Own(),
                    audit.seededV3Hybrid4(), audit.initialOracleOwnIncumbent(), audit.initialOracleHybridIncumbent(),
                    audit.incumbentSource(), audit.incumbentPlanValid());
            System.out.printf("EXACT_SUPPORT_CONTINUATION_AUDIT fixture=%s supportRootsGenerated=%d "
                            + "supportRootsReplayed=%d supportRootsAccepted=%d continuationStatesExpanded=%d "
                            + "continuationBestOwn=%d continuationBestHybrid4=%d%n",
                    scenario.name(), audit.supportRootsGenerated(), audit.supportRootsReplayed(),
                    audit.supportRootsAccepted(), audit.continuationStatesExpanded(), audit.continuationBestOwn(),
                    audit.continuationBestHybrid4());
            System.out.printf("EXACT_TERMINALIZATION_AUDIT fixture=%s statesWithStopBranch=%d "
                            + "stopBranchesGenerated=%d uniqueTerminalSkeletons=%d fullyStoppedLeaves=%d "
                            + "partiallyStoppedThenCompletedLeaves=%d%n",
                    scenario.name(), audit.statesWithStopBranch(), audit.stopBranchesGenerated(),
                    audit.uniqueTerminalSkeletons(), audit.fullyStoppedLeaves(), audit.partiallyStoppedThenCompletedLeaves());
            System.out.printf("EXACT_BOUND_AUDIT fixture=%s lowerBoundOwn=%d trivialUpperBound=%d "
                            + "tightUpperBound=%d heuristicUpperBoundOwn=%d certifiedUpperBoundOwn=%d "
                            + "certifiedUpperBoundUsed=%d ownGapCertified=%d ownOptimalityProven=%s "
                            + "tightBoundCertified=%s boundSourceUsedForProof=%s hybridUpperBoundAvailable=%s%n",
                    scenario.name(), exact.bestFoundOwnSemi(), audit.trivialUpperBound(), audit.tightUpperBound(),
                    exact.heuristicUpperBoundOwn(), exact.certifiedUpperBoundOwn(), exact.certifiedUpperBoundOwn(),
                    exact.optimalityGap(), exact.ownCollectionOptimalityProven(), audit.tightBoundCertified(),
                    audit.boundSourceUsedForProof(), audit.hybridUpperBoundAvailable());
            var boundAudit = exact.boundCertificationAudit();
            System.out.printf("EXACT_BOUND_CERTIFICATION_AUDIT fixture=%s stateId=%s currentCollections=%d "
                            + "remainingStock=%d trivialBound=%d candidateTightBound=%d actualContinuationOptimum=%s "
                            + "candidateCertified=%s boundSourceUsedForProof=%s%n", scenario.name(), boundAudit.stateId(),
                    boundAudit.currentCollections(), boundAudit.remainingStock(), boundAudit.trivialBound(),
                    boundAudit.candidateTightBound(), boundAudit.actualContinuationOptimum(),
                    boundAudit.candidateCertified(), boundAudit.boundSourceUsedForProof());
            System.out.printf("EXACT_STATE_CANONICALIZATION_AUDIT fixture=%s statesBefore=%d statesAfter=%d "
                            + "canonicalMergeCount=%d agentSymmetryMerges=%d stockOrderingMerges=%d "
                            + "memoHits=%d branchOrderPolicy=%s%n",
                    scenario.name(), audit.statesBeforeCanonicalization(), audit.statesAfterCanonicalization(),
                    audit.canonicalMergeCount(), audit.agentSymmetryMerges(), audit.stockOrderingMerges(),
                    exact.diagnostics().memoHits(), audit.branchOrderPolicy());
        }
        System.out.printf("BENCHMARK_SATURATION_RATIO exactProvenFixtures=%d v2OptimalFixtures=%d v3OptimalFixtures=%d saturation=%.3f%n",
                proven, v2Optimal, v3Optimal, proven == 0 ? 0.0 : (double) v2Optimal / proven);
        runFiveByFiveWindows(scenarios);
    }

    private static void runFiveByFiveWindows(List<Scenario> scenarios) {
        Scenario scenario = scenarios.stream().filter(v -> v.name().equals("5x5-raw-kind-zero")).findFirst().orElse(null);
        if (scenario == null) return;
        JointTeamBeamR3Result v2 = quietly(() -> new JointTeamBeamR3Planner().planWithStats(scenario.state()));
        StrategicOracleResult v3 = quietly(() -> new StrategicOracle().solve(scenario.state(), V3Phase0Config.defaults(),
                List.of(v2.plan())));
        for (long window : List.of(1_000L, 5_000L, 10_000L)) {
            ExactOracleResult result = quietly(() -> new ExactStrategicOracle().solve(scenario.state(),
                    new ExactOracleConfig(250_000, window, 64, 12, 4, 30),
                    List.of(v2.plan(), v3.winner().plan())));
            System.out.printf("EXACT_5X5_WINDOW millis=%d timeToFind7=SEEDED timeToFind8=%s "
                            + "bestFoundOwn=%d trivialCertifiedUpperBound=%d tightCertifiedUpperBound=%s "
                            + "certifiedUpperBoundUsed=%d ownGapCertified=%d statesExpanded=%d memoHits=%d boundPrunes=%d "
                            + "ownOptimalityProven=%s hybridOptimalityProven=%s oracleMillis=%d%n", window,
                    result.bestFoundOwnSemi() >= 8 ? "FOUND" : "NOT_FOUND", result.bestFoundOwnSemi(),
                    result.trivialCertifiedUpperBoundOwn(), result.tightCertifiedUpperBoundOwn().isPresent()
                            ? Integer.toString(result.tightCertifiedUpperBoundOwn().orElseThrow()) : "UNAVAILABLE",
                    result.certifiedUpperBoundOwn(), result.optimalityGap(), result.diagnostics().statesExpanded(),
                    result.diagnostics().memoHits(), result.diagnostics().boundPrunes(), result.ownCollectionOptimalityProven(),
                    result.hybridOptimalityProven(), result.diagnostics().wallMillis());
        }
    }

    private static void printSkeletonDetails(Scenario scenario, ExactOracleResult exact) {
        if (!scenario.name().equals("EXACT_5X5_SMALL")) return;
        for (var agent : exact.bestCollectionSkeleton().agents()) {
            for (var visit : agent.visits()) {
                System.out.printf("EXACT_8_COLLECTION fixture=%s patrol=%d from=%d target=%d arrivalStep=%d "
                                + "fuelBefore=%d fuelAfter=%d stockBefore=%d stockAfter=%d brand=%s%n",
                        scenario.name(), agent.agentId().value(), visit.fromPosition(), visit.position(), visit.arrivalStep(),
                        visit.fuelBefore(), visit.fuelAfter(), visit.stockBefore(), visit.stockAfter(), visit.brand().value());
            }
        }
        exact.bestCollectionSkeleton().supportEvents().forEach(event ->
                System.out.printf("EXACT_8_SUPPORT_EVENT fixture=%s step=%d position=%d provenance=%s%n",
                        scenario.name(), event.step(), event.position(), event.provenance()));
    }

    private static List<Integer> collectionPositions(DayState state, TeamPlan plan) {
        if (!(new DaySimulator().simulate(state, plan) instanceof ValidDaySimulationResult valid)) return List.of();
        return valid.events().stream().filter(UdonCollectedEvent.class::isInstance).map(UdonCollectedEvent.class::cast)
                .map(value -> value.position().value()).toList();
    }

    private static List<Integer> exactOnlyPositions(DayState state, TeamPlan v2, ExactOracleResult exact) {
        java.util.Set<Integer> v2Positions = new java.util.LinkedHashSet<>(collectionPositions(state, v2));
        return exact.bestCollectionSkeleton().agents().stream().flatMap(value -> value.visits().stream())
                .map(value -> value.position()).filter(value -> !v2Positions.contains(value)).distinct().toList();
    }

    private static List<Scenario> exactFixtures(List<Scenario> scenarios) {
        return List.of(
                new Scenario("EXACT_TINY_MANUAL_OPTIMUM", state(8, 8, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(7), 8)),
                        List.of(spot("A", 1, 1), spot("B", 3, 1), spot("C", 6, 1)), List.of())),
                new Scenario("EXACT_SHARED_STOCK", state(8, 8, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(4), 8)),
                        List.of(spot("A", 2, 2)), List.of())),
                new Scenario("EXACT_TEAM_SPLIT", state(12, 9, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(8), 8)),
                        List.of(spot("A", 1, 1), spot("B", 2, 1), spot("C", 6, 1), spot("D", 7, 1)), List.of())),
                new Scenario("EXACT_CHAIN_VS_GREEDY", state(10, 10, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 8)),
                        List.of(spot("A", 1, 1), spot("B", 2, 1), spot("C", 5, 1)), List.of())),
                new Scenario("EXACT_CROSS_REGION", state(16, 12, 2, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(23), 8)),
                        List.of(spot("A", 1, 1), spot("B", 10, 1), spot("C", 13, 1), spot("D", 22, 1)), List.of())),
                scenarios.stream().filter(value -> value.name().equals("two-patrol-refuel-tour")).findFirst().orElseThrow(),
                new Scenario("EXACT_SUPPORT_NOT_NEEDED", state(12, 8, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 8), AgentState.patrol(PATROL_1, new Position(7), 8),
                        AgentState.refuel(REFUEL_0, new Position(3))), List.of(spot("A", 1, 1), spot("B", 6, 1)), List.of())),
                scenarios.stream().filter(value -> value.name().equals("global-vs-greedy")).findFirst().orElseThrow(),
                scenarios.stream().filter(value -> value.name().equals("5x5-raw-kind-zero")).map(value -> new Scenario("EXACT_5X5_SMALL", value.state())).findFirst().orElseThrow(),
                new Scenario("LIVE_LIKE_EXACT_SMALL", state(18, 6, 2, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 5), AgentState.patrol(PATROL_1, new Position(11), 5),
                        AgentState.refuel(REFUEL_0, new Position(6))), List.of(
                                spot("A", 1, 1), spot("B", 4, 1), spot("C", 7, 1), spot("D", 10, 1)), List.of())),
                scenarios.stream().filter(value -> value.name().equals("large-6-agent-60-step")).map(value -> new Scenario("LARGE_6_AGENT_BOUNDED", value.state())).findFirst().orElseThrow());
    }

    private static void runCompetitiveTargetAblation(List<Scenario> scenarios) {
        System.out.println("V2_COMPETITIVE_TARGET_ABLATION budget=48/64/24/4 K16");
        for (Scenario scenario : scenarios) {
            for (CompetitiveTargetPolicy policy : CompetitiveTargetPolicy.values()) {
                JointTeamBeamR3Config config = JointTeamBeamR3Config.defaults()
                        .withCompetitiveTargetPolicy(policy);
                JointTeamBeamR3Result result = quietly(() -> new JointTeamBeamR3Planner(config)
                        .planWithStats(scenario.state()));
                var h = result.beamResult().evaluation().hybrid();
                var a = result.beamResult().competitiveAudit();
                double rawToSemi = a.rawPlannedCollections() == 0 ? 0.0
                        : (double) a.semiCollections() / a.rawPlannedCollections();
                double rawToCoupled = a.rawPlannedCollections() == 0 ? 0.0
                        : (double) a.coupledOwnCollections() / a.rawPlannedCollections();
                System.out.printf("competitive fixture=%s policy=%s raw=%d semi=%d coupled=%d baseOpp=%d cOpp=%d "
                                + "hybrid=%d rawToSemi=%.3f rawToCoupled=%.3f recall=%.3f unique=%d expanded=%d "
                                + "children=%d planningMs=%d searchPf=%d%n", scenario.name(), policy,
                        a.rawPlannedCollections(), a.semiCollections(), a.coupledOwnCollections(),
                        h.opponentBaselineCollections(), h.coupledOpponentCollections(), h.hybridMarginScore4(),
                        rawToSemi, rawToCoupled, a.winningTargetRecall(), result.beamResult().stats().uniqueStates(),
                        result.beamResult().stats().expandedStates(), result.beamResult().stats().generatedChildren(),
                        result.stats().wallPlanningMillis(), result.beamResult().stats().searchPathfindingExecutions());
            }
        }
    }

    /**
     * Diagnostic-only relaxed search. It is opt-in because the relaxed profiles intentionally
     * exceed the production budget; no profile is wired into runtime selection.
     */
    private static void runCollectionSearchOracle(List<Scenario> scenarios) {
        System.out.println("COLLECTION_SEARCH_ORACLE production=48/64/24/4 relaxed=A:96/128/24/6,B:192/256/24/8,C:256/512/24/ALL");
        List<OracleProfile> profiles = List.of(
                new OracleProfile("PRODUCTION", 48, 64, 24, 4),
                new OracleProfile("TARGET_ALL_SAME_BUDGET", 48, 64, 24, Integer.MAX_VALUE),
                new OracleProfile("RELAXED_A", 96, 128, 24, 6),
                new OracleProfile("RELAXED_B", 192, 256, 24, 8),
                new OracleProfile("RELAXED_C", 256, 512, 24, Integer.MAX_VALUE));
        for (Scenario scenario : collectionAuditScenarios(scenarios)) {
            V2CollectionSearchAudit productionAudit = null;
            for (OracleProfile profile : profiles) {
                JointTeamBeamConfig config = new JointTeamBeamConfig(profile.beamWidth(),
                        profile.expanded(), profile.children(), profile.targets(), 16,
                        V2SearchPolicy.R1_CONTROL, V2CollectionAuditMode.TRACE);
                JointTeamBeamResult result = quietly(() -> new JointTeamBeamPlanner(config).planWithStats(scenario.state()));
                V2CollectionSearchAudit audit = result.collectionAudit();
                if (profile.name().equals("PRODUCTION")) productionAudit = audit;
                double winningTargetRecall = productionAudit == null ? 1.0
                        : audit.winningTargetRecallAgainst(productionAudit);
                var h = result.evaluation().hybrid();
                System.out.printf("COLLECTION_ORACLE fixture=%s profile=%s semi=%d brands=%d hybrid=%d "
                                + "expanded=%d children=%d unique=%d terminals=%d planningMs=%d highWater=%d "
                                + "highWaterState=%s highWaterMaterialized=%s highWaterTerminalSemi=%s highWaterLoss=%s "
                                + "bestTerminalSemi=%d selectedTerminalSemi=%d targetReachable=%d "
                                + "targetBefore=%d targetAfter=%d truncations=%d stopOnly=%d postZeroGain=%d "
                                + "dedupHigher=%d conservationViolations=%d winningTargetRecall=%.3f signature=%s%n",
                        scenario.name(), profile.name(), h.ownSemiCollections(), h.ownSemiBrands(),
                        h.hybridMarginScore4(), result.stats().expandedStates(), result.stats().generatedChildren(),
                        result.stats().uniqueStates(), result.stats().terminalPlansEvaluated(), result.stats().planningMillis(),
                        audit.maxSecuredCollectionsSeen(), audit.highWaterStateId(),
                        audit.highWaterEventuallyMaterialized(), audit.highWaterEventualTerminalSemiCollections(),
                        audit.highWaterRejectionOrLossReason(), audit.bestTerminalOwnSemiCollections(),
                        audit.selectedTerminalSemiCollections(),
                        audit.totalReachableTargetCandidates(), audit.candidatesBeforeTruncation(),
                        audit.candidatesAfterConfiguredTopK(), audit.targetTruncationCount(),
                        audit.stopOnlyExpansionCount(), audit.collectionsGainedAfterPotentialZero(),
                        audit.higherCollectionStateLostToDedup(), audit.securedCollectionConservationViolations(),
                        winningTargetRecall,
                        result.evaluation().base().deterministicSignature());
            }
        }
    }

    private static List<Scenario> collectionAuditScenarios(List<Scenario> scenarios) {
        return List.of(
                new Scenario("TARGET_TRUNCATION_TRAP", largeContentionScenario().state()),
                new Scenario("AGENT_SCHEDULING_TRAP", scenarios.stream()
                        .filter(value -> value.name().equals("global-vs-greedy")).findFirst().orElseThrow().state()),
                new Scenario("STOP_BUDGET_TRAP", state(30, 5, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 8),
                        AgentState.patrol(PATROL_1, new Position(2), 8),
                        AgentState.patrol(PATROL_2, new Position(4), 8)), List.of(), List.of())),
                new Scenario("HIGH_COLLECTION_CONSERVATION", scenarios.stream()
                        .filter(value -> value.name().equals("high-stock")).findFirst().orElseThrow().state()),
                new Scenario("LIVE_LIKE_M6408", state(30, 5, 5, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 4),
                        AgentState.patrol(PATROL_1, new Position(12), 4),
                        AgentState.patrol(PATROL_2, new Position(24), 4),
                        AgentState.refuel(REFUEL_0, new Position(12))), List.of(
                        spot("A", 1, 1), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1),
                        spot("A", 11, 1), spot("B", 13, 1), spot("C", 16, 1), spot("D", 18, 1)), List.of())),
                scenarios.stream().filter(value -> value.name().equals("large-6-agent-60-step")).findFirst().orElseThrow());
    }

    private static List<Scenario> scenarios() {
        return List.of(
                new Scenario("high-stock", state(30, 7, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 6),
                        AgentState.patrol(PATROL_1, new Position(3), 6),
                        AgentState.patrol(PATROL_2, new Position(6), 6)), List.of(spot("A", 2, 3)), List.of())),
                new Scenario("contention", state(30, 7, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 6),
                        AgentState.patrol(PATROL_1, new Position(2), 6),
                        AgentState.patrol(PATROL_2, new Position(6), 6)), List.of(
                        spot("A", 1, 1), spot("B", 5, 1)), List.of())),
                new Scenario("brand-constrained", state(30, 8, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 6),
                        AgentState.patrol(PATROL_1, new Position(3), 6),
                        AgentState.patrol(PATROL_2, new Position(7), 6)), List.of(
                        spot("A", 1, 2), spot("A", 4, 1), spot("B", 6, 1)), List.of())),
                new Scenario("intermediate-route", state(30, 7, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(0), 6),
                        AgentState.patrol(PATROL_1, new Position(5), 6),
                        AgentState.patrol(PATROL_2, new Position(6), 6)), List.of(
                        spot("A", 1, 1), spot("B", 3, 1), spot("C", 4, 1)), List.of())),
                new Scenario("global-vs-greedy", state(30, 9, 1, List.of(
                        AgentState.patrol(PATROL_0, new Position(6), 1),
                        AgentState.patrol(PATROL_1, new Position(0), 6),
                        AgentState.patrol(PATROL_2, new Position(8), 0)), List.of(
                        spot("A", 5, 1), spot("B", 7, 1)), List.of())),
                largeContentionScenario(),
                liveLikeM6861Scenario(),
                twoPatrolSupportScenario(),
                threePatrolPermutationScenario(),
                threePatrolSupportScenario(),
                legalButBadThreeServiceScenario(),
                dynamicRendezvousScenario(),
                largeSixAgentScenario());
    }

    private static Scenario twoPatrolSupportScenario() {
        return new Scenario("two-patrol-refuel-tour", state(24, 10, 1, List.of(
                AgentState.patrol(PATROL_0, new Position(1), 0),
                AgentState.patrol(PATROL_1, new Position(8), 0),
                AgentState.refuel(REFUEL_0, new Position(4))), List.of(
                spot("A", 0, 1), spot("B", 9, 1)), List.of()));
    }

    private static Scenario threePatrolPermutationScenario() {
        return new Scenario("three-patrol-permutation", state(12, 9, 1, List.of(
                AgentState.patrol(PATROL_0, new Position(1), 0),
                AgentState.patrol(PATROL_1, new Position(4), 0),
                AgentState.patrol(PATROL_2, new Position(7), 0),
                AgentState.refuel(REFUEL_0, new Position(0))), List.of(
                spot("A", 2, 1), spot("B", 5, 1), spot("C", 8, 1)), List.of()));
    }

    private static Scenario dynamicRendezvousScenario() {
        return new Scenario("dynamic-rendezvous", state(20, 7, 1, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 2),
                AgentState.refuel(REFUEL_0, new Position(5))), List.of(
                spot("A", 2, 1), spot("B", 6, 1)), List.of()));
    }

    private static Scenario threePatrolSupportScenario() {
        return new Scenario("three-patrol-support", state(10, 9, 1, List.of(
                AgentState.patrol(PATROL_0, new Position(4), 0),
                AgentState.patrol(PATROL_1, new Position(4), 0),
                AgentState.patrol(PATROL_2, new Position(4), 0),
                AgentState.refuel(REFUEL_0, new Position(3))), List.of(
                spot("A", 2, 1), spot("B", 3, 1), spot("C", 6, 1)), List.of()));
    }

    private static Scenario legalButBadThreeServiceScenario() {
        return new Scenario("legal-but-bad-three-service", state(4, 9, 1, List.of(
                AgentState.patrol(PATROL_0, new Position(4), 6),
                AgentState.patrol(PATROL_1, new Position(4), 6),
                AgentState.patrol(PATROL_2, new Position(4), 6),
                AgentState.refuel(REFUEL_0, new Position(3))), List.of(
                spot("A", 2, 1), spot("B", 5, 1), spot("C", 6, 1)), List.of()));
    }

    private static Scenario largeSixAgentScenario() {
        return new Scenario("large-6-agent-60-step", state(60, 12, 12, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 0),
                AgentState.patrol(PATROL_1, new Position(23), 2),
                AgentState.patrol(PATROL_2, new Position(48), 1),
                AgentState.patrol(new AgentId(3), new Position(95), 0),
                AgentState.patrol(new AgentId(4), new Position(143), 2),
                AgentState.refuel(REFUEL_0, new Position(72))), List.of(
                spot("A", 2, 2), spot("B", 14, 1), spot("C", 27, 1), spot("D", 39, 1),
                spot("A", 52, 1), spot("B", 65, 2), spot("C", 78, 1), spot("D", 91, 1),
                spot("A", 104, 1), spot("B", 117, 1), spot("C", 130, 1), spot("D", 141, 1)), List.of()));
    }

    private static Scenario largeContentionScenario() {
        List<ObservedOtherGroup> others = List.of(new ObservedOtherGroup(41, List.of(
                new ObservedOtherAgent(new Position(2), 0, 0),
                new ObservedOtherAgent(new Position(17), 0, 0))));
        return new Scenario("5x5-raw-kind-zero", state(30, 5, 5, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 8),
                AgentState.patrol(PATROL_1, new Position(12), 8),
                AgentState.patrol(PATROL_2, new Position(24), 8)), List.of(
                spot("A", 1, 1), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1),
                spot("A", 11, 1), spot("B", 13, 1), spot("C", 16, 1), spot("D", 18, 1)), others));
    }

    private static Scenario liveLikeM6861Scenario() {
        List<ObservedOtherGroup> others = List.of(new ObservedOtherGroup(51, List.of(
                new ObservedOtherAgent(new Position(2), 0, 0),
                new ObservedOtherAgent(new Position(17), 0, 0))));
        return new Scenario("live-like-m6861", state(30, 5, 5, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 4),
                AgentState.patrol(PATROL_1, new Position(12), 4),
                AgentState.patrol(PATROL_2, new Position(24), 4),
                AgentState.refuel(REFUEL_0, new Position(12))), List.of(
                spot("A", 1, 2), spot("B", 3, 1), spot("C", 6, 1), spot("D", 8, 1),
                spot("A", 11, 1), spot("B", 13, 2), spot("C", 16, 1), spot("D", 18, 1)), others));
    }

    private static DayState state(int stepBudget, int width, int height, List<AgentState> agents,
            List<UdonSpot> spots, List<ObservedOtherGroup> others) {
        Terrain[] terrain = new Terrain[width * height];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(new HexMap(width, height, terrain),
                new DayStepBudgets(new int[] {stepBudget}), List.of(), new FuelCapacity(10), spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock, others);
    }

    private static UdonSpot spot(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }

    private static <T> T quietly(Supplier<T> action) {
        PrintStream original = System.out;
        try (PrintStream suppressed = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(suppressed);
            return action.get();
        } finally {
            System.setOut(original);
        }
    }

    private record Sweep(String name, JointTeamBeamConfig config) {
    }
    private record OracleProfile(String name, int beamWidth, int expanded, int children, int targets) {
    }
    private record Observation(int semiCollections, int coupledOwnCollections, int baselineOpponentCollections,
            int coupledOpponentCollections, int hybridMargin, int expanded, int generatedChildren, int unique,
            int duplicates, int dominance, int terminalStates, int terminalPlans, int maxDepth,
            int catalogPathfinding, int searchPathfinding, long searchMillis, long terminalMillis,
            long millis, String signature, String bestSecuredByDepth) {
    }
    private record Scenario(String name, DayState state) {
        private int collections(TeamPlan plan) {
            return new vn.ptit.procon.engine.DaySimulator().simulate(state, plan) instanceof
                    vn.ptit.procon.engine.ValidDaySimulationResult valid
                    ? valid.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum() : -1;
        }
    }
    private record StageBRecallTerminalView(int stageARank, int ownSemiCollections,
            int coupledOwnCollections, String signature) { }
}
