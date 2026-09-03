package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

class TeamOpportunityAllocatorTest {

    private static final AgentState A = AgentState.patrol(new AgentId(0), new Position(0), 40);
    private static final AgentState B = AgentState.patrol(new AgentId(1), new Position(10), 40);
    private static final AgentState C = AgentState.patrol(new AgentId(2), new Position(20), 40);

    @Test
    void generatesExactlyFourSeedsWithAtMostFourRefinements() {
        List<TeamOpportunityAllocator.Candidate> candidates = generate(costs());

        assertEquals(16, candidates.size());
        assertEquals(4, candidates.stream().map(value -> value.allocation().seed()).distinct().count());
        assertTrue(candidates.stream().allMatch(value -> value.allocation().refinement() <= 4));
    }

    @Test
    void minCostOwnershipUsesNearestAgentAndStableTieBreak() {
        List<TeamOpportunityAllocator.Candidate> candidates = generate(costs());
        TeamOpportunityAllocation allocation = candidates.get(0).allocation();

        assertTrue(allocation.assignedTo(new AgentId(0)).contains(new Position(2)));
        assertTrue(allocation.assignedTo(new AgentId(1)).contains(new Position(4)));
        assertTrue(allocation.assignedTo(new AgentId(2)).contains(new Position(6)));
    }

    @Test
    void m5049ShapedRegressionAllocatesDistinctEarlyOpportunities() {
        List<TeamOpportunityAllocator.Opportunity> opportunities = List.of(
                opportunity(2, "A"), opportunity(4, "B"), opportunity(6, "C"));
        Map<String, TeamOpportunityAllocator.RouteCost> costs = costMap(
                key(0, 2), new TeamOpportunityAllocator.RouteCost(1, 1),
                key(0, 4), new TeamOpportunityAllocator.RouteCost(8, 8),
                key(0, 6), new TeamOpportunityAllocator.RouteCost(8, 8),
                key(1, 2), new TeamOpportunityAllocator.RouteCost(8, 8),
                key(1, 4), new TeamOpportunityAllocator.RouteCost(1, 1),
                key(1, 6), new TeamOpportunityAllocator.RouteCost(8, 8),
                key(2, 2), new TeamOpportunityAllocator.RouteCost(8, 8),
                key(2, 4), new TeamOpportunityAllocator.RouteCost(8, 8),
                key(2, 6), new TeamOpportunityAllocator.RouteCost(1, 1));

        TeamOpportunityAllocation allocation = TeamOpportunityAllocator.generate(
                List.of(A, B, C), opportunities,
                (agent, position) -> costs.get(key(agent.id().value(), position.value())),
                ignored -> 0).getFirst().allocation();

        assertEquals(3, allocation.distinctAssignedOpportunities());
        assertEquals(3, allocation.assigned().values().stream()
                .filter(values -> !values.isEmpty()).count());
    }

    @Test
    void boundedRouteRefinementChangesOwnershipSignature() {
        List<TeamOpportunityAllocator.Candidate> candidates = generate(costs());

        assertNotEquals(candidates.get(0).allocation().signature(),
                candidates.get(1).allocation().signature());
    }

    @Test
    void balancedLoadKeepsProjectedLoadsLowerThanNearestOverload() {
        List<TeamOpportunityAllocator.Candidate> candidates = generate(costMap(
                key(0, 2), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(0, 4), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(0, 6), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(0, 8), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(1, 2), new TeamOpportunityAllocator.RouteCost(3, 3),
                key(1, 4), new TeamOpportunityAllocator.RouteCost(3, 3),
                key(1, 6), new TeamOpportunityAllocator.RouteCost(3, 3),
                key(1, 8), new TeamOpportunityAllocator.RouteCost(3, 3),
                key(2, 2), new TeamOpportunityAllocator.RouteCost(20, 20),
                key(2, 4), new TeamOpportunityAllocator.RouteCost(20, 20),
                key(2, 6), new TeamOpportunityAllocator.RouteCost(20, 20),
                key(2, 8), new TeamOpportunityAllocator.RouteCost(20, 20)));

        Map<AgentId, Integer> nearest = candidates.get(0).estimatedLoads();
        Map<AgentId, Integer> balanced = candidates.get(4).estimatedLoads();
        assertTrue(max(nearest) > max(balanced));
    }

    @Test
    void brandCoverageSeedIncludesEveryReachableBrand() {
        List<TeamOpportunityAllocator.Candidate> candidates = generate(costMap(
                key(0, 2), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(0, 4), new TeamOpportunityAllocator.RouteCost(4, 4),
                key(0, 6), new TeamOpportunityAllocator.RouteCost(6, 6),
                key(0, 8), new TeamOpportunityAllocator.RouteCost(8, 8),
                key(1, 2), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(1, 4), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(1, 6), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(1, 8), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(2, 2), new TeamOpportunityAllocator.RouteCost(3, 3),
                key(2, 4), new TeamOpportunityAllocator.RouteCost(3, 3),
                key(2, 6), new TeamOpportunityAllocator.RouteCost(3, 3),
                key(2, 8), new TeamOpportunityAllocator.RouteCost(3, 3)));

        TeamOpportunityAllocation allocation = candidates.get(12).allocation();
        assertEquals(4, allocation.assigned().values().stream().flatMap(List::stream)
                .map(position -> "B" + position.value()).distinct().count());
    }

    private static List<TeamOpportunityAllocator.Candidate> generate(
            Map<String, TeamOpportunityAllocator.RouteCost> costs) {
        List<TeamOpportunityAllocator.Opportunity> opportunities = List.of(
                opportunity(2, "B0"), opportunity(4, "B1"),
                opportunity(6, "B2"), opportunity(8, "B3"));
        return TeamOpportunityAllocator.generate(List.of(A, B, C), opportunities,
                (agent, position) -> costs.get(key(agent.id().value(), position.value())),
                ignored -> 0);
    }

    private static Map<String, TeamOpportunityAllocator.RouteCost> costs() {
        return costMap(
                key(0, 2), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(0, 4), new TeamOpportunityAllocator.RouteCost(8, 8),
                key(0, 6), new TeamOpportunityAllocator.RouteCost(12, 12),
                key(0, 8), new TeamOpportunityAllocator.RouteCost(16, 16),
                key(1, 2), new TeamOpportunityAllocator.RouteCost(8, 8),
                key(1, 4), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(1, 6), new TeamOpportunityAllocator.RouteCost(8, 8),
                key(1, 8), new TeamOpportunityAllocator.RouteCost(12, 12),
                key(2, 2), new TeamOpportunityAllocator.RouteCost(12, 12),
                key(2, 4), new TeamOpportunityAllocator.RouteCost(8, 8),
                key(2, 6), new TeamOpportunityAllocator.RouteCost(2, 2),
                key(2, 8), new TeamOpportunityAllocator.RouteCost(8, 8));
    }

    private static TeamOpportunityAllocator.Opportunity opportunity(int position, String brand) {
        return new TeamOpportunityAllocator.Opportunity(
                new Position(position), new BrandId(brand), 1);
    }

    private static String key(int agent, int position) {
        return agent + ":" + position;
    }

    private static Map<String, TeamOpportunityAllocator.RouteCost> costMap(Object... values) {
        Map<String, TeamOpportunityAllocator.RouteCost> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index],
                    (TeamOpportunityAllocator.RouteCost) values[index + 1]);
        }
        return result;
    }

    private static int max(Map<AgentId, Integer> values) {
        return values.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
