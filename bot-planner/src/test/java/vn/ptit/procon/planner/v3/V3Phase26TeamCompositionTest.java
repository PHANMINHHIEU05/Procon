package vn.ptit.procon.planner.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;

/**
 * Phase 2.6: the bounded team-composition search, measured against the FROZEN Phase 2.5 budgets.
 *
 * <p>Every expensive measurement is taken once and memoised. The two representation-oracle runs and the two
 * fuel-free ceilings each cost seconds on CURRENT LARGE, and the mandate asks for all four, so they are
 * static and shared rather than rebuilt per test.
 *
 * <p>Several assertions below record a NEGATIVE result on purpose — the V2 witness routes for P0 and P2 are
 * not enumerated by the bounded portfolio, so the exact V2 tuple is not recalled. That is the mandated
 * evidence, not a failure of the gate: the gate is raw own >= 14, and it is met by a different legal team.
 */
class V3Phase26TeamCompositionTest {

    private static final String CURRENT_LARGE = "large-6-agent-60-step";

    private static Map<String, V3Phase26FixtureReport> table;
    private static V3WitnessRouteDecomposition decomposition;
    private static StrategicTeamComposition.Outcome outcome;
    private static V3RepresentationResult oracleBefore;
    private static V3RepresentationResult oracleAfter;
    private static V3SupportCapabilityCeiling fuelFreeBefore;
    private static V3SupportCapabilityCeiling fuelFreeAfter;

    private static AgentId patrol(int value) { return new AgentId(value); }

    private static StrategicSearchConfig frozen() {
        return V3Phase26Analysis.frozenConfig(CURRENT_LARGE);
    }

    private static synchronized Map<String, V3Phase26FixtureReport> table() {
        if (table == null) table = new V3Phase26Analysis().table();
        return table;
    }

    private static V3Phase26FixtureReport report(String fixture) {
        V3Phase26FixtureReport found = table().get(fixture);
        assertNotNull(found, "Fixture " + fixture + " must be in the mandated table");
        return found;
    }

