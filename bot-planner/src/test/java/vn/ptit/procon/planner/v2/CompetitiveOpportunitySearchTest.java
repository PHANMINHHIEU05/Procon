package vn.ptit.procon.planner.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;

class CompetitiveOpportunitySearchTest {
    private static final AgentId P0 = new AgentId(0);
    private static final AgentId P1 = new AgentId(1);
    private static final AgentId R0 = new AgentId(9);

    @Test
    void simpleRaceTrapExposesOwnAndOpponentEta() {
        DayState state = state(12, List.of(AgentState.patrol(P0, new Position(0), 10)),
                List.of(spot("A", 4, 1)), List.of(new ObservedOtherGroup(7,
                        List.of(new ObservedOtherAgent(new Position(3), 0, 0)))));
        JointRouteCatalog routes = JointRouteCatalog.forState(state, true);
        CompetitiveOpportunityCatalog catalog = CompetitiveOpportunityCatalog.forState(state, routes);
        JointTeamSearchState root = JointTeamSearchState.root(state, java.util.Optional.empty());
        JointTeamSearchState.PatrolPrefix patrol = root.patrols().get(P0);
        JointRouteCatalog.CatalogRoute route = routes.routes(patrol.position(), new Position(4)).getFirst();

        CompetitiveOpportunity opportunity = catalog.describe(root, patrol,
                state.matchData().udonSpots().getFirst(), route);

        assertTrue(opportunity.ownEta() >= 0);
        assertTrue(opportunity.opponentEta() >= 0);
        assertTrue(opportunity.raceMarginSteps() < 0);
        assertEquals(0, opportunity.expectedAvailableStockAtOwnArrival());
    }

    @Test
    void highStockContestedDoesNotDiscardRemainingStock() {
        DayState state = state(12, List.of(AgentState.patrol(P0, new Position(0), 10)),
                List.of(spot("A", 4, 3)), List.of(new ObservedOtherGroup(7,
                        List.of(new ObservedOtherAgent(new Position(3), 0, 0)))));
        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withCompetitiveTargetPolicy(CompetitiveTargetPolicy.FINAL_FIXED)).planWithStats(state);

