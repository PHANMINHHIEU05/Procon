package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.planner.StrategicDiversityKey.AgentOpening;
import vn.ptit.procon.planner.StrategyStageScheduler.Decision;
import vn.ptit.procon.planner.StrategyStageScheduler.Stage;

/**
 * Three-stage expansion scheduling of the M11 strategy-depth correction.
 *
 * <p>Every fixture drives the production scheduler over the production frontier at the production
 * stage allocation. States are {@code (score, strategy)} pairs and every expansion regrows its own
 * strategy with slightly weaker children, which reproduces the live pathology: one dominant
 * opening always owns the globally best state, so a pure quality-first search never leaves it.</p>
 */
class StrategyStageSchedulerTest {

    private static final AnytimePlannerConfig PRODUCTION = AnytimePlannerConfig.defaults();
    private static final StratifiedSearchConfig STAGES = StratifiedSearchConfig.defaults();

    /** Lower score is stronger, mirroring the engine's "first is best" frontier ordering. */
    private static final Comparator<Node> BY_SCORE = Comparator.comparingInt(Node::score)
            .thenComparingInt(Node::serial);

    /** Dominant opening: its seed and every descendant outranks all other openings. */
    private static final StrategicDiversityKey DOMINANT = opening(10);

    @Test
    void strategyOwningTheGloballyBestStateAlwaysQualifies() {
        Run run = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(20), key -> 2);

