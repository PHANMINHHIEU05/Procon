package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic three-stage expansion scheduler for the M11 strategy-depth correction.
 *
 * <p>Discovery spends its share the way M11 already did, mixing quality expansions with
 * diversity expansions so a portfolio of opening strategies becomes visible. Qualification then
 * takes a bounded subset of those strategies and guarantees each of them enough depth to prove
 * whether it leads anywhere, instead of letting the single strongest opening absorb the budget.
 * Exploitation drops every fairness rule and is pure quality-first again.</p>
 *
 * <p>The scheduler records an expansion at the moment it hands a state out, so the three stage
 * counters always sum to the number of expansions the search performed. Nothing here scores a
 * strategy: ranking is always the caller's unchanged frontier comparator.</p>
 *
 * @param <T> search state type
 */
final class StrategyStageScheduler<T> {

    /** Search stage that paid for one expansion. */
    enum Stage {
        DISCOVERY,
        QUALIFICATION,
        EXPLOITATION
    }

    /**
     * One expansion handed out by the scheduler.
     *
     * @param stage stage whose budget paid for it
     * @param state state to expand
     * @param strategy strategy key that state belongs to
     * @param diversityTurn whether a discovery diversity turn selected it
     * @param obligation whether a minimum-depth qualification obligation selected it
     */
    record Decision<T>(
            Stage stage,
            T state,
            StrategicDiversityKey strategy,
            boolean diversityTurn,
            boolean obligation) {
    }

    private final StratifiedSearchConfig config;

    /** Qualified strategies in the order they were ranked at the end of discovery. */
    private final Set<StrategicDiversityKey> qualified = new LinkedHashSet<>();

    /** Qualification expansions per strategy, in deterministic key order. */
    private final Map<StrategicDiversityKey, Integer> qualificationByStrategy = new TreeMap<>();

    /** Qualified strategies observed with no expandable state while still below minimum depth. */
    private final Set<StrategicDiversityKey> observedExhausted = new TreeSet<>();

    private int discoveryExpansions;
    private int qualificationExpansions;
    private int exploitationExpansions;
    private int discoveryQualityStreak;
    private boolean portfolioSelected;

    StrategyStageScheduler(StratifiedSearchConfig config) {
        this.config = Objects.requireNonNull(config, "Stratified search configuration must not be null");
    }

    /**
     * Hands out the next expansion, or {@code null} when the frontier has nothing left.
     *
     * <p>Only a state actually handed out consumes stage budget, so an empty frontier ends the
     * search normally instead of fabricating work to fill the remaining stages.</p>
     */
    Decision<T> next(StratifiedFrontier<T> frontier) {
        Objects.requireNonNull(frontier, "Frontier must not be null");
        if (frontier.isEmpty()) {
            return null;
        }
        if (discoveryExpansions < config.discoveryBudget()) {
            return discovery(frontier);
        }
        selectPortfolio(frontier);
        if (qualificationExpansions < config.qualificationBudget()) {
            return qualification(frontier);
        }
        return exploitation(frontier);
    }

    /** Qualified portfolio, in the rank order chosen at the end of discovery. */
    Set<StrategicDiversityKey> qualifiedStrategies() {
        return Collections.unmodifiableSet(qualified);
    }

    int qualificationExpansions(StrategicDiversityKey key) {
        return qualificationByStrategy.getOrDefault(key, 0);
    }

    int discoveryExpansions() {
        return discoveryExpansions;
    }

    int qualificationExpansions() {
        return qualificationExpansions;
    }

    int exploitationExpansions() {
        return exploitationExpansions;
    }

    int totalExpansions() {
        return discoveryExpansions + qualificationExpansions + exploitationExpansions;
    }

    /** Qualified strategies that reached the configured minimum qualification depth. */
    int qualifiedStrategiesMeetingMinimumDepth() {
        return (int) qualified.stream()
                .filter(key -> qualificationExpansions(key)
                        >= config.minimumQualificationExpansionsPerStrategy())
                .count();
    }

    /**
     * Qualified strategies that ran out of expandable states before reaching minimum depth. Their
     * unused obligation was reassigned to other strategies rather than left idle.
     */
    int qualifiedStrategiesExhaustedBeforeMinimum() {
        return (int) qualified.stream()
                .filter(observedExhausted::contains)
                .filter(key -> qualificationExpansions(key)
                        < config.minimumQualificationExpansionsPerStrategy())
                .count();
    }