        assertTrue(result.beamResult().competitiveAudit().decisions().stream()
                .flatMap(value -> value.selected().stream())
                .anyMatch(value -> value.opportunity().expectedAvailableStockAtOwnArrival() > 0));
    }

    @Test
    void secureChainReportsContinuationValue() {
        DayState state = state(20, List.of(AgentState.patrol(P0, new Position(0), 10)),
                List.of(spot("A", 2, 1), spot("B", 4, 1), spot("C", 6, 1), spot("D", 8, 1)), List.of());
        JointRouteCatalog routes = JointRouteCatalog.forState(state, true);
        CompetitiveOpportunityCatalog catalog = CompetitiveOpportunityCatalog.forState(state, routes);
        JointTeamSearchState root = JointTeamSearchState.root(state, java.util.Optional.empty());
        JointTeamSearchState.PatrolPrefix patrol = root.patrols().get(P0);
        CompetitiveOpportunity opportunity = catalog.describe(root, patrol, state.matchData().udonSpots().get(1),
                routes.routes(patrol.position(), new Position(4)).getFirst());

        assertTrue(opportunity.continuationCandidateCount() > 0);
        assertTrue(opportunity.boundedContinuationValue() >= opportunity.continuationCandidateCount());
    }

    @Test
    void ownTeamCollisionKeepsNaturalOwnerObservable() {
        DayState state = state(12, List.of(
                AgentState.patrol(P0, new Position(0), 10), AgentState.patrol(P1, new Position(6), 10)),
                List.of(spot("A", 3, 1), spot("B", 6, 1)), List.of());
        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withCompetitiveTargetPolicy(CompetitiveTargetPolicy.FINAL_FIXED)).planWithStats(state);

        assertTrue(result.beamResult().competitiveAudit().decisions().stream()
                .flatMap(value -> value.selected().stream())
                .allMatch(value -> value.opportunity().ownArrivalGap() >= 0));
    }

    @Test
    void denialTrapDoesNotChangeFrozenTerminalComparator() {
        DayState state = state(12, List.of(AgentState.patrol(P0, new Position(0), 10)),
                List.of(spot("A", 2, 1), spot("B", 6, 1)), List.of(new ObservedOtherGroup(7,
                        List.of(new ObservedOtherAgent(new Position(6), 0, 0)))));
        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withCompetitiveTargetPolicy(CompetitiveTargetPolicy.FINAL_FIXED)).planWithStats(state);

        assertNotNull(result.beamResult().evaluation());
        assertEquals(0, result.beamResult().stats().searchPathfindingExecutions());
    }

    @Test
    void usefulDenialRemainsAnObservableContestedRole() {
        DayState state = state(12, List.of(AgentState.patrol(P0, new Position(0), 10)),
                List.of(spot("A", 2, 1), spot("B", 4, 2)), List.of(new ObservedOtherGroup(7,
                        List.of(new ObservedOtherAgent(new Position(4), 0, 0)))));
        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withCompetitiveTargetPolicy(CompetitiveTargetPolicy.RACE_ONLY)).planWithStats(state);

        assertTrue(result.beamResult().competitiveAudit().decisions().stream()
                .flatMap(value -> value.selected().stream())
                .anyMatch(value -> value.opportunity().contested()));
    }

    @Test
    void liveLikeM6861TracksRawToSecuredConversionDeterministically() {
        DayState state = state(30, List.of(
                AgentState.patrol(P0, new Position(0), 10), AgentState.patrol(P1, new Position(6), 10),
                AgentState.refuel(R0, new Position(3))),
                List.of(spot("A", 2, 2), spot("B", 5, 2), spot("C", 8, 2), spot("D", 10, 2)),
                List.of(new ObservedOtherGroup(7, List.of(new ObservedOtherAgent(new Position(9), 0, 0)))));
        JointTeamBeamR3Config config = JointTeamBeamR3Config.defaults()
                .withCompetitiveTargetPolicy(CompetitiveTargetPolicy.RACE_PLUS_CONTINUATION);
        JointTeamBeamR3Result first = new JointTeamBeamR3Planner(config).planWithStats(state);
        JointTeamBeamR3Result second = new JointTeamBeamR3Planner(config).planWithStats(state);
        CompetitiveSearchAudit audit = first.beamResult().competitiveAudit();

        assertEquals(first.plan().actionsByAgent(), second.plan().actionsByAgent());
        assertEquals(audit, second.beamResult().competitiveAudit());
        assertTrue(audit.rawPlannedCollections() >= audit.coupledOwnCollections());
        assertEquals(0, first.beamResult().stats().searchPathfindingExecutions());
        assertInstanceOfValid(state, first.plan());
    }

    @Test
    void competitivePortfolioHardCapAndK16RemainFrozen() {
        DayState state = state(30, List.of(AgentState.patrol(P0, new Position(0), 10)),
                List.of(spot("A", 1, 1), spot("B", 3, 1), spot("C", 5, 1), spot("D", 7, 1),
                        spot("E", 9, 1), spot("F", 11, 1)), List.of());
        JointTeamBeamR3Result result = new JointTeamBeamR3Planner(JointTeamBeamR3Config.defaults()
                .withCompetitiveTargetPolicy(CompetitiveTargetPolicy.FINAL_FIXED)).planWithStats(state);

        assertTrue(result.beamResult().competitiveAudit().decisions().stream()
                .allMatch(value -> value.selected().size() <= 4));
        assertTrue(result.stats().stageBEvaluated() <= 16);
        assertEquals(0, result.beamResult().stats().searchPathfindingExecutions());
    }

    private static void assertInstanceOfValid(DayState state, vn.ptit.procon.engine.TeamPlan plan) {
        assertFalse(new DaySimulator().simulate(state, plan) instanceof vn.ptit.procon.engine.InvalidDaySimulationResult);
    }

    private static DayState state(int steps, List<AgentState> agents, List<UdonSpot> spots,
            List<ObservedOtherGroup> opponents) {
        int width = 16;
        Terrain[] terrain = new Terrain[width];
        Arrays.fill(terrain, Terrain.PLAIN);
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData data = new StaticMatchData(new HexMap(width, 1, terrain),
                new DayStepBudgets(new int[] {steps}), List.of(), new FuelCapacity(10), spots);
        return new DayState(data, new DayIndex(0), agents, Map.of(), stock, opponents);
    }

    private static UdonSpot spot(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }
}
