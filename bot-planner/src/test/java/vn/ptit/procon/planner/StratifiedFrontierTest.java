package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.planner.StrategicDiversityKey.AgentOpening;

/**
 * Retention and per-strategy access of the strategy-portfolio frontier.
 *
 * <p>States are modelled as {@code (score, strategy)} pairs so a fixture can state exactly which
 * branch the unchanged quality comparator would have kept. The frontier keeps one global capacity
 * limit: a global elite lane that is never capped per strategy, plus a portfolio reserve that
 * preserves representation and hands unused capacity back to quality.</p>
 */
class StratifiedFrontierTest {

    private static final int FRONTIER_LIMIT = 48;
    private static final int ELITE_SLOTS = 24;

    /** Lower score is stronger, mirroring the engine's "first is best" frontier ordering. */
    private static final Comparator<Node> BY_SCORE = Comparator.comparingInt(Node::score)
            .thenComparingInt(Node::serial);

    @Test
    void globalEliteSlotsSurviveWithoutAnyPerStrategyCap() {
        StratifiedFrontier<Node> frontier = frontier();
        List<Node> dominant = new ArrayList<>();
        // 60 variations of one dominant opening, all stronger than any alternative opening.
        for (int index = 0; index < 60; index++) {
            dominant.add(add(frontier, index, opening(10)));
        }
        add(frontier, 500, opening(20));
        add(frontier, 501, opening(30));
        add(frontier, 502, opening(40));

        assertEquals(FRONTIER_LIMIT, frontier.size(), "No frontier slot may be left empty");
        List<Node> retained = drain(frontier);
        Map<StrategicDiversityKey, Long> byStrategy = new TreeMap<>();
        retained.forEach(node -> byStrategy.merge(node.strategy(), 1L, Long::sum));

        assertEquals(retained.size(), identityCount(retained), "A state may be retained once only");
        assertSame(dominant.get(0), retained.get(0), "Globally best state must survive");
        assertTrue(byStrategy.get(opening(10)) >= ELITE_SLOTS,
                "The global elite lane must stay uncapped for a genuinely strong strategy");
        assertTrue(byStrategy.size() >= 4, "The portfolio reserve must admit alternative openings");
    }

    @Test
    void portfolioReserveKeepsEveryQualifiedStrategyRepresented() {
        StratifiedFrontier<Node> frontier = frontier();
        for (int index = 0; index < 60; index++) {
            add(frontier, index, opening(10));
        }
        List<StrategicDiversityKey> alternatives = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            StrategicDiversityKey key = opening(20 + index * 10);
            alternatives.add(key);
            // Deliberately far weaker than the dominant opening, so only the reserve can keep them.
            add(frontier, 900 + index, key);
        }
        frontier.markQualified(alternatives);
        // One more state forces retention to run again now that the portfolio is known.
        add(frontier, 61, opening(10));

