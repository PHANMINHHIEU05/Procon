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
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.planner.CandidatePortfolioSelector.CandidatePortfolio;

/**
 * Per-state candidate portfolio behaviour on the real production candidate path.
 *
 * <p>Every fixture ranks its candidates with the unchanged M10 comparators
 * {@link IntentAwareCandidateMetrics#harvestPreference()} and
 * {@link IntentAwareCandidateMetrics#coveragePreference()}, mapped from
 * {@link TeamTargetCandidate} plus branch-local {@link IntentRouteMetrics} exactly the way
 * {@code AnytimeTeamPlanner.candidatePreference} does for
 * {@code ANYTIME_DIVERSE_INTENT_AWARE}. The selector under test is the same
 * {@link CandidatePortfolioSelector} the diverse planner calls, so what the fixtures state is
 * what production does; no stand-in ordering is used.</p>
 */
class CandidatePortfolioSelectorTest {

    private static final int LIMIT = AnytimePlannerConfig.DEFAULT_TOP_CANDIDATES_PER_STATE;
    private static final int ELITE = DiverseSearchConfig.DEFAULT_ELITE_CANDIDATE_SLOTS;

    @Test
    void globallyBestCandidateAlwaysSurvivesTheDiversityReserve() {
        Fixture fixture = new Fixture();
        fixture.add(0, 10, 3, harvestValue(90));
        fixture.add(0, 11, 4, harvestValue(80));
        fixture.add(0, 12, 5, harvestValue(70));
        fixture.add(0, 13, 6, harvestValue(60));
        fixture.add(1, 14, 7, harvestValue(50));
        fixture.add(2, 15, 8, harvestValue(40));
        Comparator<TeamTargetCandidate> m10 = fixture.harvestPreference();
        TeamTargetCandidate best = fixture.candidates().stream().min(m10).orElseThrow();

        CandidatePortfolio portfolio =
                CandidatePortfolioSelector.select(fixture.candidates(), m10, LIMIT, ELITE);

        assertTrue(portfolio.selected().contains(best),
                "The globally best candidate must never be dropped for diversity");
        assertEquals(best, portfolio.selected().get(0));
        assertEquals(LIMIT, portfolio.selected().size());
        assertEquals(ELITE, portfolio.eliteSelected());
        assertEquals(LIMIT - ELITE, portfolio.diverseSelected());
    }

    @Test
    void underdogAgentEnteringJustBelowTheCutIsRetainedForAgentDiversity() {
        // The real M10 harvest comparator ranks agent 0's four candidates strictly above the only
        // candidate agent 1 can offer, so pure top-K would spend the whole state on one agent.
        Fixture fixture = new Fixture();
        TeamTargetCandidate strongest = fixture.add(0, 10, 3, harvestValue(90));
        fixture.add(0, 11, 4, harvestValue(80));
        fixture.add(0, 12, 5, harvestValue(70));
        fixture.add(0, 13, 6, harvestValue(60));
        TeamTargetCandidate underdog = fixture.add(1, 14, 7, harvestValue(10));
        Comparator<TeamTargetCandidate> m10 = fixture.harvestPreference();
        List<TeamTargetCandidate> topK = fixture.candidates().stream().sorted(m10).limit(LIMIT).toList();

        CandidatePortfolio portfolio =
                CandidatePortfolioSelector.select(fixture.candidates(), m10, LIMIT, ELITE);

        assertFalse(topK.contains(underdog), "Fixture must hide the second agent below the cut");
        assertTrue(topK.stream().allMatch(candidate -> candidate.patrolAgentId().value() == 0));
        assertTrue(portfolio.selected().contains(underdog),
                "The diversity reserve must admit the second acting PATROL agent");
        assertEquals(strongest, portfolio.selected().get(0));
        assertTrue(portfolio.selected().containsAll(topK.subList(0, ELITE)),
                "Elite preservation must keep the strongest branches under the M10 comparator");
        assertEquals(2, agentsOf(portfolio).size());
        assertEquals(LIMIT, portfolio.selected().size());
    }

