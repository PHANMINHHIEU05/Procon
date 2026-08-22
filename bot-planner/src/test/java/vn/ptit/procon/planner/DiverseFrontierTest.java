package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.planner.StrategicDiversityKey.AgentOpening;

/**
 * Frontier retention and expansion scheduling for the diversity reserve.
 *
 * <p>States are modelled as {@code (score, strategy)} pairs so the fixtures can state exactly
 * which branch the unchanged quality comparator would have kept.</p>
 */
class DiverseFrontierTest {

    private static final int FRONTIER_LIMIT = 48;
    private static final int ELITE_SLOTS = 32;
    private static final int MAX_PER_STRATEGY = 4;

    /** Lower score is stronger, mirroring the engine's "first is best" frontier ordering. */
    private static final Comparator<Node> BY_SCORE = Comparator.comparingInt(Node::score)
            .thenComparingInt(Node::serial);

    @Test
    void monocultureFrontierKeepsEliteBestAndStillAdmitsOtherStrategies() {
        DiverseFrontier<Node> frontier = frontier();
        List<Node> added = new ArrayList<>();
        // 60 variations of one dominant opening, all stronger than any alternative opening.
        for (int index = 0; index < 60; index++) {
            added.add(add(frontier, index, opening(10)));
        }
        // Three strategically different openings that a pure quality frontier would evict.
        added.add(add(frontier, 500, opening(20)));
        added.add(add(frontier, 501, opening(30)));
        added.add(add(frontier, 502, opening(40)));

        List<Node> retained = drain(frontier);
        Map<StrategicDiversityKey, Long> byStrategy = new LinkedHashMap<>();
        retained.forEach(node -> byStrategy.merge(node.strategy(), 1L, Long::sum));

        assertTrue(retained.size() <= FRONTIER_LIMIT);
        assertEquals(retained.size(), new LinkedHashSet<>(retained).size());
        assertTrue(retained.contains(added.get(0)), "Globally best state must survive");
        assertTrue(byStrategy.size() >= 4, "Diversity lane must admit the alternative openings");
        assertTrue(byStrategy.get(opening(10)) >= ELITE_SLOTS,
                "Elite lane must stay uncapped for the dominant strategy");
        assertTrue(byStrategy.get(opening(20)) <= MAX_PER_STRATEGY);
        assertTrue(byStrategy.get(opening(30)) <= MAX_PER_STRATEGY);
        assertTrue(byStrategy.get(opening(40)) <= MAX_PER_STRATEGY);
    }

    @Test
    void diversityExpansionPrefersTheLeastExpandedStrategy() {
        DiverseFrontier<Node> frontier = frontier();
        Node bestOfDominant = add(frontier, 0, opening(10));
        add(frontier, 1, opening(10));
        add(frontier, 2, opening(10));
        Node bestOfAlternative = add(frontier, 90, opening(20));

        // Every strategy still sits at zero expansions, so the least-expanded tie-break resolves to
        // the globally best state: there is no exploration work to do yet.
        assertFalse(frontier.diversityAvailable());
        assertEquals(bestOfDominant, frontier.poll(false));

        assertTrue(frontier.diversityAvailable(),
                "Once the dominant strategy has been expanded, the alternative is behind");
        assertEquals(bestOfAlternative, frontier.poll(true),
                "A diversity turn must take the least-expanded strategy");
        assertEquals(2, frontier.uniqueStrategyKeysExpanded());
        assertEquals(1, frontier.maxStrategyExpansionCount());
        assertEquals(2, frontier.strategyBucketsSeen());
    }

    @Test
    void singleStrategyLeavesNoDiversityWorkAndKeepsFullQualityOrdering() {
        DiverseFrontier<Node> frontier = frontier();
        List<Node> added = new ArrayList<>();
        for (int index = 0; index < FRONTIER_LIMIT; index++) {
            added.add(add(frontier, index, opening(10)));
        }

        assertFalse(frontier.diversityAvailable(), "One opening leaves nothing to diversify over");
        assertEquals(1, frontier.strategyBucketsSeen());
        assertEquals(FRONTIER_LIMIT, frontier.size());
        List<Node> drained = drain(frontier);
        assertEquals(added, drained, "Fallback must behave like pure quality search");
        assertEquals(1, frontier.uniqueStrategyKeysExpanded());
    }

    @Test
    void unusedDiversityReserveReturnsToTheQualityLane() {
        DiverseFrontier<Node> frontier = frontier();
        // Two openings only: the diversity reserve cannot use all 16 of its slots.
        for (int index = 0; index < 40; index++) {
            add(frontier, index, opening(10));
        }
        for (int index = 0; index < 20; index++) {
            add(frontier, 100 + index, opening(20));
        }

        assertEquals(FRONTIER_LIMIT, frontier.size(),
                "Frontier capacity must never be left unused to satisfy diversity");
        assertTrue(frontier.eliteRetained() >= ELITE_SLOTS);
        assertTrue(frontier.diverseRetained() <= FRONTIER_LIMIT - ELITE_SLOTS);
        assertEquals(FRONTIER_LIMIT, frontier.eliteRetained() + frontier.diverseRetained());
    }

    @Test
    void boundedPriorityFrontierKeepsThePreM11RetentionContract() {
        SearchFrontier<Node> frontier = new BoundedPriorityFrontier<>(3, BY_SCORE);
        Node best = new Node(0, 0, opening(10));

        assertEquals(0, frontier.add(best));
        assertEquals(0, frontier.add(new Node(1, 1, opening(20))));
        assertEquals(0, frontier.add(new Node(2, 2, opening(30))));
        assertEquals(1, frontier.add(new Node(9, 3, opening(40))));
        assertFalse(frontier.diversityAvailable());
        assertEquals(3, frontier.size());
        assertEquals(best, frontier.poll(true), "Diversity requests must not change M10 polling");
    }

    private static DiverseFrontier<Node> frontier() {
        return new DiverseFrontier<>(
                FRONTIER_LIMIT, ELITE_SLOTS, MAX_PER_STRATEGY, BY_SCORE, Node::strategy);
    }

    private static Node add(DiverseFrontier<Node> frontier, int score, StrategicDiversityKey key) {
        Node node = new Node(score, score, key);
        frontier.add(node);
        return node;
    }

    private static List<Node> drain(DiverseFrontier<Node> frontier) {
        List<Node> drained = new ArrayList<>();
        while (!frontier.isEmpty()) {
            drained.add(frontier.poll(false));
        }
        return drained;
    }

    private static StrategicDiversityKey opening(int firstTarget) {
        return StrategicDiversityKey.of(List.of(new AgentOpening(0, firstTarget)));
    }

    private record Node(int score, int serial, StrategicDiversityKey strategy) {
    }
}
