package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

class CapacityAwareTeamAllocatorTest {

    private static final AgentState A = AgentState.patrol(new AgentId(0), new Position(0), 60);
    private static final AgentState B = AgentState.patrol(new AgentId(1), new Position(1), 60);
    private static final AgentState C = AgentState.patrol(new AgentId(2), new Position(2), 60);

    @Test
    void highStockSpotCanReceiveThreePhysicalClaims() {
        Position x = new Position(10);
        CollectionOpportunityCapacity capacity = capacity(x, "A", 3, 0, 1, 2);

        CapacityAwareTeamAllocator.Candidate candidate = generate(
                List.of(A, B, C), List.of(capacity), Map.of()).getFirst();

        assertEquals(3, candidate.allocation().totalLogicalClaims());
        assertEquals(1, candidate.allocation().distinctClaimedSpots());
        assertEquals(1, candidate.allocation().multiClaimSpotCount());
        assertEquals(3, candidate.timeline().expectedOwnSuccessfulClaims());
        assertEquals(1, candidate.allocation().claimsFor(new AgentId(0)).size());
        assertEquals(1, candidate.allocation().claimsFor(new AgentId(1)).size());
        assertEquals(1, candidate.allocation().claimsFor(new AgentId(2)).size());
    }

    @Test
    void capacityExhaustionRejectsThirdLaterArrival() {
        Position x = new Position(10);
        CollectionOpportunityCapacity capacity = capacity(x, "A", 2, 0, 1, 2);
        List<AgentState> patrols = List.of(A, B, C);

        CapacityAwareTeamAllocator.Candidate candidate = CapacityAwareTeamAllocator.generate(
                patrols, List.of(capacity),
                (agent, target) -> new CapacityAwareTeamAllocator.RouteCost(
                        agent.id().value() == 0 ? 3 : agent.id().value() == 1 ? 5 : 7, 1),
                Map.of(), 60).getFirst();

        assertEquals(2, candidate.allocation().totalLogicalClaims());
        assertEquals(2, candidate.timeline().expectedOwnSuccessfulClaims());
    }

    @Test
    void opponentPressureConsumesCapacityBeforeLaterOwnArrival() {
        Position x = new Position(10);
        CollectionOpportunityCapacity capacity = capacity(x, "A", 2, 0, 1);

        CapacityAwareTeamAllocator.Candidate candidate = CapacityAwareTeamAllocator.generate(
                List.of(A, B), List.of(capacity),
                (agent, target) -> new CapacityAwareTeamAllocator.RouteCost(
                        agent.id().value() == 0 ? 3 : 6, 1),
                Map.of(x, List.of(4)), 60).getFirst();

        assertEquals(2, candidate.allocation().totalLogicalClaims());
        assertEquals(1, candidate.timeline().expectedOwnSuccessfulClaims());
        assertTrue(candidate.timeline().expectedOpponentClaimsBeforeOwn() > 0);
    }

    @Test
    void equalStepOpponentWinsTheConservativeTie() {
        Position x = new Position(10);
        CollectionOpportunityCapacity capacity = capacity(x, "A", 1, 0);

        CapacityAwareTeamAllocator.Candidate candidate = CapacityAwareTeamAllocator.generate(
                List.of(A), List.of(capacity),
                (agent, target) -> new CapacityAwareTeamAllocator.RouteCost(4, 1),
                Map.of(x, List.of(4)), 60).getFirst();

        assertEquals(0, candidate.timeline().expectedOwnSuccessfulClaims());
        assertEquals(1, candidate.timeline().expectedOpponentClaimsBeforeOwn());
    }

    @Test
    void capacityBrandSeedKeepsFourBrandsBeforeFillingStock() {
        List<CollectionOpportunityCapacity> capacities = List.of(
                capacity(new Position(10), "A", 2, 0, 1, 2),
                capacity(new Position(11), "B", 2, 0, 1, 2),
                capacity(new Position(12), "C", 2, 0, 1, 2),
                capacity(new Position(13), "D", 2, 0, 1, 2));

        CapacityAwareTeamAllocator.Candidate candidate = generate(
                List.of(A, B, C), capacities, Map.of()).stream()
                .filter(value -> value.allocation().seed()
                        == CapacityAwareTeamAllocation.Seed.CAPACITY_BRAND)
                .findFirst().orElseThrow();

        assertEquals(4, candidate.allocation().claims().stream()
                .map(claim -> capacities.stream()
                        .filter(capacity -> capacity.spot().equals(claim.spot()))
                        .findFirst().orElseThrow().brand())
                .distinct().count());
    }

    @Test
    void m5068ShapeProducesMoreLogicalClaimsThanDistinctSpots() {
        List<CollectionOpportunityCapacity> capacities = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> capacity(new Position(10 + index), "B" + index, 2, 0, 1, 2))
                .toList();

        CapacityAwareTeamAllocator.Candidate candidate = generate(
                List.of(A, B, C, AgentState.patrol(new AgentId(3), new Position(3), 60),
                        AgentState.patrol(new AgentId(4), new Position(4), 60)),
                capacities, Map.of()).getFirst();

        assertTrue(candidate.allocation().totalLogicalClaims()
                > candidate.allocation().distinctClaimedSpots());
        assertTrue(candidate.allocation().totalLogicalClaims() > 8);
    }

    private static CollectionOpportunityCapacity capacity(
            Position spot, String brand, int stock, int... patrols) {
        return new CollectionOpportunityCapacity(spot, new BrandId(brand), stock,
                java.util.Arrays.stream(patrols).mapToObj(AgentId::new).toList());
    }

    private static List<CapacityAwareTeamAllocator.Candidate> generate(
            List<AgentState> patrols,
            List<CollectionOpportunityCapacity> capacities,
            Map<Position, List<Integer>> opponents) {
        return CapacityAwareTeamAllocator.generate(patrols, capacities,
                (agent, target) -> new CapacityAwareTeamAllocator.RouteCost(1, 1),
                opponents, 60);
    }
}