    private Decision<T> discovery(StratifiedFrontier<T> frontier) {
        boolean diversityTurn = discoveryQualityStreak
                        >= config.discoveryQualityExpansionsPerDiversityExpansion()
                && frontier.diversityAvailable();
        T state = frontier.poll(diversityTurn);
        if (diversityTurn) {
            discoveryQualityStreak = 0;
        } else {
            discoveryQualityStreak++;
        }
        discoveryExpansions++;
        return decision(Stage.DISCOVERY, state, frontier, diversityTurn, false);
    }

    private Decision<T> qualification(StratifiedFrontier<T> frontier) {
        List<StrategicDiversityKey> obligations = new ArrayList<>();
        int belowMinimum = 0;
        for (StrategicDiversityKey key : qualified) {
            if (qualificationExpansions(key) >= config.minimumQualificationExpansionsPerStrategy()) {
                continue;
            }
            belowMinimum++;
            if (frontier.hasStates(key)) {
                obligations.add(key);
            } else {
                // Soft feasibility: nothing to expand right now, so the obligation is skipped
                // instead of wasting a slot. The strategy stays qualified and may return later.
                observedExhausted.add(key);
            }
        }
        if (!obligations.isEmpty()) {
            StrategicDiversityKey chosen = obligations.stream()
                    .min(Comparator
                            .comparingInt((StrategicDiversityKey key) -> qualificationExpansions(key))
                            .thenComparing(frontier::head, frontier.preference())
                            .thenComparing(Comparator.naturalOrder()))
                    .orElseThrow();
            return qualificationExpansion(frontier, chosen, true);
        }

        List<StrategicDiversityKey> live = qualified.stream()
                .filter(frontier::hasStates)
                .toList();
        if (live.isEmpty()) {
            // No qualified strategy is expandable: spend the slot on global quality rather than
            // stalling, and let the frontier decide which branch that is.
            T state = frontier.poll(false);
            return qualificationExpansion(frontier, state, false);
        }
        boolean anyBelowMinimum = belowMinimum > 0;
        List<StrategicDiversityKey> selectable = live.stream()
                .filter(key -> !config.qualificationCapBlocks(
                        qualificationExpansions(key), anyBelowMinimum))
                .toList();
        if (selectable.isEmpty()) {
            selectable = live;
        }
        StrategicDiversityKey chosen = selectable.stream()
                .min(Comparator
                        .<StrategicDiversityKey, T>comparing(frontier::head, frontier.preference())
                        .thenComparing(Comparator.naturalOrder()))
                .orElseThrow();
        return qualificationExpansion(frontier, chosen, false);
    }

    private Decision<T> exploitation(StratifiedFrontier<T> frontier) {
        T state = frontier.poll(false);
        exploitationExpansions++;
        return decision(Stage.EXPLOITATION, state, frontier, false, false);
    }

    private Decision<T> qualificationExpansion(
            StratifiedFrontier<T> frontier, StrategicDiversityKey key, boolean obligation) {
        return qualificationExpansion(frontier, frontier.pollStrategy(key), obligation);
    }

    private Decision<T> qualificationExpansion(
            StratifiedFrontier<T> frontier, T state, boolean obligation) {
        qualificationExpansions++;
        Decision<T> decision = decision(Stage.QUALIFICATION, state, frontier, false, obligation);
        qualificationByStrategy.merge(decision.strategy(), 1, Integer::sum);
        return decision;
    }

    /**
     * Freezes the qualified portfolio once discovery is over: strategies ranked by their best
     * currently available state under the unchanged frontier comparator, capped at the configured
     * maximum, and always including the strategy that owns the globally best state.
     */
    private void selectPortfolio(StratifiedFrontier<T> frontier) {
        if (portfolioSelected) {
            return;
        }
        portfolioSelected = true;
        StrategicDiversityKey elite = frontier.bestStrategy();
        if (elite != null) {
            qualified.add(elite);
        }
        for (StrategicDiversityKey key : frontier.rankedStrategies()) {
            if (qualified.size() >= config.maxQualifiedStrategies()) {
                break;
            }
            qualified.add(key);
        }
        frontier.markQualified(qualified);
    }

    private Decision<T> decision(
            Stage stage,
            T state,
            StratifiedFrontier<T> frontier,
            boolean diversityTurn,
            boolean obligation) {
        return new Decision<>(stage, state, frontier.strategyOf(state), diversityTurn, obligation);
    }
}
