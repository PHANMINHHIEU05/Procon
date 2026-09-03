package vn.ptit.procon.planner.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.TeamCoordinatorPlanner;

class JointTeamBeamPlannerTest {

    private static final AgentId PATROL_0 = new AgentId(0);
    private static final AgentId PATROL_1 = new AgentId(1);
    private static final AgentId PATROL_2 = new AgentId(2);

    @Test
    void stockTimelineUsesArrivalChronologyInsteadOfExpansionOrder() {
        DayState state = state(6, 4, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 6),
                AgentState.patrol(PATROL_1, new Position(3), 6)), List.of(
                spot("X", 2, 1)));
        JointRouteCatalog catalog = JointRouteCatalog.forState(state);
        JointTeamSearchState root = JointTeamSearchState.root(state, java.util.Optional.empty());

        JointTeamSearchState firstZero = root.extend(state, PATROL_0,
                catalog.routes(new Position(0), new Position(2)).getFirst());
        JointTeamSearchState zeroThenOne = firstZero.extend(state, PATROL_1,
                catalog.routes(new Position(3), new Position(2)).getFirst());
        JointTeamSearchState firstOne = root.extend(state, PATROL_1,
                catalog.routes(new Position(3), new Position(2)).getFirst());
        JointTeamSearchState oneThenZero = firstOne.extend(state, PATROL_0,
                catalog.routes(new Position(0), new Position(2)).getFirst());

        assertEquals(1, zeroThenOne.timeline().successfulCollections());
        assertEquals(0, zeroThenOne.timeline().remainingStock().get(new Position(2)));
        assertEquals(PATROL_1, zeroThenOne.timeline().arrivalsBySpot().get(new Position(2))
                .getFirst().agentId());
        assertEquals(zeroThenOne.exactKey(), oneThenZero.exactKey());
    }

    @Test
    void simultaneousArrivalsUseTheSameAgentIdTieBreakAsTheSimulator() {
        DayState state = state(4, 3, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 6),
                AgentState.patrol(PATROL_1, new Position(2), 6)), List.of(
                spot("X", 1, 1)));
        JointRouteCatalog catalog = JointRouteCatalog.forState(state);
        JointTeamSearchState root = JointTeamSearchState.root(state, java.util.Optional.empty());

        JointTeamSearchState committed = root
                .extend(state, PATROL_1, catalog.routes(new Position(2), new Position(1)).getFirst())
                .extend(state, PATROL_0, catalog.routes(new Position(0), new Position(1)).getFirst());

        assertEquals(PATROL_0, committed.timeline().arrivalsBySpot().get(new Position(1))
                .getFirst().agentId());
        TeamPlan plan = committed.completePlan(state);
        assertEquals(1, collections(state, plan));
        assertEquals(1, ((ValidDaySimulationResult) new DaySimulator().simulate(state, plan))
                .portionsCollectedByAgent().get(PATROL_0));
    }

    @Test
    void crossedSpotsCollectAndRevisitsRemainIneligible() {
        DayState state = state(12, 5, List.of(AgentState.patrol(PATROL_0, new Position(0), 6)), List.of(
                spot("A", 1, 1), spot("B", 3, 1)));
        JointRouteCatalog catalog = JointRouteCatalog.forState(state);
        JointTeamSearchState root = JointTeamSearchState.root(state, java.util.Optional.empty());
        JointRouteCatalog.CatalogRoute route = catalog.routes(new Position(0), new Position(3)).getFirst();

        assertEquals(List.of(
                new JointRouteCatalog.SpotArrival(new Position(1), 2),
                new JointRouteCatalog.SpotArrival(new Position(3), 6)), route.spotArrivals());
        JointTeamSearchState throughA = root.extend(state, PATROL_0, route);
        JointTeamSearchState revisitingA = throughA.extend(state, PATROL_0,
                catalog.routes(new Position(3), new Position(1)).getFirst());

        assertEquals(2, throughA.timeline().successfulCollections());
        assertEquals(2, revisitingA.timeline().successfulCollections());
        assertTrue(revisitingA.timeline().visitedBy(PATROL_0).contains(new Position(1)));
    }

    @Test
    void highStockSpotCanServeThreePatrols() {
        DayState state = state(10, 7, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 6),
                AgentState.patrol(PATROL_1, new Position(3), 6),
                AgentState.patrol(PATROL_2, new Position(6), 6)), List.of(
                spot("X", 2, 3)));
        JointRouteCatalog catalog = JointRouteCatalog.forState(state);
        JointTeamSearchState root = JointTeamSearchState.root(state, java.util.Optional.empty());

        JointTeamSearchState committed = root
                .extend(state, PATROL_0, catalog.routes(new Position(0), new Position(2)).getFirst())
                .extend(state, PATROL_2, catalog.routes(new Position(6), new Position(2)).getFirst())
                .extend(state, PATROL_1, catalog.routes(new Position(3), new Position(2)).getFirst());

        assertEquals(3, committed.timeline().successfulCollections());
        assertEquals(0, committed.timeline().remainingStock().get(new Position(2)));
    }

    @Test
    void jointBeamFindsTheCoordinatedPlanThatTheSequentialPlannerMisses() {
        DayState state = state(10, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(6), 1),
                AgentState.patrol(PATROL_1, new Position(0), 6)), List.of(
                spot("A", 5, 1), spot("B", 7, 1)));

        TeamPlan legacy = new TeamCoordinatorPlanner().plan(state);
        JointTeamBeamResult v2 = new JointTeamBeamPlanner(
                new JointTeamBeamConfig(32, 64, 16, 4)).planWithStats(state);

        assertEquals(1, collections(state, legacy));
        assertEquals(2, v2.evaluation().base().udonTotal());
        assertEquals(2, collections(state, v2.plan()));
        assertTrue(v2.evaluation().base().udonTotal() > collections(state, legacy));
    }

    @Test
    void beamIsBoundedDeterministicAndNeverPathfindsDuringExpansion() {
        DayState state = state(10, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(6), 1),
                AgentState.patrol(PATROL_1, new Position(0), 6)), List.of(
                spot("A", 5, 1), spot("B", 7, 1)));
        JointTeamBeamConfig config = new JointTeamBeamConfig(4, 3, 4, 2);

        JointTeamBeamResult first = new JointTeamBeamPlanner(config).planWithStats(state);
        JointTeamBeamResult second = new JointTeamBeamPlanner(config).planWithStats(state);

        assertTrue(first.stats().expandedStates() <= 3);
        assertTrue(first.stats().frontierPeak() <= 4);
        assertEquals(0, first.stats().searchPathfindingExecutions());
        assertTrue(first.stats().catalogPathfindingExecutions() > 0);
        assertEquals(first.evaluation().base().deterministicSignature(),
                second.evaluation().base().deterministicSignature());
    }

    @Test
    void collectionAuditCapturesHighWaterLifecycleWithoutChangingSearch() {
        DayState state = state(10, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(6), 1),
                AgentState.patrol(PATROL_1, new Position(0), 6)), List.of(
                spot("A", 5, 1), spot("B", 7, 1)));
        JointTeamBeamConfig config = new JointTeamBeamConfig(8, 8, 8, 4, 0,
                V2SearchPolicy.R1_CONTROL, V2CollectionAuditMode.TRACE);

        JointTeamBeamResult result = new JointTeamBeamPlanner(config).planWithStats(state);

        assertEquals(V2CollectionAuditMode.TRACE, result.collectionAudit().mode());
        assertFalse(result.collectionAudit().states().isEmpty());
        assertTrue(result.collectionAudit().maxSecuredCollectionsSeen() >= 1);
        assertTrue(result.collectionAudit().candidatesBeforeTruncation()
                >= result.collectionAudit().candidatesAfterConfiguredTopK());
        assertEquals(0, result.collectionAudit().securedCollectionConservationViolations());
        assertEquals(0, result.stats().searchPathfindingExecutions());
    }

    @Test
    void collectionAuditIsDeterministicAndDisabledByDefault() {
        DayState state = state(10, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(6), 1),
                AgentState.patrol(PATROL_1, new Position(0), 6)), List.of(
                spot("A", 5, 1), spot("B", 7, 1)));
        JointTeamBeamConfig trace = new JointTeamBeamConfig(8, 8, 8, 4, 0,
                V2SearchPolicy.R1_CONTROL, V2CollectionAuditMode.TRACE);

        JointTeamBeamResult first = new JointTeamBeamPlanner(trace).planWithStats(state);
        JointTeamBeamResult second = new JointTeamBeamPlanner(trace).planWithStats(state);
        JointTeamBeamResult ordinary = new JointTeamBeamPlanner().planWithStats(state);

        assertEquals(first.collectionAudit(), second.collectionAudit());
        assertEquals(V2CollectionAuditMode.OFF, ordinary.collectionAudit().mode());
        assertTrue(ordinary.collectionAudit().states().isEmpty());
    }

    @Test
    void securedCollectionsSurviveMaterialization() {
        DayState state = state(10, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 6)), List.of(spot("A", 2, 1)));
        JointRouteCatalog catalog = JointRouteCatalog.forState(state);
        JointTeamSearchState committed = JointTeamSearchState.root(state, java.util.Optional.empty())
                .extend(state, PATROL_0, catalog.routes(new Position(0), new Position(2)).getFirst());

        assertEquals(committed.timeline().successfulCollections(), collections(state, committed.completePlan(state)));
    }

    @Test
    void stopBudgetAuditCountsOnlyObservedStopChainsAndDedupDoesNotLoseCollections() {
        DayState empty = state(8, 4, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 6),
                AgentState.patrol(PATROL_1, new Position(2), 6)), List.of());
        JointTeamBeamConfig config = new JointTeamBeamConfig(8, 8, 8, 4, 0,
                V2SearchPolicy.R1_CONTROL, V2CollectionAuditMode.TRACE);
        JointTeamBeamResult result = new JointTeamBeamPlanner(config).planWithStats(empty);

        assertTrue(result.collectionAudit().stopOnlyExpansionCount() > 0);
        assertEquals(0, result.collectionAudit().higherCollectionStateLostToDedup());
        assertEquals(0, result.stats().searchPathfindingExecutions());
    }

    @Test
    void equivalentWholeTeamBranchesAreDeduplicatedByTheActualBeam() {
        DayState state = state(10, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(6), 1),
                AgentState.patrol(PATROL_1, new Position(0), 6)), List.of(
                spot("A", 5, 1), spot("B", 7, 1)));

        JointTeamBeamResult result = new JointTeamBeamPlanner(
                new JointTeamBeamConfig(32, 64, 16, 4)).planWithStats(state);

        assertTrue(result.stats().duplicateStatesRejected() > 0);
    }

    @Test
    void fixedRefuelRootProducesAValidFuelRestoringPlan() {
        AgentId refuel = new AgentId(9);
        DayState state = state(4, 5, List.of(
                AgentState.patrol(PATROL_0, new Position(2), 0),
                AgentState.refuel(refuel, new Position(2))), List.of(spot("A", 3, 1)));

        JointTeamBeamResult result = new JointTeamBeamPlanner().planWithStats(state);
        DaySimulationResult simulation = new DaySimulator().simulate(state, result.plan());

        assertTrue(simulation instanceof ValidDaySimulationResult);
        assertEquals(1, result.evaluation().base().udonTotal());
        assertFalse(result.plan().actionsFor(refuel).isEmpty());
    }

    @Test
    void partialMetricsKeepTheRemainingStockBoundOptimisticAndComparatorPrefersPotential() {
        DayState state = state(10, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 6),
                AgentState.patrol(PATROL_1, new Position(7), 6)), List.of(
                spot("A", 2, 1), spot("B", 5, 2)));
        JointRouteCatalog catalog = JointRouteCatalog.forState(state);
        JointTeamBeamPlanner planner = new JointTeamBeamPlanner();
        JointTeamSearchState root = JointTeamSearchState.root(state, java.util.Optional.empty());
        JointTeamSearchState committed = root.extend(state, PATROL_0,
                catalog.routes(new Position(0), new Position(2)).getFirst());

        JointPartialStateMetrics rootMetrics = planner.metrics(root, catalog, state.stepBudget());
        JointPartialStateMetrics committedMetrics = planner.metrics(committed, catalog, state.stepBudget());
        assertTrue(rootMetrics.remainingCollectionPotentialUpperBound() >= 2);
        assertTrue(rootMetrics.remainingCollectionPotentialUpperBound() <= 3);
        assertEquals(1, committedMetrics.securedCollections());
        assertTrue(planner.statePreference(catalog, state.stepBudget()).compare(committed, root) < 0);
    }

    @Test
    void exactDominanceRequiresNoWorseResourcesAndDoesNotEraseDifferentFutureState() {
        DayState state = state(6, 4, List.of(AgentState.patrol(PATROL_0, new Position(0), 6)), List.of(
                spot("A", 2, 1)));
        JointTeamSearchState root = JointTeamSearchState.root(state, java.util.Optional.empty());
        JointTeamSearchState delayed = new JointTeamSearchState(state, Map.of(PATROL_0,
                new JointTeamSearchState.PatrolPrefix(PATROL_0, new Position(0), new Position(0),
                        2, 4, false, 0, 0, List.of(new WaitAction(2)))), java.util.Optional.empty());

        assertEquals(root.dominanceKey(), delayed.dominanceKey());
        assertTrue(JointStateDominance.dominates(root, delayed));
        assertFalse(JointStateDominance.dominates(delayed, root));
        JointTeamBeamPlanner.DominanceRegistry registry = new JointTeamBeamPlanner.DominanceRegistry();
        List<JointTeamSearchState> frontier = new java.util.ArrayList<>();
        assertTrue(registry.accept(root, frontier));
        assertFalse(registry.accept(delayed, frontier));
    }

    @Test
    void depthTerminalPortfolioTimingAndShortlistAuditArePopulated() {
        DayState state = state(10, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(6), 1),
                AgentState.patrol(PATROL_1, new Position(0), 6)), List.of(
                spot("A", 5, 1), spot("B", 7, 1)));
        JointTeamBeamResult exhaustive = new JointTeamBeamPlanner(
                new JointTeamBeamConfig(32, 64, 16, 4, 0)).planWithStats(state);
        JointTeamBeamResult shortlist = new JointTeamBeamPlanner(
                new JointTeamBeamConfig(32, 64, 16, 4, 32)).planWithStats(state);

        assertFalse(exhaustive.depthSummaries().isEmpty());
        assertTrue(exhaustive.depthSummaries().stream().anyMatch(value -> value.statesExpanded() > 0));
        assertFalse(exhaustive.terminalPortfolio().isEmpty());
        assertEquals(1, exhaustive.terminalPortfolio().getFirst().rank());
        assertTrue(exhaustive.terminalPortfolio().stream().mapToInt(
                JointTerminalPortfolioEntry::hybridMarginScore4).boxed()
                .reduce((left, right) -> left >= right ? right : Integer.MAX_VALUE).orElse(0)
                != Integer.MAX_VALUE);
        assertTrue(exhaustive.stats().stageATerminalEvaluations()
                >= exhaustive.stats().terminalPlansEvaluated());
        assertEquals(exhaustive.stats().terminalPlansEvaluated(), exhaustive.stats().coupledTerminalEvaluations());
        assertTrue(exhaustive.stats().catalogMillis() + exhaustive.stats().searchMillis()
                + exhaustive.stats().stageBTerminalWallMillis() <= exhaustive.stats().planningMillis());
        assertEquals(exhaustive.stats().stageATerminalAccumulatedMillis()
                + exhaustive.stats().stageBTerminalAccumulatedMillis(),
                exhaustive.stats().terminalEvaluationMillis());
        assertTrue(shortlist.stats().stageBDuplicateCandidatesSkipped() > 0);
        assertEquals(shortlist.stats().stageBRequested(), shortlist.stats().coupledTerminalEvaluations());
        assertEquals(exhaustive.evaluation().base().deterministicSignature(),
                shortlist.evaluation().base().deterministicSignature());
    }

    @Test
    void lowDepthSearchStillGeneratesStopBranchesAndDepthCounters() {
        DayState state = state(4, 3, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 6),
                AgentState.patrol(PATROL_1, new Position(2), 6)), List.of());
        JointTeamBeamResult result = new JointTeamBeamPlanner(
                new JointTeamBeamConfig(4, 1, 2, 1)).planWithStats(state);

        assertEquals(2, result.stats().generatedChildren());
        assertTrue(result.depthSummaries().stream().anyMatch(value -> value.strategicDecisionDepth() == 1
                && value.statesConsidered() == 2));
        assertEquals(0, result.evaluation().base().udonTotal());
    }

    @Test
    void productionDefaultsUseTheFrozenR1ControlPolicyAndBudget() {
        JointTeamBeamConfig defaults = JointTeamBeamConfig.defaults();

        assertEquals(48, defaults.beamWidth());
        assertEquals(64, defaults.maxExpandedStates());
        assertEquals(24, defaults.maxChildrenPerState());
        assertEquals(4, defaults.maxNextTargetsPerAgent());
        assertEquals(0, defaults.fullTerminalEvaluationLimit());
        assertEquals(V2SearchPolicy.R1_CONTROL, defaults.searchPolicy());
    }

    @Test
    void branchAndTerminalAccountingReconcilesWithAdmittedStates() {
        DayState state = state(10, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(6), 1),
                AgentState.patrol(PATROL_1, new Position(0), 6)), List.of(
                spot("A", 5, 1), spot("B", 7, 1)));
        JointTeamBeamResult result = new JointTeamBeamPlanner(
                new JointTeamBeamConfig(32, 64, 16, 4)).planWithStats(state);

        JointTeamBeamStats stats = result.stats();
        assertEquals(stats.generatedChildren(), stats.collectChildrenGenerated() + stats.stopChildrenGenerated());
        assertEquals(stats.terminalStates(), stats.stageATerminalEvaluations());
        assertEquals(stats.terminalStates(), stats.continuingTerminalCandidates()
                + stats.fullyStoppedTerminalCandidates());
        assertEquals(stats.terminalPlansEvaluated(), stats.coupledTerminalEvaluations());
        assertEquals(0, stats.searchPathfindingExecutions());
        assertEquals(stats.maxDepthReached(), result.depthSummaries().stream()
                .mapToInt(JointBeamDepthStats::strategicDecisionDepth).max().orElseThrow());
    }

    @Test
    void policiesAreDeterministicAndTheFullR2PolicyPreservesTheCurrentR2SearchShape() {
        DayState state = state(30, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 6),
                AgentState.patrol(PATROL_1, new Position(3), 6),
                AgentState.patrol(PATROL_2, new Position(7), 6)), List.of(
                spot("A", 1, 2), spot("A", 4, 1), spot("B", 6, 1)));
        JointTeamBeamConfig control = new JointTeamBeamConfig(48, 64, 24, 4, 0,
                V2SearchPolicy.R1_CONTROL);
        JointTeamBeamConfig fullR2 = new JointTeamBeamConfig(48, 64, 24, 4, 0,
                V2SearchPolicy.FULL_R2);

        JointTeamBeamResult controlFirst = new JointTeamBeamPlanner(control).planWithStats(state);
        JointTeamBeamResult controlSecond = new JointTeamBeamPlanner(control).planWithStats(state);
        JointTeamBeamResult fullFirst = new JointTeamBeamPlanner(fullR2).planWithStats(state);
        JointTeamBeamResult fullSecond = new JointTeamBeamPlanner(fullR2).planWithStats(state);

        assertEquals(controlFirst.evaluation().base().deterministicSignature(),
                controlSecond.evaluation().base().deterministicSignature());
        assertEquals(fullFirst.evaluation().base().deterministicSignature(),
                fullSecond.evaluation().base().deterministicSignature());
        assertEquals(fullFirst.stats().generatedChildren(), fullSecond.stats().generatedChildren());
        assertEquals(fullFirst.stats().uniqueStates(), fullSecond.stats().uniqueStates());
        assertEquals(157, controlFirst.stats().generatedChildren());
        assertEquals(166, fullFirst.stats().generatedChildren());
        assertEquals(20, fullFirst.stats().terminalPlansEvaluated());
        assertEquals(78, fullFirst.stats().stageBDuplicateCandidatesSkipped());
    }

    @Test
    void eachSingleFeaturePolicyChangesOnlyItsNamedFlag() {
        assertFalse(V2SearchPolicy.R1_CONTROL.r2PartialOrdering());
        assertFalse(V2SearchPolicy.R1_CONTROL.r2PotentialBound());
        assertFalse(V2SearchPolicy.R1_CONTROL.r2StopTerminalHandling());
        assertFalse(V2SearchPolicy.R1_CONTROL.r2SafeDominance());
        assertFalse(V2SearchPolicy.R1_CONTROL.r2BranchingPolicy());

        assertEquals(new V2SearchPolicy(true, false, false, false, false),
                V2SearchPolicy.r1ControlWithPartialOrdering());
        assertEquals(new V2SearchPolicy(false, true, false, false, false),
                V2SearchPolicy.r1ControlWithPotentialBound());
        assertEquals(new V2SearchPolicy(false, false, true, false, false),
                V2SearchPolicy.r1ControlWithStopTerminalHandling());
        assertEquals(new V2SearchPolicy(false, false, false, true, false),
                V2SearchPolicy.r1ControlWithSafeDominance());
        assertEquals(new V2SearchPolicy(false, false, false, false, true),
                V2SearchPolicy.r1ControlWithBranchingPolicy());
    }

    @Test
    void potentialDiscriminationIsBoundedAndRepeatableAtEveryDepth() {
        DayState state = state(10, 8, List.of(
                AgentState.patrol(PATROL_0, new Position(0), 6),
                AgentState.patrol(PATROL_1, new Position(7), 6)), List.of(
                spot("A", 2, 1), spot("B", 5, 2)));
        JointTeamBeamConfig config = new JointTeamBeamConfig(32, 64, 16, 4, 0,
                V2SearchPolicy.FULL_R2);

        JointTeamBeamResult first = new JointTeamBeamPlanner(config).planWithStats(state);
        JointTeamBeamResult second = new JointTeamBeamPlanner(config).planWithStats(state);

        assertEquals(first.depthSummaries(), second.depthSummaries());
        assertTrue(first.depthSummaries().stream().allMatch(depth ->
                depth.minPotentialCollections() <= depth.maxPotentialCollections()
                        && depth.distinctPotentialValues() <= depth.statesConsidered()));
    }

    private static int collections(DayState state, TeamPlan plan) {
        DaySimulationResult simulation = new DaySimulator().simulate(state, plan);
        return ((ValidDaySimulationResult) simulation).portionsCollectedByAgent().values().stream()
                .mapToInt(Integer::intValue).sum();
    }

    private static DayState state(
            int stepBudget,
            int width,
            List<AgentState> agents,
            List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                new HexMap(width, 1, terrain),
                new DayStepBudgets(new int[] {stepBudget}),
                List.of(),
                new FuelCapacity(10),
                spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock);
    }

    private static UdonSpot spot(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }
}
