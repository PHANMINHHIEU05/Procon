package vn.ptit.procon.planner.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;

class JointTeamBeamR3PlannerTest {
    private static final AgentId P0 = new AgentId(0);
    private static final AgentId P1 = new AgentId(1);
    private static final AgentId P2 = new AgentId(2);
    private static final AgentId R0 = new AgentId(9);

    @Test
    void boundedTourPortfolioCanReplayTwoPatrolServices() {
        DayState state = state(24, 10, List.of(
                AgentState.patrol(P0, new Position(1), 0),
                AgentState.patrol(P1, new Position(8), 0),
                AgentState.refuel(R0, new Position(4))), List.of(
                spot("A", 0, 1), spot("B", 9, 1)));

        JointTeamBeamR3Config auditConfig = JointTeamBeamR3Config.defaults()
                .withRootFamilyAuditMode(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY);
        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(auditConfig).planWithStats(state);
        ValidDaySimulationResult valid = assertInstanceOf(ValidDaySimulationResult.class,
                new DaySimulator().simulate(state, result.plan()));

        List<AgentId> refueledPatrols = valid.events().stream().filter(RefueledEvent.class::isInstance)
                .map(RefueledEvent.class::cast).map(RefueledEvent::patrolId).distinct().toList();
        assertTrue(refueledPatrols.containsAll(List.of(P0, P1)));
        assertEquals(2, valid.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum());
        JointTeamBeamResult v2 = new JointTeamBeamPlanner().planWithStats(state);
        assertTrue(result.beamResult().evaluation().hybrid().ownSemiCollections()
                > v2.evaluation().hybrid().ownSemiCollections());
        assertEquals(2, result.stats().selectedSupportServiceCount());
        assertTrue(result.stats().skeletonsRetained() <= 12);
        assertEquals(0, result.stats().tourSearchPathfindingExecutions());
        assertEquals(0, result.stats().beamSearchPathfindingExecutions());
        R3RootFamilySummary noRefuel = family(result, 0);
        R3RootFamilySummary twoService = family(result, 2);
        assertTrue(noRefuel.available());
        assertTrue(twoService.available());
        assertTrue(twoService.bestFullyEvaluatedOwnSemiCollections()
                > noRefuel.bestFullyEvaluatedOwnSemiCollections());
        assertEquals(2, result.rootFamilyAudit().selectedProvenance().supportServiceCount());
        assertTrue(result.rootFamilyAudit().supportProductivity().isPresent());
    }

    @Test
    void auditModeDoesNotChangeTheProductionSelectedPlan() {
        DayState state = state(24, 10, List.of(
                AgentState.patrol(P0, new Position(1), 0),
                AgentState.patrol(P1, new Position(8), 0),
                AgentState.refuel(R0, new Position(4))), List.of(
                spot("A", 0, 1), spot("B", 9, 1)));

        JointTeamBeamR3Result production = new JointTeamBeamR3Planner().planWithStats(state);
        JointTeamBeamR3Result audit = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withRootFamilyAuditMode(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY)).planWithStats(state);