    @Test
    void unrepresentedTargetSpotIsRetainedForTargetDiversity() {
        // Every candidate the M10 comparator puts in the top four aims at spot 10; only the two
        // weakest aim elsewhere, so pure top-K would leave the state a single-spot monoculture.
        Fixture fixture = new Fixture();
        fixture.add(0, 10, 3, harvestValue(90));
        fixture.add(1, 10, 4, harvestValue(80));
        fixture.add(2, 10, 5, harvestValue(70));
        fixture.add(3, 10, 6, harvestValue(60));
        fixture.add(0, 20, 7, harvestValue(12));
        fixture.add(0, 30, 8, harvestValue(11));
        Comparator<TeamTargetCandidate> m10 = fixture.harvestPreference();

        CandidatePortfolio portfolio =
                CandidatePortfolioSelector.select(fixture.candidates(), m10, LIMIT, ELITE);
        List<Integer> targets = targetsOf(portfolio);

        assertEquals(List.of(10), fixture.candidates().stream().sorted(m10).limit(LIMIT)
                .map(candidate -> candidate.targetPosition().value()).distinct().toList());
        assertEquals(LIMIT, portfolio.selected().size());
        assertTrue(targets.contains(10));
        assertTrue(targets.contains(20));
        assertTrue(targets.contains(30));
        assertEquals(3, new LinkedHashSet<>(targets).size(),
                "Target diversity must break the single-spot monoculture");
    }

    @Test
    void coveragePhaseKeepsTheNewRealizableBrandAndStillAddsANewAgent() {
        // Coverage ordering leads with a new forecast-realizable team brand, so elite preservation
        // must keep that candidate even though its intent-adjusted score is the lowest.
        Fixture fixture = new Fixture();
        TeamTargetCandidate newBrand = fixture.add(0, 10, 9, newRealizableBrandValue(20));
        fixture.add(0, 11, 3, harvestValue(90));
        fixture.add(0, 12, 4, harvestValue(80));
        fixture.add(0, 13, 5, harvestValue(70));
        TeamTargetCandidate otherAgent = fixture.add(1, 14, 6, harvestValue(10));
        Comparator<TeamTargetCandidate> m10 = fixture.coveragePreference();

        CandidatePortfolio portfolio =
                CandidatePortfolioSelector.select(fixture.candidates(), m10, LIMIT, ELITE);

        assertEquals(newBrand, fixture.candidates().stream().min(m10).orElseThrow());
        assertEquals(newBrand, portfolio.selected().get(0));
        assertTrue(portfolio.selected().contains(otherAgent),
                "A new agent on a new target is the strongest novelty class");
        assertEquals(otherAgent, portfolio.selected().get(ELITE),
                "The first diversity slot must go to the new agent and new target");
        assertEquals(LIMIT, portfolio.selected().size());
    }

    @Test
    void eliteReserveKeepsHalfTheCapacityAndSelectionIsDeterministic() {
        Fixture fixture = new Fixture();
        fixture.add(0, 10, 3, harvestValue(90));
        fixture.add(0, 11, 4, harvestValue(80));
        fixture.add(1, 12, 5, harvestValue(70));
        fixture.add(1, 13, 6, harvestValue(60));
        fixture.add(2, 14, 7, harvestValue(50));
        Comparator<TeamTargetCandidate> m10 = fixture.harvestPreference();
        List<TeamTargetCandidate> reversed = new ArrayList<>(fixture.candidates());
        java.util.Collections.reverse(reversed);

        CandidatePortfolio first =
                CandidatePortfolioSelector.select(fixture.candidates(), m10, LIMIT, ELITE);
        CandidatePortfolio again =
                CandidatePortfolioSelector.select(fixture.candidates(), m10, LIMIT, ELITE);
        CandidatePortfolio fromReversedInput =
                CandidatePortfolioSelector.select(reversed, m10, LIMIT, ELITE);

        assertEquals(first.selected(), again.selected());
        assertEquals(first.selected(), fromReversedInput.selected(),
                "Input iteration order must never change the retained portfolio");
        assertTrue(first.eliteSelected() * 2 >= first.selected().size(),
                "At least half of the per-state capacity must stay elite");
        assertEquals(CandidatePortfolio.empty().selected(),
                CandidatePortfolioSelector.select(fixture.candidates(), m10, 0, ELITE).selected());
        assertEquals(1,
                CandidatePortfolioSelector.select(fixture.candidates(), m10, 1, ELITE)
                        .selected().size());
    }