    private static synchronized V3WitnessRouteDecomposition decomposition() {
        if (decomposition == null) {
            DayState state = V3Phase24Fixtures.currentLarge();
            StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder()
                    .build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);
            V2BaselineWitness witness = new V2BaselineWitnessCapture().capture(state);
            decomposition = V3WitnessRouteDecomposition.of(witness,
                    new V2PlanToStrategicWitness().extract(state, witness, graph));
        }
        return decomposition;
    }

    private static synchronized StrategicTeamComposition.Outcome outcome() {
        if (outcome == null) {
            DayState state = V3Phase24Fixtures.currentLarge();
            outcome = new StrategicTeamComposition().run(state, frozen().withCompositionSearch(true),
                    List.of(), 0, 0, StrategicSearchObserver.NONE, V3SupportRootUniverse.of(state));
        }
        return outcome;
    }

    /** The portfolios built under the root the V2 witness actually used — the PART 2/3/36 evidence base. */
    private static Map<AgentId, StrategicRoutePortfolio.Portfolio> witnessPortfolios() {
        return outcome().portfoliosByRoot().getOrDefault(decomposition().supportRootSignature(), Map.of());
    }

    private static V3PatrolRoutePortfolioRecall recall() {
        return V3PatrolRoutePortfolioRecall.of(decomposition(), witnessPortfolios(), frozen().maxRouteDepth());
    }

    private static V3PatrolRouteDiversityAudit rootDiversity() {
        return V3PatrolRouteDiversityAudit.of(decomposition().supportRootSignature(), witnessPortfolios());
    }

    private static V3TeamCombinationRecall teamRecall() {
        return V3TeamCombinationRecall.of(decomposition(), outcome(), frozen());
    }

    private static synchronized V3RepresentationResult oracleBefore() {
        if (oracleBefore == null) oracleBefore = oracle(V3RepresentationConfig.defaults());
        return oracleBefore;
    }

    private static synchronized V3RepresentationResult oracleAfter() {
        if (oracleAfter == null) {
            oracleAfter = oracle(V3RepresentationConfig.defaults().withCompositionSearch(true));
        }
        return oracleAfter;
    }

    /** One oracle run under the SAME fixed root the witness used, so only the enumeration differs. */
    private static V3RepresentationResult oracle(V3RepresentationConfig config) {
        DayState state = V3Phase24Fixtures.currentLarge();
        return new V3RepresentationOracle().solve(state, config, V3EdgeRetentionPolicy.DIVERSE_GRAPH, List.of(),
                V3SupportRootUniverse.of(state).bySignature(decomposition().supportRootSignature()));
    }

    private static synchronized V3SupportCapabilityCeiling fuelFreeBefore() {
        if (fuelFreeBefore == null) {
            fuelFreeBefore = V3SupportCapabilityCeiling.measure(V3Phase24Fixtures.currentLarge(), frozen(),
                    V3RepresentationConfig.defaults());
        }
        return fuelFreeBefore;
    }

    private static synchronized V3SupportCapabilityCeiling fuelFreeAfter() {
        if (fuelFreeAfter == null) {
            fuelFreeAfter = V3SupportCapabilityCeiling.measure(V3Phase24Fixtures.currentLarge(),
                    frozen().withCompositionSearch(true),
                    V3RepresentationConfig.defaults().withCompositionSearch(true));
        }
        return fuelFreeAfter;
    }

    private static List<AgentState> patrols(DayState state) {
        return state.agents().stream().filter(agent -> agent.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(agent -> agent.id().value())).toList();
    }

    /** Settles one explicit partial assignment under the witness root, through the search's own chronology. */
    private static StrategicTeamComposition.ComposedTeam partial(List<StrategicRouteCandidate> assigned) {
        DayState state = V3Phase24Fixtures.currentLarge();
        V3SupportRootContext root = V3SupportRootUniverse.of(state)
                .bySignature(decomposition().supportRootSignature());
        return StrategicTeamComposition.composePartial(state, patrols(state), new StrategicChronologyReplay(),
                new StrategicTrajectoryCache(state), frozen().withCompositionSearch(true), root,
                SupportAwareTrajectoryScheduler.of(state, root.trajectory()), witnessPortfolios(), assigned);
    }

    /** PART 1: the witness routes are READ OUT of the validated V2 TeamPlan, never written down here. */
    @Test
    void CURRENT_LARGE_V2_PATROL_ROUTE_EXTRACTION() {
        V3WitnessRouteDecomposition value = decomposition();
        assertEquals(5, value.routes().size(), "one route per PATROL, including the idle one");
        assertEquals(14, value.ownSemiCollections());
        assertEquals(56, value.hybridMarginScore4());
        assertEquals(14, value.totalStrategicTransitions());
        assertTrue(value.validatorAccepted(), "the decomposed plan is the one PlanValidator accepted");
        assertTrue(value.simulatorValid(), "and the one DaySimulator ran");
        assertEquals("9:2@48/4,0@0/12,3@95/40", value.supportRootSignature());
        Map<Integer, Integer> collections = new LinkedHashMap<>();
        value.routes().forEach(route -> collections.put(route.patrolId().value(), route.collectionCount()));
        assertEquals(Map.of(0, 4, 1, 0, 2, 6, 3, 3, 4, 1), collections, "the mandated P0..P4 structure");
        assertEquals(14, value.routes().stream()
                .mapToInt(V3WitnessRouteDecomposition.PatrolRoute::collectionCount).sum());
        assertEquals(1, value.routes().stream()
                .filter(V3WitnessRouteDecomposition.PatrolRoute::isEmpty).count(), "exactly one idle PATROL");
        assertEquals(4, value.workingRoutes().size());
        assertEquals(6, value.longestRoute());
        // Extraction, not transcription: every signature is derived from the targets the plan actually has.
        value.routes().forEach(route -> assertEquals(V3WitnessRouteDecomposition.signature(route.targets()),
                route.signature()));
        value.workingRoutes().forEach(route -> assertFalse(route.targets().isEmpty(),
                "a working route must name the targets it claims"));
        assertTrue(value.routes().stream().anyMatch(route -> !route.fuelChronology().isEmpty()),
                "the fuel chronology is carried through from the witness, not recomputed");
        assertEquals("0=2>65>78>91 1=STOP 2=14>2>27>39>52>65 3=104>117>141 4=130", value.teamSignature());
    }

    /** PART 2: which layer lost each witness route, per PATROL, with the loss reason proven not guessed. */
    @Test
    void PATROL_ROUTE_PORTFOLIO_RECALL() {
        V3PatrolRoutePortfolioRecall value = recall();
        assertEquals(5, value.patrols().size());
        assertFalse(value.allWitnessRoutesRetained(),
                "reported as measured: the bounded portfolio does not enumerate P0's and P2's witness route");
        assertEquals(StrategicRoutePortfolio.LossReason.FULL_ROUTE_RETAINED,
                value.patrol(patrol(1)).firstLossReason());
        assertEquals(StrategicRoutePortfolio.LossReason.FULL_ROUTE_RETAINED,
                value.patrol(patrol(3)).firstLossReason());
        assertEquals(StrategicRoutePortfolio.LossReason.FULL_ROUTE_RETAINED,
                value.patrol(patrol(4)).firstLossReason());
        assertEquals(StrategicRoutePortfolio.LossReason.ROUTE_PORTFOLIO_PRUNE,
                value.patrol(patrol(0)).firstLossReason());
        assertEquals(StrategicRoutePortfolio.LossReason.ROUTE_PORTFOLIO_PRUNE,
                value.patrol(patrol(2)).firstLossReason());
        // Neither loss is a LENGTH loss: both routes fit the frozen route depth, so the universe represents
        // them and only the bounded best-first expansion order failed to reach them.
        assertTrue(value.patrol(patrol(0)).witnessRouteLength() <= frozen().maxRouteDepth());
        assertTrue(value.patrol(patrol(2)).witnessRouteLength() <= frozen().maxRouteDepth());
        assertTrue(value.patrol(patrol(0)).witnessPrefixGenerated() >= 1, "a real prefix WAS generated");
        assertTrue(value.patrol(patrol(2)).witnessPrefixGenerated() >= 1);
        assertFalse(value.patrol(patrol(0)).prefixComplete());
        assertFalse(value.patrol(patrol(2)).prefixComplete());
        assertTrue(value.patrol(patrol(4)).prefixComplete());
        int slots = Math.max(1, frozen().maxStrategicChildrenPerState() - 1);
        value.patrols().forEach(entry -> assertTrue(entry.retainedRouteCount() <= slots,
                "no portfolio may exceed the frozen retention slots"));
        assertTrue(value.retainedTotal() > 0 && value.retainedTotal() <= 5 * slots);
        assertTrue(value.generatedTotal() >= value.retainedTotal());
    }

    /** PART 3: the retained routes must be different DECISIONS, judged on eight axes, not one signature. */
    @Test
    void PATROL_ROUTE_DIVERSITY() {
        V3PatrolRouteDiversityAudit value = rootDiversity();
        assertEquals(5, value.patrols().size());
        assertTrue(value.structurallyDiverse(), "no portfolio may be one route plus near-identical variants");
        assertTrue(value.minDistinctDiversityKeys() >= 2);
        value.patrols().forEach(entry -> {
            assertEquals(entry.retained(), entry.distinctDiversityKeys(),
                    "every retained route must contribute its own structural key");
            assertTrue(entry.diverseBeyondSignature());
            assertEquals(entry.retained(), entry.supportDependentRoutes() + entry.supportFreeRoutes());
        });
        V3PatrolRouteDiversityAudit.PatrolDiversity busiest = value.patrols().stream()
                .max(Comparator.comparingInt(V3PatrolRouteDiversityAudit.PatrolDiversity::retained))
                .orElseThrow();
        assertTrue(busiest.distinctFirstTargets() >= 2, "different openings");
        assertTrue(busiest.distinctLastTargets() >= 2, "different futures");
        assertTrue(busiest.distinctTargetSets() >= 2, "different claimed cells");
        assertTrue(busiest.distinctRegionSequences() >= 2, "different region continuations");
        assertTrue(busiest.distinctArrivalProfiles() >= 2, "different arrival chronologies");
        assertTrue(busiest.distinctCollectionCounts() >= 2, "and not all the same collection count");
        assertTrue(busiest.distinctBrandSets() >= 2);
    }

    /** PART 4/5: retention is a conservative Pareto front, never a weighted scalar and never a tie-break. */
    @Test
    void ROUTE_PARETO_RETENTION_SAFE() {
        Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios = witnessPortfolios();
        assertFalse(portfolios.isEmpty());
        int slots = Math.max(1, frozen().maxStrategicChildrenPerState() - 1);
        int tieGroups = 0;
        for (StrategicRoutePortfolio.Portfolio portfolio : portfolios.values()) {
            List<StrategicRouteCandidate> kept = portfolio.routes();
            assertTrue(portfolio.retained() <= slots);
            for (StrategicRouteCandidate left : kept) {
                for (StrategicRouteCandidate right : kept) {
                    assertFalse(StrategicRoutePortfolio.dominates(left, right),
                            "a retained route may never dominate another retained route");
                }
            }
            Map<Integer, Integer> byCollections = new LinkedHashMap<>();
            kept.forEach(route -> byCollections.merge(route.soloPotential(), 1, Integer::sum));
            tieGroups += (int) byCollections.values().stream().filter(count -> count > 1).count();
            for (StrategicRouteCandidate dropped : portfolio.generatedRoutes()) {
                if (portfolio.contains(dropped.signature())) continue;
                boolean dominated = kept.stream()
                        .anyMatch(route -> StrategicRoutePortfolio.dominates(route, dropped));
                assertTrue(dominated || portfolio.retained() >= slots,
                        "a route may only be dropped by domination or by the frozen retention cap");
            }
        }
        assertTrue(tieGroups > 0,
                "routes that merely TIE on collections must survive: that is what conservative means");
    }

    /** PART 36: the mandatory per-route witness evidence — generated, retained and rank, exactly as measured. */
    @Test
    void V2_WITNESS_PATROL_ROUTES_RETAINED() {
        V3PatrolRoutePortfolioRecall value = recall();
        assertEquals("STOP", value.patrol(patrol(1)).witnessRouteSignature());
        assertTrue(value.patrol(patrol(1)).witnessRouteRetained(),
                "the idle route is a reserved choice of every expansion, so it is always available");
        assertTrue(value.patrol(patrol(3)).witnessRouteGenerated());
        assertTrue(value.patrol(patrol(3)).witnessRouteRetained());
        assertEquals(14, value.patrol(patrol(3)).witnessRouteRank());
        assertTrue(value.patrol(patrol(4)).witnessRouteRetained());
        assertEquals(0, value.patrol(patrol(4)).witnessRouteRank(), "P4's single-target route ranks first");
        assertFalse(value.patrol(patrol(0)).witnessRouteGenerated());
        assertFalse(value.patrol(patrol(2)).witnessRouteGenerated());
        assertEquals(-1, value.patrol(patrol(0)).witnessRouteRank());
        assertEquals(-1, value.patrol(patrol(2)).witnessRouteRank());
        assertEquals(3, value.patrols().stream()
                .filter(V3PatrolRoutePortfolioRecall.PatrolRecall::witnessRouteRetained).count());
        // The gate does not depend on this: raw V3 reaches the witness SCORE without the witness ROUTES.
        assertTrue(report(CURRENT_LARGE).afterRawOwn() >= decomposition().ownSemiCollections());
    }

    /** PART 6/21: the whole tuple, looked up by key in an ORDINARY run. Never protected, never injected. */
    @Test
    void TEAM_COMBINATION_RECALL() {
        V3TeamCombinationRecall value = teamRecall();
        assertEquals(decomposition().supportRootSignature(), value.requiredSupportRootSignature());
        assertEquals(decomposition().teamSignature(), value.requiredTeamSignature());
        assertTrue(value.requiredSupportRootRetained(),
                "the witness root IS admitted by the frozen fair admission — the root is not the loss");
        assertFalse(value.requiredRoutesAllRetained());
        assertFalse(value.combinationRepresentable());
        assertFalse(value.combinationGenerated());
        assertEquals(-1, value.combinationRankIfGenerated());
        assertFalse(value.reached());
        assertEquals(V3TeamCombinationRecall.CombinationLoss.OTHER_PROVEN, value.firstCombinationLoss());
        assertTrue(value.lossEvidence().startsWith("route portfolio under this root did not retain"),
                "OTHER_PROVEN must name the exact upstream layer, never shrug: " + value.lossEvidence());
        assertEquals(report(CURRENT_LARGE).counters().completeTeamCandidates(), value.completeTeamCandidates(),
                "the recall run and the table run are the same deterministic search");
        assertEquals(report(CURRENT_LARGE).counters().materializedPlans(), value.materializedPlans());
    }

    /** PART 9: a PARTIAL team already knows its settled chronology, before any materialisation slot is spent. */
    @Test
    void PARTIAL_TEAM_CHRONOLOGY() {
        StrategicRouteCandidate route = witnessPortfolios().get(patrol(2)).routes().stream()
                .filter(candidate -> partial(List.of(candidate)) != null).findFirst().orElseThrow();
        StrategicTeamComposition.ComposedTeam one = partial(List.of(route));
        assertNotNull(one);
        assertFalse(one.team().complete(), "one assigned route out of five PATROLs is a PARTIAL team");
        assertEquals(1, one.team().assignedCount());
        assertEquals(4, one.team().unassigned().size());
        assertFalse(one.team().chronologyFingerprint().isBlank());
        assertEquals(one.team().chronologyFingerprint(), one.state().chronologyFingerprint(),
                "the state the search ranks and the team record read the SAME replay");
        assertTrue(one.team().securedCollections() > 0, "secured collections come from the replay, not a guess");
        assertTrue(one.team().optimisticRemaining() > 0, "four PATROLs still have something to offer");
        assertEquals(one.team().securedCollections() + one.team().optimisticRemaining(),
                one.team().optimisticTotal());
        assertTrue(one.team().identity().startsWith(decomposition().supportRootSignature() + "|"));
        assertFalse(one.team().identity().contains("expanded"), "identity carries no debug history");
        StrategicRouteCandidate second = witnessPortfolios().get(patrol(3)).routes().stream()
                .filter(candidate -> partial(List.of(route, candidate)) != null).findFirst().orElseThrow();
        StrategicTeamComposition.ComposedTeam two = partial(List.of(route, second));
        assertEquals(2, two.team().assignedCount());
        assertNotEquals(one.team().chronologyFingerprint(), two.team().chronologyFingerprint(),
                "adding a route re-settles the whole team chronology");
        assertTrue(two.team().securedCollections() >= one.team().securedCollections());
        assertNotEquals(one.team().identity(), two.team().identity());
    }

    /** PART 9: same-stock competition is decided in the PARTIAL state, not discovered after materialisation. */
    @Test
    void PARTIAL_TEAM_STOCK_CONFLICT() {
        DayState state = V3Phase24Fixtures.currentLarge();
        Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios = witnessPortfolios();
        List<AgentId> ids = List.copyOf(portfolios.keySet());
        StrategicRouteCandidate left = null;
        StrategicRouteCandidate right = null;
        for (int a = 0; a < ids.size() && left == null; a++) {
            for (int b = a + 1; b < ids.size() && left == null; b++) {
                for (StrategicRouteCandidate first : portfolios.get(ids.get(a)).routes()) {
                    for (StrategicRouteCandidate other : portfolios.get(ids.get(b)).routes()) {
                        boolean shares = first.targetSet().stream()
                                .anyMatch(position -> other.targetSet().contains(position)
                                        && state.spotStock().getOrDefault(position, 0) == 1);
                        if (!shares) continue;
                        left = first; right = other; break;
                    }
                    if (left != null) break;
                }
            }
        }
        assertNotNull(left, "CURRENT LARGE must offer two PATROLs competing for one single-unit spot");
        StrategicTeamComposition.ComposedTeam alone = partial(List.of(left));
        StrategicTeamComposition.ComposedTeam other = partial(List.of(right));
        StrategicTeamComposition.ComposedTeam both = partial(List.of(left, right));
        assertNotNull(both, "the competing pair is still a legal partial team");
        assertTrue(both.team().routeOverlap() > 0, "the overlap is measured, not inferred later");
        assertTrue(both.team().securedCollections()
                        <= alone.team().securedCollections() + other.team().securedCollections(),
                "the chronology must never double-count a shared spot");
        assertTrue(both.team().remainingStock().values().stream().allMatch(units -> units >= 0));
    }

    /** PART 8/15: the support root is part of the team tuple, carried in the identity of every state. */
    @Test
    void PARTIAL_TEAM_SUPPORT_ROOT() {
        StrategicTeamComposition.Outcome value = outcome();
        assertTrue(value.portfoliosByRoot().size() >= 2, "more than one structurally distinct root is admitted");
        value.completed().forEach(team -> {
            String root = team.team().supportRoot().signature();
            assertTrue(value.portfoliosByRoot().containsKey(root),
                    "a composed team can only use a root that built portfolios");
            assertTrue(team.team().identity().startsWith(root + "|"),
                    "the root is part of the state identity, so two roots never collapse into one state");
            assertTrue(team.team().diversityKey().startsWith(root));
            assertEquals(5, team.team().assignedCount());
            assertTrue(team.team().unassigned().isEmpty());
        });
        long roots = value.completed().stream()
                .map(team -> team.team().supportRoot().signature()).distinct().count();
        assertTrue(roots >= 2, "composition really happened under more than one root");
    }

    /** PART 10: the BRANCH order is most-constrained-first, and deliberately not PATROL id order. */
    @Test
    void MOST_CONSTRAINED_PATROL_ORDER() {
        StrategicTeamComposition.Outcome value = outcome();
        Map<String, List<AgentId>> orders = new LinkedHashMap<>();
        value.completed().forEach(team -> orders.putIfAbsent(team.team().supportRoot().signature(),
                team.team().assigned().stream().map(StrategicRouteCandidate::patrolId).toList()));
        assertFalse(orders.isEmpty());
        orders.forEach((root, order) -> {
            Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios = value.portfoliosByRoot().get(root);
            assertEquals(Set.copyOf(portfolios.keySet()), Set.copyOf(order), "every PATROL is decided once");
            int previous = -1;
            for (AgentId id : order) {
                int retained = portfolios.get(id).retained();
                assertTrue(retained >= previous,
                        "fewest retained legal routes first: " + root + " ordered " + order);
                previous = retained;
            }
        });
        List<AgentId> witnessOrder = orders.get(decomposition().supportRootSignature());
        assertNotNull(witnessOrder, "the witness root composed teams of its own");
        assertEquals(patrol(1), witnessOrder.getFirst(), "P1 has no legal route at all, so it is decided first");
        assertEquals(patrol(4), witnessOrder.get(1), "then the PATROL with only two");
        assertNotEquals(List.of(patrol(0), patrol(1), patrol(2), patrol(3), patrol(4)), witnessOrder,
                "blind PATROL id order is exactly what PART 10 forbids");
    }

    /** PART 13: a k-best product frontier under the FROZEN caps — every one of them asserted unchanged. */
    @Test
    void K_BEST_TEAM_PRODUCT_SEARCH() {
        StrategicSearchConfig config = frozen();
        assertEquals(32, config.strategicBeamWidth());
        assertEquals(128, config.maxStrategicExpandedStates());
        assertEquals(32, config.maxStrategicChildrenPerState());
        assertEquals(16, config.maxAllocationCandidates());
        assertEquals(10, config.maxRouteDepth());
        assertEquals(16, config.maxTerminalEvaluations());
        assertFalse(config.compositionSearch(), "the frozen rule itself is untouched; the flag is opt-in");
        StrategicTeamComposition.Counters counters = report(CURRENT_LARGE).counters();
        assertEquals(config.maxStrategicExpandedStates(), counters.partialStatesExpanded(),
                "the whole frozen expansion budget is spent, and not one expansion more");
        assertTrue(counters.completeTeamCandidates() > counters.partialStatesExpanded(),
                "expansions buy COMPLETE teams now, instead of re-deriving one hop per level");
        assertEquals(counters.partialStatesGenerated(), counters.partialStatesUnique(),
                "the frontier is not spent on duplicate states");
        assertTrue(counters.materializedPlans() <= config.maxTerminalEvaluations());
        assertTrue(counters.coupledEvaluations() <= config.maxTerminalEvaluations());
    }

    /** PART 14: the frontier holds structurally different TEAM decisions, not variants of one team. */
    @Test
    void TEAM_COMPOSITION_DIVERSITY() {
        V3TeamCompositionDiversity value = report(CURRENT_LARGE).teamDiversity();
        assertTrue(value.teams() > 1);
        assertTrue(value.structurallyDiverse());
        assertTrue(value.distinctDiversityKeys() >= 2);
        assertTrue(value.distinctClaimFingerprints() >= 2, "retained teams must claim different cells");
        assertTrue(value.distinctFirstTargetVectors() >= 2);
        assertTrue(value.distinctEndPositionVectors() >= 2);
        assertTrue(value.distinctRegionSequenceVectors() >= 2);
        assertTrue(value.distinctResponsibilityVectors() >= 2, "and split the load differently");
        assertTrue(value.lowConsequenceVariantShare() < 1.0);
        assertEquals(Math.max(0, value.teams() - value.distinctClaimFingerprints()),
                value.lowConsequenceVariants());
    }

    /** PART 16/17: root-local composition, one shared global budget, no root given a bonus. */
    @Test
    void ROOT_LOCAL_COMPOSITION() {
        StrategicTeamComposition.Outcome value = outcome();
        assertTrue(value.portfoliosByRoot().size() >= 2);
        int slots = Math.max(1, frozen().maxStrategicChildrenPerState() - 1);
        value.portfoliosByRoot().forEach((root, portfolios) -> {
            assertEquals(5, portfolios.size(), "every root builds a portfolio for every PATROL");
            portfolios.values().forEach(portfolio -> {
                assertTrue(portfolio.retained() <= slots);
                assertTrue(portfolio.expansions() <= frozen().maxStrategicChildrenPerState(),
                        "the per-PATROL dive spends the frozen children budget, not a new one");
                portfolio.routes().forEach(route ->
                        assertTrue(route.length() <= frozen().maxRouteDepth()));
            });
        });
        assertTrue(value.counters().partialStatesExpanded() <= frozen().maxStrategicExpandedStates(),
                "the global total is the frozen total: the root-local merge adds no slot");
        assertTrue(value.counters().materializedPlans() <= frozen().maxTerminalEvaluations());
    }

    /** PART 17: every structurally distinct admitted root gets a bounded initial opportunity of its own. */
    @Test
    void FAIR_SUPPORT_ROOT_COMPOSITION() {
        StrategicTeamComposition.Outcome value = outcome();
        int roots = value.portfoliosByRoot().size();
        assertTrue(roots >= 2);
        int share = Math.max(1, frozen().maxStrategicExpandedStates() / roots);
        assertTrue(share >= 1, "the fair share is never zero, however many roots are admitted");
        assertTrue(roots * share <= frozen().maxStrategicExpandedStates() + roots,
                "the shares partition ONE budget; the remainder pass never adds to it");
        Set<String> composing = new LinkedHashSet<>();
        value.completed().forEach(team -> composing.add(team.team().supportRoot().signature()));
        assertTrue(composing.size() >= 2, "more than one root turned its share into complete teams");
        assertTrue(value.portfoliosByRoot().keySet().containsAll(composing));
        assertTrue(value.portfoliosByRoot().containsKey(decomposition().supportRootSignature()),
                "including the root the V2 witness used — it is never excluded, only out-ranked");
    }

    /** PART 18: only COMPLETE teams consume a materialisation slot. */
    @Test
    void MATERIALIZATION_LATE() {
        V3MaterializationEfficiency value = report(CURRENT_LARGE).materialization();
        assertTrue(value.lateMaterialization());
        assertEquals(frozen().maxTerminalEvaluations(), value.materializationCap());
        assertTrue(value.materializedPlans() <= value.materializationCap());
        assertTrue(value.materializedPlans() <= value.completeTeamCandidatesSeen());
        assertTrue(value.completeTeamCandidatesSeen() > value.materializedPlans(),
                "thousands of complete teams competed for sixteen slots");
        StrategicTeamComposition.Counters counters = report(CURRENT_LARGE).counters();
        assertTrue(counters.partialStatesGenerated() > counters.materializedPlans(),
                "not one partial tuple was materialised");
        outcome().materialized().forEach(team -> assertTrue(team.team().complete()));
    }

    /** PART 19: the frozen slots buy distinct, legal, useful plans, and the best one arrives early. */
    @Test
    void MATERIALIZATION_EFFICIENCY() {
        V3MaterializationEfficiency value = report(CURRENT_LARGE).materialization();
        assertEquals(1.0, value.validShare(), 1.0e-9, "every materialised plan passed the frozen validator");
        assertEquals(value.materializedPlans(), value.validPlans());
        assertTrue(value.uniquePhysicalPlans() >= 2, "the slots are not spent on near-duplicates");
        assertTrue(value.uniqueOwnScores() >= 1);
        assertEquals(0, value.bestOwnFoundAtMaterializationIndex(),
                "measured: the very FIRST plan the ordering chose to materialise was already the best");
        assertEquals(0, value.bestHybridFoundAtMaterializationIndex());
        assertTrue(value.bestFoundEarly(),
                "PART 19's question: the ordering must reach the best plan in the first half of the budget");
        assertTrue(value.materializationCapReached(), "and the budget is fully spent, not left on the table");
    }

    /**
     * PART 21: reported as MEASURED. The witness tuple is NOT reached, and nothing was done to help it.
     *
     * <p>This is the one hard-desired outcome of Phase 2.6 that is not met, and the test pins it so that the
     * report cannot quietly claim otherwise. What the gate needs — PART 22 — is a raw own of fourteen, and
     * PART 23 explicitly allows a different physical plan to supply it.
     */
    @Test
    void V2_WITNESS_TEAM_COMBINATION_REACHABLE() {
        V3TeamCombinationRecall value = teamRecall();
        assertFalse(value.reached(), "measured: the exact V2 tuple is not composed under the frozen caps");
        assertFalse(value.combinationMaterialized());
        assertEquals(outcome().materialized().size(), value.materializedBeforeRequired());
        String signature = decomposition().teamSignature();
        assertTrue(outcome().completed().stream()
                        .noneMatch(team -> team.team().routeSignatures().equals(signature)),
                "no protection and no injection: the witness tuple is nowhere in the search output");
        assertTrue(outcome().materialized().stream()
                .noneMatch(team -> team.team().routeSignatures().equals(signature)));
        V3Phase26FixtureReport row = report(CURRENT_LARGE);
        assertTrue(row.afterRawOwn() >= decomposition().ownSemiCollections(),
                "and yet the raw search ties the witness score with a team of its own");
        assertNotEquals(decomposition().supportRootSignature(), row.afterSupportRoot(),
                "sameAsV2Witness=false, which PART 23 permits");
    }

    /** PART 24: the same enumeration, driven by the oracle's own four unchanged caps. */
    @Test
    void CURRENT_LARGE_REPRESENTATION_ORACLE_AT_LEAST_14() {
        V3RepresentationConfig before = V3RepresentationConfig.defaults();
        V3RepresentationConfig after = before.withCompositionSearch(true);
        assertFalse(before.compositionSearch());
        assertTrue(after.compositionSearch());
        assertEquals(before.maxPathLength(), after.maxPathLength());
        assertEquals(before.maxPathsPerPatrol(), after.maxPathsPerPatrol());
        assertEquals(before.maxTeamCandidates(), after.maxTeamCandidates());
        assertEquals(before.maxMaterializedPlans(), after.maxMaterializedPlans());
        assertEquals(12, oracleBefore().winner().ownSemiCollections(), "the Phase 2.5 representation ceiling");
        assertEquals(48, oracleBefore().winner().hybridMarginScore4());
        assertTrue(oracleAfter().winner().ownSemiCollections() >= 14,
                "PART 24 desired: 12 -> at least 14, same caps");
        assertTrue(oracleAfter().winner().hybridMarginScore4() >= 56);
        assertTrue(oracleAfter().winner().ownSemiCollections() > oracleBefore().winner().ownSemiCollections());
        assertTrue(oracleAfter().diagnostics().materializedPlans() <= after.maxMaterializedPlans());
    }

    /** PART 22: the CURRENT LARGE hard gate, on RAW V3, with no forcing and no enlarged cap. */
    @Test
    void CURRENT_LARGE_RAW_V3_AT_LEAST_14() {
        V3Phase26FixtureReport row = report(CURRENT_LARGE);
        assertEquals(14, row.v2Own(), "the frozen incumbent");
        assertEquals(56, row.v2Hybrid4());
        assertEquals(10, row.beforeRawOwn(), "the Phase 2.5 raw figure");
        assertEquals(40, row.beforeRawHybrid4());
        assertTrue(row.afterRawOwn() >= 14, "PART 22: a tie at 14 is sufficient");
        assertTrue(row.afterRawHybrid4() >= 56);
        assertTrue(row.rawImproved());
        assertFalse(row.rawRegressed());
        assertTrue(row.afterRawTiesV2() || row.afterRawWinsAgainstV2());
        assertFalse(row.fallbackUsed(), "the raw figure owes nothing to the frozen V2 fallback");
        assertTrue(row.parityMatch());
        assertTrue(row.pathfindingFree());
    }

    /** PART 25/26: fuel removed for the whole day, same architecture on both sides of the comparison. */
    @Test
    void FUEL_FREE_CAPABILITY_IMPROVES() {
        V3SupportCapabilityCeiling before = fuelFreeBefore();
        V3SupportCapabilityCeiling after = fuelFreeAfter();
        assertEquals(12, before.oracleOwn(), "the Phase 2.5 fuel-free oracle");
        assertEquals(48, before.oracleHybrid4());
        assertEquals(10, before.rawOwn(), "the Phase 2.5 fuel-free raw search");
        assertEquals(40, before.rawHybrid4());
        assertTrue(after.oracleOwn() > before.oracleOwn(), "PART 25: if still 12/10 the ceiling was untouched");
        assertTrue(after.rawOwn() > before.rawOwn());
        assertTrue(after.oracleOwn() >= 14);
        assertTrue(after.rawOwn() >= 14);
        assertTrue(after.rawHybrid4() >= 56);
        assertEquals(before.materializedPlanCap(), after.materializedPlanCap(), "same cap on both sides");
        assertTrue(after.statesExpanded() <= frozen().maxStrategicExpandedStates());
        assertTrue(after.terminals() <= frozen().maxTerminalEvaluations());
    }

    /** PART 27: the smallest fixture must not pay for the redesign. */
    @Test
    @DisplayName("5X5_NON_REGRESSION")
    void _5X5_NON_REGRESSION() {
        V3Phase26FixtureReport row = report("5x5-raw-kind-zero");
        assertEquals(7, row.v2Own());
        assertEquals(8, row.afterRawOwn());
        assertEquals(32, row.afterRawHybrid4());
        assertEquals(row.beforeRawOwn(), row.afterRawOwn(), "identical raw quality, different organisation");
        assertTrue(row.afterRawWinsAgainstV2());
        assertFalse(row.fallbackUsed());
        assertTrue(row.parityMatch());
        assertTrue(row.pathfindingFree());
    }

    /** PART 28: the live-like replay fixture, whose trajectory-primary witness must remain. */
    @Test
    void LIVE_LIKE_NON_REGRESSION() {
        V3Phase26FixtureReport row = report("live-like-m6861");
        assertEquals(8, row.v2Own());
        assertEquals(9, row.afterRawOwn());
        assertEquals(37, row.afterRawHybrid4());
        assertEquals(row.beforeRawOwn(), row.afterRawOwn());
        assertTrue(row.afterRawWinsAgainstV2());
        assertNotEquals("NO_REFUEL", row.afterSupportRoot(),
                "the trajectory-primary witness remains: the winner still uses a mobile support root");
        assertFalse(row.fallbackUsed());
        assertTrue(row.parityMatch());
        assertTrue(row.pathfindingFree());
    }

    /** PART 29: the densest fixture, where the redesign has the most room to go wrong. */
    @Test
    void LARGE_DENSE_NON_REGRESSION() {
        V3Phase26FixtureReport row = report("LARGE_DENSE");
        assertEquals(13, row.v2Own());
        assertEquals(15, row.afterRawOwn());
        assertEquals(60, row.afterRawHybrid4());
        assertEquals(row.beforeRawOwn(), row.afterRawOwn());
        assertTrue(row.afterRawWinsAgainstV2());
        assertFalse(row.fallbackUsed());
        assertTrue(row.parityMatch());
        assertTrue(row.pathfindingFree());
    }

    /** PART 30: the three medium fixtures, at their mandated floors, untuned. */
    @Test
    void MEDIUM_NON_REGRESSION() {
        assertTrue(report("MEDIUM_REGION_RELOCATION").afterRawOwn() >= 5);
        assertTrue(report("MEDIUM_SHARED_STOCK").afterRawOwn() >= 6);
        assertTrue(report("MEDIUM_SUPPORT_CHAIN").afterRawOwn() >= 5);
        List.of("MEDIUM_REGION_RELOCATION", "MEDIUM_SHARED_STOCK", "MEDIUM_SUPPORT_CHAIN").forEach(fixture -> {
            V3Phase26FixtureReport row = report(fixture);
            assertEquals(row.beforeRawOwn(), row.afterRawOwn(), fixture + " must not move at all");
            assertFalse(row.rawRegressed());
            assertTrue(row.parityMatch());
            assertTrue(row.pathfindingFree());
        });
    }

    /** PART 31: the distributed fixture, where a tie is accepted. */
    @Test
    void LARGE_DISTRIBUTED_NON_REGRESSION() {
        V3Phase26FixtureReport row = report("LARGE_DISTRIBUTED");
        assertEquals(8, row.v2Own());
        assertTrue(row.afterRawOwn() >= 8, "a tie is accepted here");
        assertEquals(32, row.afterRawHybrid4());
        assertTrue(row.afterRawTiesV2());
        assertFalse(row.rawRegressed());
        assertTrue(row.parityMatch());
        assertTrue(row.pathfindingFree());
    }

    /** PART 32/33: the fallback still protects every fixture, and never supplies the CURRENT LARGE verdict. */
    @Test
    void FALLBACK_NON_REGRESSION() {
        table().forEach((fixture, row) -> {
            assertTrue(row.selectedOwn() >= row.v2Own(),
                    fixture + " must never be selected below the frozen incumbent");
            assertTrue(row.selectedOwn() >= row.afterRawOwn(), fixture + " selection is never worse than raw");
            assertTrue(row.selectedHybrid4() >= row.v2Hybrid4());
        });
        V3Phase26FixtureReport row = report(CURRENT_LARGE);
        assertFalse(row.fallbackUsed(), "PART 33: the CURRENT LARGE verdict is measured on RAW V3");
        assertEquals(row.afterRawOwn(), row.selectedOwn(),
                "the selected figure IS the raw figure here, so nothing is borrowed from V2");
        V3Phase26Analysis.Scorecard scorecard = V3Phase26Analysis.scorecard(table());
        assertEquals(8, scorecard.fixtures());
        assertTrue(scorecard.noRegression(), "PART 32/38: the redesign must not cost a single fixture");
        assertEquals(0, scorecard.regressedFixtures());
        assertEquals(8, scorecard.afterRawAtLeastV2(), "raw V3 is at least the incumbent on every fixture");
        assertTrue(scorecard.improvedFixtures() >= 1);
        assertTrue(scorecard.afterRawLosses() < scorecard.beforeRawLosses(),
                "and the one fixture raw V3 used to lose is no longer lost");
    }

    /** PART 37: hard zero. A single search-time Dijkstra would invalidate every timing figure. */
    @Test
    void STRATEGIC_SEARCH_PATHFINDING_ZERO() {
        table().forEach((fixture, row) -> {
            assertEquals(0, row.strategicSearchPathfindingExecutions(), fixture + " must add no pathfinding");
            assertTrue(row.pathfindingFree());
        });
        assertEquals(0, outcome().search().diagnostics().strategicSearchPathfindingExecutions(),
                "the composition search itself consumes the cached catalog and adds nothing");
    }

    /** The production planner is frozen: V3 may consume its exported roots, and may change nothing. */
    @Test
    void PRODUCTION_INVARIANCE() {
        DayState state = V3Phase24Fixtures.currentLarge();
        TeamPlan before = new JointTeamBeamR3Planner().plan(state);
        new StrategicTeamComposition().run(state, frozen().withCompositionSearch(true), List.of(), 0, 0,
                StrategicSearchObserver.NONE, V3SupportRootUniverse.of(state));
        TeamPlan after = new JointTeamBeamR3Planner().plan(state);
        assertEquals(before.actionsByAgent(), after.actionsByAgent(),
                "production planning must be bit-identical before and after the composition search ran");
        assertFalse(frozen().compositionSearch(), "and the frozen config never turns the redesign on");
        assertFalse(V3RepresentationConfig.defaults().compositionSearch());
        assertFalse(new StrategicSearchConfig(32, 128, 32, 16, 10, 16).compositionSearch(),
                "the ten-argument convenience constructor also defaults to the historical enumeration");
    }

    /** PART 38: same winner, same root, same signature, same counters, on a second identical run. */
    @Test
    void DETERMINISM() {
        DayState state = V3Phase24Fixtures.currentLarge();
        StrategicSearchConfig config = frozen().withCompositionSearch(true);
        StrategicTeamComposition.Outcome first = new StrategicTeamComposition().run(state, config, List.of(),
                0, 0, StrategicSearchObserver.NONE, V3SupportRootUniverse.of(state));
        StrategicTeamComposition.Outcome second = new StrategicTeamComposition().run(state, config, List.of(),
                0, 0, StrategicSearchObserver.NONE, V3SupportRootUniverse.of(state));
        assertEquals(first.search().rawSupportRootSignature(), second.search().rawSupportRootSignature());
        assertEquals(first.search().rawWinner().ownSemiCollections(),
                second.search().rawWinner().ownSemiCollections());
        assertEquals(first.search().rawWinner().hybridMarginScore4(),
                second.search().rawWinner().hybridMarginScore4());
        assertEquals(first.search().winner().physicalSignature(), second.search().winner().physicalSignature());
        assertEquals(first.search().winningNode().state().exactKey(),
                second.search().winningNode().state().exactKey());
        assertEquals(first.counters(), second.counters(), "every mandated counter is deterministic");
        assertEquals(first.completed().size(), second.completed().size());
        assertEquals(signatures(first), signatures(second), "and the whole terminal set is the same set");
        assertEquals(first.portfoliosByRoot().keySet(), second.portfoliosByRoot().keySet());
    }

    private static List<String> signatures(StrategicTeamComposition.Outcome value) {
        List<String> result = new ArrayList<>();
        value.materialized().forEach(team -> result.add(team.team().routeSignatures()));
        return result;
    }

    /** The mandated eight-row before/after table itself: right fixtures, right order, no regression. */
    @Test
    void PHASE26_BEFORE_AFTER_TABLE() {
        Map<String, V3Phase26FixtureReport> value = table();
        assertEquals(List.of("5x5-raw-kind-zero", "live-like-m6861", "MEDIUM_REGION_RELOCATION",
                "MEDIUM_SHARED_STOCK", "MEDIUM_SUPPORT_CHAIN", "LARGE_DISTRIBUTED", "LARGE_DENSE",
                CURRENT_LARGE), List.copyOf(value.keySet()), "the mandated fixtures in the mandated order");
        value.forEach((fixture, row) -> {
            assertEquals(fixture, row.fixture());
            assertEquals(18, row.row().split(" \\| ").length, "the mandated column count");
            assertFalse(row.counterRow().isBlank());
            assertTrue(row.routesRetained() > 0);
            assertTrue(row.routesGenerated() >= row.routesRetained());
            assertTrue(row.counters().partialStatesExpanded() > 0);
            assertFalse(row.afterSupportRoot().isBlank());
            assertEquals(row.perPatrolRoutesGenerated().keySet(), row.perPatrolRoutesRetained().keySet());
        });
        assertTrue(value.values().stream().noneMatch(V3Phase26FixtureReport::rawRegressed));
    }

    /** One un-measured warm-up run, then the repetitions the timing DIAGNOSTIC is sampled over. */
    private static final int LATENCY_WARMUPS = 1;
    private static final int LATENCY_REPETITIONS = 5;

    /** The composition search, re-run on the identical immutable state, as V3Phase26Analysis measures it. */
    private static List<StrategicTeamComposition.Outcome> repeatedComposition(String fixture) {
        DayState state = V3Phase24Fixtures.phase24Table().get(fixture);
        StrategicSearchConfig config = V3Phase26Analysis.frozenConfig(fixture).withCompositionSearch(true);
        V3SupportRootUniverse universe = V3SupportRootUniverse.of(state);
        List<StrategicTeamComposition.Outcome> runs = new ArrayList<>();
        for (int run = 0; run < LATENCY_WARMUPS + LATENCY_REPETITIONS; run++) {
            StrategicTeamComposition.Outcome outcome = new StrategicTeamComposition().run(state, config,
                    List.of(), 0, 0, StrategicSearchObserver.NONE, universe);
            if (run >= LATENCY_WARMUPS) runs.add(outcome);
        }
        return List.copyOf(runs);
    }

    private static List<Long> samples(List<StrategicTeamComposition.Outcome> runs) {
        return runs.stream().map(run -> run.search().diagnostics().searchMillis()).toList();
    }

    /** Phase 2.7.2: min / median / p95 / max of the measured repetitions. Reported, never asserted. */
    private static String timingDiagnostic(String guard, String fixture, List<Long> millis) {
        List<Long> sorted = millis.stream().sorted().toList();
        return String.format(Locale.ROOT, "PERFORMANCE_DIAGNOSTIC guard=%s fixture=%s samples=%d minMillis=%d"
                        + " medianMillis=%d p95Millis=%.1f maxMillis=%d rawMillis=%s"
                        + " (informational only: no assertion is made on absolute milliseconds)",
                guard, fixture, sorted.size(), sorted.getFirst(), medianMillis(sorted), percentile95(sorted),
                sorted.getLast(), millis);
    }

    private static long medianMillis(List<Long> sorted) {
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }

    /** Linear-interpolated p95, so a five-sample p95 is not simply the maximum. */
    private static double percentile95(List<Long> sorted) {
        double index = 0.95 * (sorted.size() - 1);
        int low = (int) Math.floor(index);
        int high = (int) Math.ceil(index);
        return sorted.get(low) + (index - low) * (sorted.get(high) - sorted.get(low));
    }

    /** The strategic identity of a composed winner: support root plus every PATROL route. */
    private static String strategicSignature(StrategicSearchResult search) {
        StrategicSearchNode node = search.winningNode();
        String routes = node.state().patrols().stream()
                .sorted(Comparator.comparingInt(patrol -> patrol.patrolId().value()))
                .map(patrol -> patrol.patrolId().value() + ":" + (patrol.route().isEmpty() ? "STOP"
                        : patrol.route().stream().map(position -> String.valueOf(position.value()))
                                .collect(Collectors.joining(">"))))
                .collect(Collectors.joining("/"));
        return node.supportRoot().signature() + "|" + routes;
    }

    /** The longest strategic route the winner uses: the observed side of the frozen route-depth cap. */
    private static int longestWinningRoute(StrategicSearchResult search) {
        return search.winningNode().state().patrols().stream()
                .mapToInt(patrol -> patrol.route().size()).max().orElse(0);
    }

    /**
     * The frozen Phase 2.3 caps restated BY VALUE, so no later change can loosen one silently.
     *
     * <p>Route depth, the per-PATROL route portfolio (whose expansion and retention slots are derived from
     * the children cap) and the allocation-candidate cap are all pinned here, which is how Phase 2.7.2
     * proves the redesigned gate did not buy determinism by widening a budget.
     */
    private static StrategicSearchConfig frozenBudgetContract(String fixture) {
        if (fixture.contains("live-like")) return new StrategicSearchConfig(64, 1024, 64, 16, 12, 128);
        if (fixture.contains("large")) return new StrategicSearchConfig(32, 128, 32, 16, 10, 16);
        return new StrategicSearchConfig(32, 512, 32, 16, 10, 64);
    }

    private static void assertFrozenBudget(String fixture, StrategicSearchConfig budget) {
        StrategicSearchConfig contract = frozenBudgetContract(fixture);
        assertEquals(contract.strategicBeamWidth(), budget.strategicBeamWidth(), fixture + " beam width");
        assertEquals(contract.maxStrategicExpandedStates(), budget.maxStrategicExpandedStates(),
                fixture + " expanded-state cap");
        assertEquals(contract.maxStrategicChildrenPerState(), budget.maxStrategicChildrenPerState(),
                fixture + " children-per-state cap, which is also the route-portfolio expansion cap");
        assertEquals(contract.maxAllocationCandidates(), budget.maxAllocationCandidates(),
                fixture + " allocation-candidate cap");
        assertEquals(contract.maxRouteDepth(), budget.maxRouteDepth(), fixture + " max route depth");
        assertEquals(contract.maxTerminalEvaluations(), budget.maxTerminalEvaluations(),
                fixture + " terminal-evaluation cap");
        assertEquals(0L, budget.maxPlanningMillis(),
                fixture + " offline profile carries no deadline, so its counters are fully deterministic");
    }

    /**
     * PART 34, redesigned by Phase 2.7.2: the offline Phase 2.6 gate is BOUNDED WORK, not milliseconds.
     *
     * <p>The absolute wall-clock assertion this test used to end with was machine-sensitive and is gone.
     * Phase 2.7.1 had already replaced an across-fixture median of eight un-warmed samples with a warm
     * repeated median, because each of those eight samples was the first execution of its fixture and so
     * carried the JIT and GC state of the test JVM. That was not enough: the same warm median moved by a
     * uniform 1.8x when the CPU energy-performance preference flipped from balance_performance to power, and
     * the identical source both FAILED {@code mvn -o clean test} and PASSED {@code mvn -o clean verify} six
     * minutes later. A number that answers differently on the same commit cannot be a release gate.
     *
     * <p>What remains is what a slow, busy or throttled machine cannot move: the frozen caps asserted by
     * literal value, the bounded composition work every fixture performs inside them, the per-PATROL route
     * portfolio inside its retention slots, a pathfinding-free strategic search, no deadline on a profile
     * that has none, and unchanged result quality. Latency is warmed, repeated and reported only.
     */
    @Test
    void PHASE26_SEARCH_LATENCY() {
        Map<String, V3Phase26FixtureReport> value = table();
        assertEquals(8, value.size());
        value.forEach((fixture, row) -> {
            StrategicSearchConfig budget = V3Phase26Analysis.frozenConfig(fixture);
            assertFrozenBudget(fixture, budget);
            assertBoundedWork(fixture, budget, row);
        });
        assertRepeatedCompositionIsInvariant();
    }

    /** Every deterministic work bound Phase 2.7.2 mandates for ONE mandatory Phase 2.6 fixture. */
    private static void assertBoundedWork(String fixture, StrategicSearchConfig budget,
            V3Phase26FixtureReport row) {
        StrategicTeamComposition.Counters work = row.counters();
        int retainedSlots = Math.max(1, budget.maxStrategicChildrenPerState() - 1);
        assertTrue(work.partialStatesExpanded() <= budget.maxStrategicExpandedStates(),
                fixture + " expanded " + work.partialStatesExpanded() + " partial team states > "
                        + budget.maxStrategicExpandedStates());
        assertTrue(work.materializedPlans() <= budget.maxTerminalEvaluations(),
                fixture + " materialised " + work.materializedPlans() + " > "
                        + budget.maxTerminalEvaluations());
        assertTrue(work.coupledEvaluations() <= budget.maxTerminalEvaluations(),
                fixture + " coupled " + work.coupledEvaluations() + " > " + budget.maxTerminalEvaluations());
        assertTrue(work.validPlans() <= work.materializedPlans(),
                fixture + " cannot validate more plans than it materialised");
        assertTrue(work.routesRetained() <= work.routesGenerated(),
                fixture + " retained more routes than it generated");
        row.perPatrolRoutesRetained().forEach((patrol, retained) ->
                assertTrue(retained <= retainedSlots, fixture + " patrol " + patrol.value() + " retained "
                        + retained + " routes, the portfolio keeps at most " + retainedSlots));
        assertEquals(0, row.strategicSearchPathfindingExecutions(), fixture + " strategic pathfinding");
        assertFalse(row.rawRegressed(), fixture + " composition must not regress the Phase 2.5 raw result");
        System.out.printf(Locale.ROOT, "PERFORMANCE_DIAGNOSTIC guard=PHASE26_BOUNDED_WORK fixture=%s"
                        + " partialStates=%d/%d materialized=%d/%d coupled=%d/%d valid=%d routesGenerated=%d"
                        + " routesRetained=%d retainedSlots=%d portfolioExpansions=%d routeDepthCap=%d"
                        + " allocationCap=%d pathfinding=%d beforeOwn=%d afterOwn=%d afterHybrid4=%d root=%s"
                        + " beforeMillis=%d afterMillis=%d%n",
                fixture, work.partialStatesExpanded(), budget.maxStrategicExpandedStates(),
                work.materializedPlans(), budget.maxTerminalEvaluations(), work.coupledEvaluations(),
                budget.maxTerminalEvaluations(), work.validPlans(), work.routesGenerated(),
                work.routesRetained(), retainedSlots, work.portfolioExpansions(), budget.maxRouteDepth(),
                budget.maxAllocationCandidates(), row.strategicSearchPathfindingExecutions(),
                row.beforeRawOwn(), row.afterRawOwn(), row.afterRawHybrid4(), row.afterSupportRoot(),
                row.beforeSearchMillis(), row.afterSearchMillis());
    }

    /**
     * Phase 2.7.2 item 8: repetitions of the primary hard case agree on everything except wall time.
     *
     * <p>Own, hybrid4, the physical signature, the support root, the strategic signature and every work
     * counter must be identical across the measured repetitions. Wall time is explicitly excluded from the
     * comparison and only reported, which leaves the milliseconds as the single environment-dependent
     * quantity this guard emits — and it emits it as a diagnostic, not as an assertion.
     */
    private static void assertRepeatedCompositionIsInvariant() {
        List<StrategicTeamComposition.Outcome> runs = repeatedComposition(CURRENT_LARGE);
        StrategicSearchConfig budget = V3Phase26Analysis.frozenConfig(CURRENT_LARGE);
        StrategicTeamComposition.Outcome first = runs.getFirst();
        for (StrategicTeamComposition.Outcome run : runs) {
            assertEquals(first.search().rawWinner().ownSemiCollections(),
                    run.search().rawWinner().ownSemiCollections());
            assertEquals(first.search().rawWinner().hybridMarginScore4(),
                    run.search().rawWinner().hybridMarginScore4());
            assertEquals(first.search().rawWinner().physicalSignature(),
                    run.search().rawWinner().physicalSignature());
            assertEquals(first.search().rawSupportRootSignature(), run.search().rawSupportRootSignature());
            assertEquals(strategicSignature(first.search()), strategicSignature(run.search()));
            assertEquals(first.counters().partialStatesExpanded(), run.counters().partialStatesExpanded());
            assertEquals(first.counters().materializedPlans(), run.counters().materializedPlans());
            assertEquals(first.counters().coupledEvaluations(), run.counters().coupledEvaluations());
            assertEquals(first.counters().routesGenerated(), run.counters().routesGenerated());
            assertEquals(first.counters().routesRetained(), run.counters().routesRetained());
            assertEquals(first.counters().portfolioExpansions(), run.counters().portfolioExpansions());
            assertTrue(run.counters().partialStatesExpanded() <= budget.maxStrategicExpandedStates());
            assertTrue(run.counters().materializedPlans() <= budget.maxTerminalEvaluations());
            assertTrue(run.counters().coupledEvaluations() <= budget.maxTerminalEvaluations());
            assertTrue(longestWinningRoute(run.search()) <= budget.maxRouteDepth(),
                    "the composed winner uses a strategic route longer than the frozen route depth");
            assertEquals(0, run.search().diagnostics().strategicSearchPathfindingExecutions());
            assertFalse(run.search().diagnostics().deadlineBudgetExceeded());
        }
        assertEquals(1, runs.stream().map(run -> strategicSignature(run.search()))
                        .collect(Collectors.toCollection(LinkedHashSet::new)).size(),
                "the strategic signature must be identical across every repetition");
        System.out.printf(Locale.ROOT, "PERFORMANCE_DIAGNOSTIC guard=PHASE26_INVARIANCE fixture=%s"
                        + " repetitions=%d longestWinningRoute=%d routeDepthCap=%d strategic=%s%n",
                CURRENT_LARGE, runs.size(), longestWinningRoute(first.search()), budget.maxRouteDepth(),
                strategicSignature(first.search()));
        System.out.println(timingDiagnostic("PHASE26_LATENCY", CURRENT_LARGE, samples(runs)));
    }
}