        assertTrue(run.discovered() > STAGES.maxQualifiedStrategies(),
                "The fixture must discover more strategies than qualification can admit");
        assertEquals(DOMINANT, run.bestAfterDiscovery());
        assertTrue(run.scheduler().qualifiedStrategies().contains(run.bestAfterDiscovery()),
                "The strategy holding the globally best frontier state must always qualify");
        assertEquals(run.bestAfterDiscovery(),
                run.scheduler().qualifiedStrategies().iterator().next(),
                "The elite guarantee is applied before the ranked portfolio");
    }

    @Test
    void qualificationSetStaysBoundedNoMatterHowManyStrategiesExist() {
        Run run = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(20), key -> 2);
        Set<StrategicDiversityKey> qualified = run.scheduler().qualifiedStrategies();

        assertTrue(run.discovered() >= 20);
        assertEquals(STAGES.maxQualifiedStrategies(), qualified.size());
        assertTrue(qualified.size() <= STAGES.maxQualifiedStrategies());
        assertTrue(run.rankedAfterDiscovery().containsAll(qualified),
                "Qualification may only admit strategies discovery actually reached");
    }

    @Test
    void onlyTheStrongestBoundedSubsetOfManyWeakStrategiesQualifies() {
        Run run = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(20), key -> 2);

        assertEquals(
                run.rankedAfterDiscovery().subList(0, STAGES.maxQualifiedStrategies()),
                List.copyOf(run.scheduler().qualifiedStrategies()),
                "Qualification must be the ranked prefix under the unchanged frontier comparator");
    }

    @Test
    void fewerThanEightStrategiesAllQualify() {
        Run run = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(3), key -> 2);

        assertEquals(3, run.discovered());
        assertEquals(3, run.scheduler().qualifiedStrategies().size());
        assertEquals(3, run.scheduler().qualifiedStrategiesMeetingMinimumDepth());
        assertEquals(Set.copyOf(run.rankedAfterDiscovery()),
                Set.copyOf(run.scheduler().qualifiedStrategies()));
    }

    @Test
    void everyQualifiedStrategyReachesMinimumDepthBeforeAnyThirdExpansion() {
        Run run = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(5), key -> 2);
        Set<StrategicDiversityKey> qualified = run.scheduler().qualifiedStrategies();
        Map<StrategicDiversityKey, Integer> depth = new TreeMap<>();

        assertEquals(5, qualified.size());
        for (Decision<Node> decision : run.decisions()) {
            if (decision.stage() != Stage.QUALIFICATION) {
                continue;
            }
            int reached = depth.merge(decision.strategy(), 1, Integer::sum);
            if (reached == 3) {
                for (StrategicDiversityKey key : qualified) {
                    assertTrue(depth.getOrDefault(key, 0)
                                    >= STAGES.minimumQualificationExpansionsPerStrategy(),
                            "Strategy " + key + " must reach minimum depth before any third");
                }
            }
        }
        assertEquals(5, run.scheduler().qualifiedStrategiesMeetingMinimumDepth());
        assertEquals(0, run.scheduler().qualifiedStrategiesExhaustedBeforeMinimum());
    }

    @Test
    void exhaustedQualifiedStrategyIsSkippedInsteadOfWastingBudget() {
        // Eight strategies qualify, but one of them is a dead end: its single available state
        // produces no successors, so its remaining minimum-depth obligation can never be paid.
        StrategicDiversityKey deadEnd = opening(80);
        Run run = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(8),
                key -> key.equals(deadEnd) ? 0 : 2);

        assertTrue(run.scheduler().qualifiedStrategies().contains(deadEnd),
                "The dead end still has a state at the end of discovery, so it qualifies");
        assertFalse(run.frontier().hasStates(deadEnd), "The dead end must be retired, not pruned");
        assertEquals(1, run.frontier().expansionCount(deadEnd),
                "A retired strategy is never expanded again, and never expanded twice");
        assertEquals(1, run.scheduler().qualificationExpansions(deadEnd));
        assertEquals(1, run.scheduler().qualifiedStrategiesExhaustedBeforeMinimum());
        assertEquals(STAGES.maxQualifiedStrategies() - 1,
                run.scheduler().qualifiedStrategiesMeetingMinimumDepth(),
                "Every other qualified strategy still reaches minimum depth");
        assertEquals(PRODUCTION.maxExpandedStates(), run.scheduler().totalExpansions(),
                "The unused obligation must be reassigned, never left idle");
        assertEquals(STAGES.discoveryBudget(), run.scheduler().discoveryExpansions());
        assertEquals(STAGES.qualificationBudget(), run.scheduler().qualificationExpansions());
        assertEquals(STAGES.exploitationBudget(), run.scheduler().exploitationExpansions());
    }

    @Test
    void dominantStrategyCannotConsumeTheQualificationStage() {
        Run corrected = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(20), key -> 2);
        // Pure quality-first analogue of the pre-correction schedule: one stage, no fairness.
        StratifiedSearchConfig qualityOnly = new StratifiedSearchConfig(
                0, 0, PRODUCTION.maxExpandedStates(), 8, 2, 6, 24, 2, 2);
        Run qualityFirst = run(qualityOnly, PRODUCTION.maxExpandedStates(), seeds(20), key -> 2);

        assertEquals(PRODUCTION.maxExpandedStates(),
                qualityFirst.frontier().expansionCount(DOMINANT),
                "Quality-first alone lets the dominant strategy absorb the whole budget");

        int dominantQualification = corrected.scheduler().qualificationExpansions(DOMINANT);
        int obligations = STAGES.minimumQualificationExpansionsPerStrategy()
                * (STAGES.maxQualifiedStrategies() - 1);
        assertTrue(dominantQualification <= STAGES.qualificationBudget() - obligations,
                "Qualification obligations of the other strategies are untouchable");
        assertEquals(STAGES.maxQualifiedStrategies(),
                corrected.scheduler().qualifiedStrategiesMeetingMinimumDepth());
        assertTrue(corrected.frontier().expansionCount(DOMINANT) < PRODUCTION.maxExpandedStates());
        assertTrue(corrected.qualificationStrategies().size() >= STAGES.maxQualifiedStrategies());
    }

    @Test
    void exploitationDropsFairnessAndLetsTheStrongestStrategyDominateAgain() {
        Run run = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(20), key -> 2);
        List<Decision<Node>> exploitation = run.decisions().stream()
                .filter(decision -> decision.stage() == Stage.EXPLOITATION)
                .toList();

        assertEquals(STAGES.exploitationBudget(), exploitation.size());
        assertTrue(exploitation.stream().allMatch(decision -> decision.strategy().equals(DOMINANT)),
                "Exploitation must be pure global quality-first again");
        assertTrue(exploitation.stream().noneMatch(Decision::obligation));
        assertTrue(exploitation.stream().noneMatch(Decision::diversityTurn));
    }

    @Test
    void singleStrategyReducesToQualityFirstWithoutArtificialDelay() {
        Run run = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(1), key -> 2);

        assertEquals(1, run.discovered());
        assertEquals(Set.of(DOMINANT), run.scheduler().qualifiedStrategies());
        assertEquals(PRODUCTION.maxExpandedStates(), run.decisions().size());
        for (int index = 0; index < run.decisions().size(); index++) {
            assertSame(run.peeks().get(index), run.decisions().get(index).state(),
                    "Every expansion must take the globally best state when only one exists");
        }
        assertTrue(run.decisions().stream().noneMatch(Decision::diversityTurn),
                "One opening leaves nothing to diversify over");
    }

    @Test
    void stageCountersComeFromActualExpansionsAndSumToTheBudget() {
        Run run = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(20), key -> 2);
        StrategyStageScheduler<Node> scheduler = run.scheduler();

        assertEquals(STAGES.discoveryBudget(), scheduler.discoveryExpansions());
        assertEquals(STAGES.qualificationBudget(), scheduler.qualificationExpansions());
        assertEquals(STAGES.exploitationBudget(), scheduler.exploitationExpansions());
        assertEquals(PRODUCTION.maxExpandedStates(), scheduler.totalExpansions());
        assertEquals(run.decisions().size(), scheduler.totalExpansions());
        assertEquals(scheduler.totalExpansions(),
                run.frontier().expansionCountsByStrategy().values().stream()
                        .mapToInt(Integer::intValue).sum());
        assertEquals(run.decisions().size(), identityCount(run.decisions()),
                "A state may be expanded at most once");
        assertTrue(run.frontier().maxStrategyExpansionCount() <= scheduler.totalExpansions());
    }

    @Test
    void anEmptyFrontierEndsTheSearchInsteadOfFabricatingWork() {
        Run run = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(5), key -> 0);

        assertEquals(5, run.decisions().size(), "Only the five seeds could ever be expanded");
        assertEquals(5, run.scheduler().totalExpansions());
        assertEquals(5, run.scheduler().discoveryExpansions());
        assertEquals(0, run.scheduler().qualificationExpansions());
        assertEquals(0, run.scheduler().exploitationExpansions());
        assertTrue(run.frontier().isEmpty());
        assertNull(run.scheduler().next(run.frontier()));
    }

    @Test
    void repeatedRunsScheduleIdenticalStagesStrategiesAndStates() {
        Run first = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(20), key -> 2);
        Run second = run(STAGES, PRODUCTION.maxExpandedStates(), seeds(20), key -> 2);

        assertEquals(signature(first), signature(second));
        assertEquals(List.copyOf(first.scheduler().qualifiedStrategies()),
                List.copyOf(second.scheduler().qualifiedStrategies()));
        assertEquals(first.frontier().expansionCountsByStrategy(),
                second.frontier().expansionCountsByStrategy());
        assertEquals(first.scheduler().discoveryExpansions(),
                second.scheduler().discoveryExpansions());
        assertEquals(first.scheduler().qualificationExpansions(),
                second.scheduler().qualificationExpansions());
        assertEquals(first.scheduler().exploitationExpansions(),
                second.scheduler().exploitationExpansions());
    }

    private static List<String> signature(Run run) {
        return run.decisions().stream()
                .map(decision -> decision.stage() + "|" + decision.strategy() + "|"
                        + decision.state().score() + "|" + decision.obligation() + "|"
                        + decision.diversityTurn())
                .toList();
    }

    /** Seed states for {@code count} strategies, the first one dominating every other. */
    private static List<Node> seeds(int count) {
        List<Node> seeds = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            seeds.add(new Node(index * 100, index, opening(10 + index * 10)));
        }
        return seeds;
    }

    /**
     * Drives the production scheduler until the budget is spent or the frontier empties, growing
     * each expanded strategy by {@code children} slightly weaker successors.
     */
    private static Run run(
            StratifiedSearchConfig config,
            int budget,
            List<Node> seeds,
            ToIntFunction<StrategicDiversityKey> children) {
        StratifiedFrontier<Node> frontier = new StratifiedFrontier<>(
                PRODUCTION.maxFrontierSize(),
                config.globalEliteSlots(PRODUCTION.maxFrontierSize()),
                BY_SCORE,
                Node::strategy);
        StrategyStageScheduler<Node> scheduler = new StrategyStageScheduler<>(config);
        seeds.forEach(frontier::add);

        List<Decision<Node>> decisions = new ArrayList<>();
        List<Node> peeks = new ArrayList<>();
        List<StrategicDiversityKey> ranked = List.of();
        StrategicDiversityKey best = null;
        int serial = 1_000;
        while (scheduler.totalExpansions() < budget) {
            if (best == null
                    && scheduler.discoveryExpansions() >= config.discoveryBudget()
                    && !frontier.isEmpty()) {
                best = frontier.bestStrategy();
                ranked = frontier.rankedStrategies();
            }
            peeks.add(frontier.isEmpty() ? null : frontier.head(frontier.bestStrategy()));
            Decision<Node> decision = scheduler.next(frontier);
            if (decision == null) {
                peeks.remove(peeks.size() - 1);
                break;
            }
            decisions.add(decision);
            int childCount = children.applyAsInt(decision.strategy());
            for (int child = 0; child < childCount; child++) {
                frontier.add(new Node(
                        decision.state().score() + 1 + child, serial++, decision.strategy()));
            }
        }
        return new Run(scheduler, frontier, List.copyOf(decisions), List.copyOf(peeks), ranked, best);
    }

    private static int identityCount(List<Decision<Node>> decisions) {
        Set<Node> distinct = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        decisions.forEach(decision -> distinct.add(decision.state()));
        return distinct.size();
    }

    private static StrategicDiversityKey opening(int firstTarget) {
        return StrategicDiversityKey.of(List.of(new AgentOpening(0, firstTarget)));
    }

    /** One completed scheduler run, with everything a fixture needs to assert about it. */
    private record Run(
            StrategyStageScheduler<Node> scheduler,
            StratifiedFrontier<Node> frontier,
            List<Decision<Node>> decisions,
            List<Node> peeks,
            List<StrategicDiversityKey> rankedAfterDiscovery,
            StrategicDiversityKey bestAfterDiscovery) {

        private int discovered() {
            return frontier.strategyBucketsSeen();
        }

        private Set<StrategicDiversityKey> qualificationStrategies() {
            Set<StrategicDiversityKey> strategies = new LinkedHashSet<>();
            decisions.stream()
                    .filter(decision -> decision.stage() == Stage.QUALIFICATION)
                    .forEach(decision -> strategies.add(decision.strategy()));
            return strategies;
        }
    }

    private record Node(int score, int serial, StrategicDiversityKey strategy) {
    }
}