    @Test
    void perStateCandidateCountNeverExceedsTheProductionLimit() {
        Fixture fixture = new Fixture();
        for (int index = 0; index < 12; index++) {
            fixture.add(index % 3, 10 + index, 3 + index, harvestValue(90 - index));
        }
        Comparator<TeamTargetCandidate> m10 = fixture.harvestPreference();

        for (int poolSize = 1; poolSize <= 12; poolSize++) {
            List<TeamTargetCandidate> pool = fixture.candidates().subList(0, poolSize);
            CandidatePortfolio portfolio =
                    CandidatePortfolioSelector.select(pool, m10, LIMIT, ELITE);

            assertEquals(Math.min(LIMIT, poolSize), portfolio.selected().size(),
                    "A state must retain exactly min(limit, candidates) branches");
            assertTrue(portfolio.selected().size() <= LIMIT);
            assertEquals(portfolio.selected().size(),
                    new LinkedHashSet<>(portfolio.selected()).size(),
                    "The portfolio must never duplicate a candidate to fill a diversity slot");
            assertTrue(portfolio.eliteSelected() * 2 >= portfolio.selected().size());
            assertEquals(pool.stream().min(m10).orElseThrow(), portfolio.selected().get(0));
        }
    }

    private static Set<Integer> agentsOf(CandidatePortfolio portfolio) {
        return portfolio.selected().stream()
                .map(candidate -> candidate.patrolAgentId().value())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<Integer> targetsOf(CandidatePortfolio portfolio) {
        return portfolio.selected().stream()
                .map(candidate -> candidate.targetPosition().value())
                .toList();
    }

    /** Route attribution whose only distinguishing key is the intent-adjusted score. */
    private static IntentRouteMetrics harvestValue(int adjustedScore) {
        return new IntentRouteMetrics(1, adjustedScore, 1, 0, 0, 0, Set.of(new BrandId("A")), 0);
    }

    /** Route attribution that adds a brand whose forecast stock still remains at our arrival. */
    private static IntentRouteMetrics newRealizableBrandValue(int adjustedScore) {
        return new IntentRouteMetrics(1, adjustedScore, 1, 0, 0, 0, Set.of(new BrandId("D")), 1);
    }

    /**
     * Candidate pool plus its branch-local intent attribution, exposing the production comparators.
     */
    private static final class Fixture {

        private final List<TeamTargetCandidate> candidates = new ArrayList<>();
        private final Map<TeamTargetCandidate, IntentRouteMetrics> intent = new LinkedHashMap<>();

        TeamTargetCandidate add(int agent, int target, int steps, IntentRouteMetrics metrics) {
            Position start = new Position(0);
            Position goal = new Position(target);
            TeamTargetCandidate candidate = new TeamTargetCandidate(
                    new AgentId(agent),
                    goal,
                    new BrandId("A"),
                    new Route(start, goal, List.of(Direction.RIGHT), steps, 1),
                    steps,
                    1,
                    true,
                    metrics.forecastRealizableBrandGain() > 0,
                    metrics.projectedCollectionGain(),
                    20);
            candidates.add(candidate);
            intent.put(candidate, metrics);
            return candidate;
        }

        List<TeamTargetCandidate> candidates() {
            return List.copyOf(candidates);
        }

        Comparator<TeamTargetCandidate> harvestPreference() {
            return Comparator.comparing(this::metrics, IntentAwareCandidateMetrics.harvestPreference());
        }

        Comparator<TeamTargetCandidate> coveragePreference() {
            return Comparator.comparing(this::metrics, IntentAwareCandidateMetrics.coveragePreference());
        }

        /** Exactly the mapping {@code AnytimeTeamPlanner} applies for intent-aware policies. */
        private IntentAwareCandidateMetrics metrics(TeamTargetCandidate candidate) {
            IntentRouteMetrics metrics = intent.getOrDefault(candidate, IntentRouteMetrics.empty());
            return new IntentAwareCandidateMetrics(
                    metrics.forecastRealizableBrandGain() > 0,
                    candidate.newBrandForTeamToday(),
                    metrics.adjustedScore(),
                    metrics.forecastRealizableCollections(),
                    metrics.forecastRealizableBrandGain(),
                    candidate.projectedCollectionGain(),
                    metrics.likelyClaimedFirstCollections(),
                    metrics.tieCollections(),
                    candidate.routeSteps(),
                    candidate.routeFuel(),
                    candidate.resultingFuel(),
                    candidate.targetPosition(),
                    candidate.patrolAgentId());
        }
    }
}