        assertEquals(production.beamResult().evaluation().base().deterministicSignature(),
                audit.beamResult().evaluation().base().deterministicSignature());
        assertEquals(production.beamResult().evaluation().hybrid().ownSemiCollections(),
                audit.beamResult().evaluation().hybrid().ownSemiCollections());
        assertEquals(production.beamResult().evaluation().hybrid().hybridMarginScore4(),
                audit.beamResult().evaluation().hybrid().hybridMarginScore4());
        assertEquals(production.stats().selectedSupportServiceCount(), audit.stats().selectedSupportServiceCount());
        assertEquals(production.stats().selectedSupportSkeletonSignature(),
                audit.stats().selectedSupportSkeletonSignature());
        assertEquals(production.stats().stageBEvaluated(), audit.stats().stageBEvaluated());
        assertEquals(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY, audit.rootFamilyAudit().mode());
        assertTrue(audit.rootFamilyAudit().liveShadowAudit().isPresent());
    }

    @Test
    void liveShadowAuditIsBoundedAndReportsUnavailableFamiliesAsNa() {
        DayState state = state(24, 10, List.of(
                AgentState.patrol(P0, new Position(1), 0),
                AgentState.patrol(P1, new Position(8), 0),
                AgentState.refuel(R0, new Position(4))), List.of(
                spot("A", 0, 1), spot("B", 9, 1)));

        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withRootFamilyAuditMode(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY)).planWithStats(state);
        R3LiveFamilyAudit shadow = result.rootFamilyAudit().liveShadowAudit().orElseThrow();

        assertTrue(shadow.shadowEvaluationsPerformed() <= 4);
        assertEquals(4, shadow.families().size());
        R3LiveFamilyAuditFamily unavailable = shadow.families().stream()
                .filter(value -> value.serviceCount() == 3).findFirst().orElseThrow();
        assertFalse(unavailable.available());
        assertFalse(unavailable.shadowEvaluated());
        assertEquals(null, unavailable.shadowHybridMarginScore4());
        assertEquals("UNAVAILABLE", unavailable.physicalSignature());
    }

    @Test
    void liveShadowAuditReusesPhysicalEvaluationsAndIsDeterministic() {
        DayState state = state(24, 10, List.of(
                AgentState.patrol(P0, new Position(1), 0),
                AgentState.patrol(P1, new Position(8), 0),
                AgentState.refuel(R0, new Position(4))), List.of(
                spot("A", 0, 1), spot("B", 9, 1)));
        JointTeamBeamR3Config config = JointTeamBeamR3Config.defaults()
                .withRootFamilyAuditMode(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY);

        JointTeamBeamR3Result first = new JointTeamBeamR3Planner(config).planWithStats(state);
        JointTeamBeamR3Result second = new JointTeamBeamR3Planner(config).planWithStats(state);
        R3LiveFamilyAudit firstShadow = first.rootFamilyAudit().liveShadowAudit().orElseThrow();
        R3LiveFamilyAudit secondShadow = second.rootFamilyAudit().liveShadowAudit().orElseThrow();

        assertEquals(firstShadow, secondShadow);
        long distinctPhysicalHeads = firstShadow.families().stream().filter(R3LiveFamilyAuditFamily::available)
                .map(R3LiveFamilyAuditFamily::physicalSignature).distinct().count();
        assertTrue(firstShadow.shadowEvaluationsPerformed() <= distinctPhysicalHeads);
        assertTrue(firstShadow.shadowEvaluationsPerformed() <= 4);
    }

    @Test
    void deadlineCanSkipLiveShadowWithoutChangingTheAlreadySelectedPlan() {
        DayState state = state(60, 60, List.of(
                AgentState.patrol(P0, new Position(0), 0),
                AgentState.patrol(P1, new Position(12), 0),
                AgentState.patrol(P2, new Position(24), 0),
                AgentState.refuel(R0, new Position(30))), List.of(
                spot("A", 2, 1), spot("B", 10, 1), spot("C", 18, 1), spot("D", 26, 1),
                spot("A", 34, 1), spot("B", 42, 1), spot("C", 50, 1), spot("D", 58, 1)));
        JointTeamBeamR3Config off = new JointTeamBeamR3Config(8_000, 750, 64, 1,
                24, 24, 4, 216, 48, 24, 12);
        CountingClock normalClock = new CountingClock(Integer.MAX_VALUE);
        JointTeamBeamR3Result normal = new JointTeamBeamR3Planner(off, normalClock,
                new vn.ptit.procon.planner.RefuelRouteFinder()).planWithStats(state);

        CountingClock deadlineAfterSelection = new CountingClock(Math.max(0, normalClock.calls() - 3));
        JointTeamBeamR3Result shadow = new JointTeamBeamR3Planner(off
                .withRootFamilyAuditMode(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY), deadlineAfterSelection,
                new vn.ptit.procon.planner.RefuelRouteFinder()).planWithStats(state);

        assertEquals(normal.beamResult().evaluation().base().deterministicSignature(),
                shadow.beamResult().evaluation().base().deterministicSignature());
        R3LiveFamilyAudit audit = shadow.rootFamilyAudit().liveShadowAudit().orElseThrow();
        assertTrue(audit.shadowEvaluationsSkippedForDeadline() > 0);
        assertTrue(audit.families().stream().anyMatch(R3LiveFamilyAuditFamily::shadowAuditSkippedForDeadline));
        assertInstanceOf(ValidDaySimulationResult.class, new DaySimulator().simulate(state, shadow.plan()));
    }

    @Test
    void noRefuelFamilyIsAuditedWithoutChangingSearchDeduplication() {
        DayState state = state(20, 7, List.of(
                AgentState.patrol(P0, new Position(0), 2),
                AgentState.refuel(R0, new Position(5))), List.of(
                spot("A", 2, 1), spot("B", 6, 1)));

        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withRootFamilyAuditMode(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY)).planWithStats(state);

        R3RootFamilySummary noRefuel = family(result, 0);
        assertTrue(noRefuel.available());
        assertTrue(noRefuel.rawTerminalCandidates() >= noRefuel.uniquePhysicalTerminals());
        assertEquals(0, result.stats().tourSearchPathfindingExecutions());
        assertEquals(0, result.stats().beamSearchPathfindingExecutions());
    }

    @Test
    void noRefuelRootUsesTheNormalCollectionBeam() {
        DayState state = state(12, 7, List.of(
                AgentState.patrol(P0, new Position(0), 6),
                AgentState.patrol(P1, new Position(6), 6),
                AgentState.refuel(R0, new Position(3))), List.of(
                spot("A", 2, 1), spot("B", 4, 1)));

        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withRootFamilyAuditMode(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY)).planWithStats(state);

        R3NoRefuelSearchAudit audit = result.rootFamilyAudit().noRefuelSearchAudit().orElseThrow();
        assertTrue(audit.expandablePatrolCount() > 0);
        assertTrue(audit.reachableOpportunitiesAtRoot() > 0);
        assertTrue(audit.generatedChildrenAtFirstExpansion() > 0);
        assertTrue(audit.uniquePhysicalTerminals() > 1);
        assertTrue(audit.bestSemiCollections() > 0);
        assertFalse(audit.waitOnlyWasBest());
    }

    @Test
    void supportRetentionPreservesEveryAvailableFamilyWithinTwelveRoots() {
        DayState state = state(16, 9, List.of(
                AgentState.patrol(P0, new Position(0), 3),
                AgentState.patrol(P1, new Position(4), 3),
                AgentState.patrol(P2, new Position(8), 3),
                AgentState.refuel(R0, new Position(4))), List.of(
                spot("A", 1, 1), spot("B", 3, 1), spot("C", 5, 1), spot("D", 7, 1)));

        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withRootFamilyAuditMode(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY)).planWithStats(state);
        R3SupportFamilyPipeline pipeline = result.stats().supportFamilyPipeline();

        assertTrue(pipeline.totalSupportSkeletonsRetained() <= 12);
        assertTrue(pipeline.retained1() + pipeline.retained2() + pipeline.retained3()
                <= pipeline.totalSupportSkeletonsRetained());
        assertEquals(pipeline.totalSupportSkeletonsRetained(), result.stats().skeletonsRetained());
    }

    @Test
    void legalButBadThreeServiceRootDoesNotWinAgainstABetterLowerServiceFamily() {
        DayState state = state(4, 9, List.of(
                AgentState.patrol(P0, new Position(4), 6),
                AgentState.patrol(P1, new Position(4), 6),
                AgentState.patrol(P2, new Position(4), 6),
                AgentState.refuel(R0, new Position(3))), List.of(
                spot("A", 2, 1), spot("B", 5, 1), spot("C", 6, 1)));

        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withRootFamilyAuditMode(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY)).planWithStats(state);

        R3RootFamilySummary threeService = family(result, 3);
        assertTrue(threeService.available(), "The fixture must contain a legal three-service support root");
        assertTrue(result.beamResult().evaluation().hybrid().ownSemiCollections()
                > threeService.bestFullyEvaluatedOwnSemiCollections(),
                "A legal but worse three-service tour must not win the frozen terminal objective");
        assertTrue(result.stats().selectedSupportServiceCount() < 3,
                "Frozen terminal selection must not prefer a legal but worse three-service tour");
    }

    @Test
    void threePatrolSupportFamilyIsAuditedWhenTheMapRequiresAllServices() {
        DayState state = state(10, 9, List.of(
                AgentState.patrol(P0, new Position(4), 0),
                AgentState.patrol(P1, new Position(4), 0),
                AgentState.patrol(P2, new Position(4), 0),
                AgentState.refuel(R0, new Position(3))), List.of(
                spot("A", 0, 1), spot("B", 6, 1), spot("C", 8, 1)));

        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withRootFamilyAuditMode(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY)).planWithStats(state);

        R3RootFamilySummary threeService = family(result, 3);
        assertTrue(threeService.available());
        assertEquals(3, threeService.supportedPatrols().size());
        assertTrue(threeService.bestFullyEvaluatedOwnSemiCollections()
                > family(result, 0).bestFullyEvaluatedOwnSemiCollections());
        assertTrue(result.beamResult().evaluation().hybrid().ownSemiCollections() >= 3);
        assertTrue(result.beamResult().evaluation().hybrid().hybridMarginScore4()
                >= threeService.bestFullyEvaluatedHybridMarginScore4());
        assertEquals(0, result.stats().tourSearchPathfindingExecutions());
    }

    @Test
    void rootFamilyProvenanceAndPhysicalCountsAreDeterministic() {
        DayState state = state(24, 10, List.of(
                AgentState.patrol(P0, new Position(1), 0),
                AgentState.patrol(P1, new Position(8), 0),
                AgentState.refuel(R0, new Position(4))), List.of(
                spot("A", 0, 1), spot("B", 9, 1)));
        JointTeamBeamR3Config config = JointTeamBeamR3Config.defaults()
                .withRootFamilyAuditMode(R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY);

        JointTeamBeamR3Result first = new JointTeamBeamR3Planner(config).planWithStats(state);
        JointTeamBeamR3Result second = new JointTeamBeamR3Planner(config).planWithStats(state);

        assertEquals(first.rootFamilyAudit().selectedProvenance(), second.rootFamilyAudit().selectedProvenance());
        assertEquals(first.rootFamilyAudit().families(), second.rootFamilyAudit().families());
        assertEquals(first.rootFamilyAudit().globalUniquePhysicalTerminals(),
                second.rootFamilyAudit().globalUniquePhysicalTerminals());
    }

    @Test
    void largeSupportOpportunitySetNeverEnumeratesTheProductSpace() {
        List<AgentState> agents = List.of(
                AgentState.patrol(new AgentId(0), new Position(0), 0),
                AgentState.patrol(new AgentId(1), new Position(12), 0),
                AgentState.patrol(new AgentId(2), new Position(24), 0),
                AgentState.patrol(new AgentId(3), new Position(36), 0),
                AgentState.patrol(new AgentId(4), new Position(48), 0),
                AgentState.refuel(R0, new Position(30)));
        DayState state = state(60, 60, agents, List.of(
                spot("A", 2, 1), spot("B", 10, 1), spot("C", 18, 1), spot("D", 26, 1),
                spot("A", 34, 1), spot("B", 42, 1), spot("C", 50, 1), spot("D", 58, 1)));
        JointTeamBeamR3Config config = JointTeamBeamR3Config.defaults();
        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(config).planWithStats(state);

        assertTrue(result.stats().partialToursGenerated() <= config.maxPartialToursGenerated());
        assertTrue(result.stats().skeletonsConsidered() <= config.maxSkeletonsConsidered());
        assertTrue(result.stats().skeletonsValidated() <= config.maxSkeletonsValidated());
        assertTrue(result.stats().skeletonsRetained() <= config.maxSkeletonsRetained());
        assertEquals(0, result.stats().tourSearchPathfindingExecutions());
        assertEquals(0, result.stats().beamSearchPathfindingExecutions());
    }

    @Test
    void expiredBudgetReturnsAValidFallbackInsteadOfThrowing() {
        DayState state = state(8, 4, List.of(AgentState.patrol(P0, new Position(0), 0),
                AgentState.refuel(R0, new Position(1))), List.of(spot("A", 3, 1)));
        JointTeamBeamR3Config tiny = new JointTeamBeamR3Config(2, 1, 64, 16, 24, 24, 4, 216, 48, 24, 12);
        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(tiny, () -> Long.MAX_VALUE,
                new vn.ptit.procon.planner.RefuelRouteFinder()).planWithStats(state);

        assertInstanceOf(ValidDaySimulationResult.class, new DaySimulator().simulate(state, result.plan()));
        assertTrue(result.stats().planningDeadlineTriggered());
        assertFalse(result.plan().actionsByAgent().isEmpty());
    }

    @Test
    void refuelRootIsRejectedWhenGainOverNoRefuelIsZero() {
        DayState state = state(16, 8, List.of(
                AgentState.patrol(P0, new Position(0), 5),
                AgentState.refuel(R0, new Position(3))), List.of(
                spot("A", 2, 1)));
        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(AdaptiveR3Policy.defaults()).planWithStats(state);
        assertEquals(0, result.stats().selectedSupportServiceCount());
    }

    @Test
    void abundantFuelSuppressesSupportPortfolioAndKeepsNoRefuelRoot() {
        DayState state = state(16, 8, 60, List.of(
                AgentState.patrol(P0, new Position(0), 60),
                AgentState.refuel(R0, new Position(3))), List.of(
                spot("A", 2, 1)));

        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(AdaptiveR3Policy.defaults()).planWithStats(state);

        assertEquals(1, result.stats().rootCandidates());
        assertEquals(0, result.stats().partialToursGenerated());
        assertEquals(0, result.stats().selectedSupportServiceCount());
        assertEquals("NO_REFUEL", result.stats().selectedSupportSkeletonSignature());
    }

    private static DayState state(int steps, int width, List<AgentState> agents, List<UdonSpot> spots) {
        return state(steps, width, 10, agents, spots);
    }

    private static DayState state(int steps, int width, int fuelCapacity, List<AgentState> agents, List<UdonSpot> spots) {
        Terrain[] terrain = new Terrain[width]; Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>(); spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData data = new StaticMatchData(new HexMap(width, 1, terrain), new DayStepBudgets(new int[] {steps}),
                List.of(), new FuelCapacity(fuelCapacity), spots);
        return new DayState(data, new DayIndex(0), agents, Map.of(), stock);
    }
    private static UdonSpot spot(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }

    private static R3RootFamilySummary family(JointTeamBeamR3Result result, int serviceCount) {
        return result.rootFamilyAudit().families().stream()
                .filter(value -> value.serviceCount() == serviceCount).findFirst().orElseThrow();
    }

    private static final class CountingClock implements LongSupplier {
        private final int zeroCalls;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingClock(int zeroCalls) { this.zeroCalls = zeroCalls; }

        @Override public long getAsLong() {
            return calls.getAndIncrement() < zeroCalls ? 0L : Long.MAX_VALUE;
        }

        int calls() { return calls.get(); }
    }
}