        assertEquals(FRONTIER_LIMIT, frontier.size());
        for (StrategicDiversityKey key : alternatives) {
            assertTrue(frontier.hasStates(key),
                    "Qualified strategy " + key + " must keep at least one retained state");
        }
        assertEquals(ELITE_SLOTS, frontier.eliteRetained());
        assertEquals(FRONTIER_LIMIT - ELITE_SLOTS, frontier.portfolioRetained());
    }

    @Test
    void unusedPortfolioCapacityReturnsToGlobalQuality() {
        StratifiedFrontier<Node> frontier = frontier();
        // Two openings only: the portfolio reserve cannot spend all of its capacity on breadth.
        for (int index = 0; index < 40; index++) {
            add(frontier, index, opening(10));
        }
        for (int index = 0; index < 20; index++) {
            add(frontier, 100 + index, opening(20));
        }

        assertEquals(FRONTIER_LIMIT, frontier.size(),
                "Frontier capacity must never be left unused to satisfy strategy balancing");
        assertEquals(FRONTIER_LIMIT, frontier.eliteRetained() + frontier.portfolioRetained());
        assertEquals(2, frontier.strategyBucketsSeen());
        List<Node> retained = drain(frontier);
        assertEquals(retained.size(), identityCount(retained));
    }

    @Test
    void perStrategyAccessIsDeterministicAndNeverHandsOutAStateTwice() {
        StratifiedFrontier<Node> frontier = frontier();
        Node bestOfDominant = add(frontier, 0, opening(10));
        Node secondOfDominant = add(frontier, 1, opening(10));
        Node bestOfAlternative = add(frontier, 90, opening(20));

        assertEquals(opening(10), frontier.bestStrategy());
        assertEquals(List.of(opening(10), opening(20)), frontier.rankedStrategies());
        assertSame(bestOfAlternative, frontier.head(opening(20)));
        assertTrue(frontier.hasStates(opening(20)));

        assertSame(bestOfAlternative, frontier.pollStrategy(opening(20)));
        assertFalse(frontier.hasStates(opening(20)), "Retirement happens when a bucket empties");
        assertNull(frontier.pollStrategy(opening(20)));
        assertNull(frontier.head(opening(20)));
        assertEquals(List.of(opening(10)), frontier.rankedStrategies());
        assertEquals(1, frontier.expansionCount(opening(20)));

        assertSame(bestOfDominant, frontier.poll(false));
        assertSame(secondOfDominant, frontier.poll(false));
        assertTrue(frontier.isEmpty());
        assertNull(frontier.poll(false));
        assertEquals(2, frontier.expansionCount(opening(10)));
        assertEquals(2, frontier.maxStrategyExpansionCount());
        assertEquals(2, frontier.strategiesExpanded());
        assertEquals(Map.of(opening(10), 2, opening(20), 1), frontier.expansionCountsByStrategy());
    }

    @Test
    void strategiesRankByTheUnchangedComparatorOverTheirBestAvailableState() {
        StratifiedFrontier<Node> frontier = frontier();
        add(frontier, 30, opening(40));
        add(frontier, 10, opening(20));
        add(frontier, 20, opening(30));
        add(frontier, 11, opening(20));

        assertEquals(List.of(opening(20), opening(30), opening(40)), frontier.rankedStrategies());
        assertEquals(opening(20), frontier.bestStrategy());
        assertEquals(0, frontier.bestFrontierOrdinal(opening(20)));
        assertEquals(2, frontier.bestFrontierOrdinal(opening(30)));
        assertEquals(3, frontier.bestFrontierOrdinal(opening(40)));
        assertEquals(-1, frontier.bestFrontierOrdinal(opening(50)));
        assertEquals(2, frontier.generatedStateCount(opening(20)));
        assertEquals(0, frontier.firstSeenOrder(opening(40)));
        assertEquals(1, frontier.firstSeenOrder(opening(20)));
    }

    @Test
    void diversityTurnsFollowTheLeastExpandedStrategy() {
        StratifiedFrontier<Node> frontier = frontier();
        Node bestOfDominant = add(frontier, 0, opening(10));
        add(frontier, 1, opening(10));
        Node bestOfAlternative = add(frontier, 90, opening(20));

        // Every strategy still sits at zero expansions, so the tie-break resolves to global quality.
        assertFalse(frontier.diversityAvailable());
        assertSame(bestOfDominant, frontier.poll(false));
        assertTrue(frontier.diversityAvailable());
        assertSame(bestOfAlternative, frontier.poll(true));
        assertEquals(1, frontier.expansionCount(opening(20)));
    }

    @Test
    void singleStrategyReducesToPureQualityOrdering() {
        StratifiedFrontier<Node> frontier = frontier();
        List<Node> added = new ArrayList<>();
        for (int index = 0; index < FRONTIER_LIMIT; index++) {
            added.add(add(frontier, index, opening(10)));
        }

        assertFalse(frontier.diversityAvailable(), "One opening leaves nothing to balance over");
        assertEquals(1, frontier.strategyBucketsSeen());
        assertEquals(FRONTIER_LIMIT, frontier.size());
        assertEquals(added, drain(frontier), "Fallback must behave like pure quality search");
    }

    @Test
    void rejectsReservesOutsideTheFrontierLimit() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new StratifiedFrontier<>(0, 1, BY_SCORE, Node::strategy));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new StratifiedFrontier<>(48, 0, BY_SCORE, Node::strategy));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new StratifiedFrontier<>(48, 49, BY_SCORE, Node::strategy));
    }

    private static StratifiedFrontier<Node> frontier() {
        return new StratifiedFrontier<>(FRONTIER_LIMIT, ELITE_SLOTS, BY_SCORE, Node::strategy);
    }

    private static Node add(StratifiedFrontier<Node> frontier, int score, StrategicDiversityKey key) {
        Node node = new Node(score, score, key);
        frontier.add(node);
        return node;
    }

    private static List<Node> drain(StratifiedFrontier<Node> frontier) {
        List<Node> drained = new ArrayList<>();
        while (!frontier.isEmpty()) {
            drained.add(frontier.poll(false));
        }
        return drained;
    }

    private static int identityCount(List<Node> nodes) {
        Set<Node> distinct = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        distinct.addAll(nodes);
        return distinct.size();
    }

    private static StrategicDiversityKey opening(int firstTarget) {
        return StrategicDiversityKey.of(List.of(new AgentOpening(0, firstTarget)));
    }

    private record Node(int score, int serial, StrategicDiversityKey strategy) {
    }
}
