package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * Deterministic elite-plus-diversity candidate portfolio for one expanded state.
 *
 * <p>The strongest candidates under the unchanged M10 candidate comparator are always kept as
 * elite. The remaining slots are offered to candidates that add a new acting PATROL agent or a
 * new target spot, so a single expanded state cannot spend its whole per-state capacity on
 * variations of one strategic move. The M10 comparator remains the tie-break inside every
 * novelty class, so the selection is fully deterministic and never randomised.</p>
 */
final class CandidatePortfolioSelector {

    private CandidatePortfolioSelector() {
    }

    /**
     * Selects at most {@code limit} candidates, reserving the first {@code eliteSlots} for the
     * globally strongest candidates and filling the rest by deterministic novelty.
     */
    static CandidatePortfolio select(
            List<TeamTargetCandidate> candidates,
            Comparator<TeamTargetCandidate> preference,
            int limit,
            int eliteSlots) {
        Objects.requireNonNull(candidates, "Candidates must not be null");
        Objects.requireNonNull(preference, "Candidate preference must not be null");
        if (limit <= 0 || candidates.isEmpty()) {
            return CandidatePortfolio.empty();
        }
        List<TeamTargetCandidate> ordered = candidates.stream().sorted(preference).toList();
        int capacity = Math.min(limit, ordered.size());
        int elite = Math.max(1, Math.min(eliteSlots, capacity));

        List<TeamTargetCandidate> selected = new ArrayList<>(ordered.subList(0, elite));
        Set<AgentId> representedAgents = new LinkedHashSet<>();
        Set<Position> representedTargets = new LinkedHashSet<>();
        selected.forEach(candidate -> {
            representedAgents.add(candidate.patrolAgentId());
            representedTargets.add(candidate.targetPosition());
        });

        List<TeamTargetCandidate> pool = new ArrayList<>(ordered.subList(elite, ordered.size()));
        int diverse = 0;
        while (selected.size() < capacity && !pool.isEmpty()) {
            TeamTargetCandidate chosen = mostNovel(pool, representedAgents, representedTargets);
            selected.add(chosen);
            pool.remove(chosen);
            representedAgents.add(chosen.patrolAgentId());
            representedTargets.add(chosen.targetPosition());
            diverse++;
        }
        return new CandidatePortfolio(List.copyOf(selected), elite, diverse);
    }

    /**
     * Picks the first candidate of the strongest novelty class. The pool is already ordered by
     * the M10 comparator, so that comparator decides ties inside a novelty class.
     */
    private static TeamTargetCandidate mostNovel(
            List<TeamTargetCandidate> pool,
            Set<AgentId> representedAgents,
            Set<Position> representedTargets) {
        TeamTargetCandidate chosen = pool.get(0);
        int chosenClass = noveltyClass(chosen, representedAgents, representedTargets);
        for (TeamTargetCandidate candidate : pool) {
            int novelty = noveltyClass(candidate, representedAgents, representedTargets);
            if (novelty < chosenClass) {
                chosenClass = novelty;
                chosen = candidate;
            }
            if (chosenClass == NEW_AGENT_AND_TARGET) {
                break;
            }
        }
        return chosen;
    }

    private static final int NEW_AGENT_AND_TARGET = 0;
    private static final int NEW_TARGET = 1;
    private static final int NEW_AGENT = 2;
    private static final int ALREADY_REPRESENTED = 3;

    private static int noveltyClass(
            TeamTargetCandidate candidate,
            Set<AgentId> representedAgents,
            Set<Position> representedTargets) {
        boolean newAgent = !representedAgents.contains(candidate.patrolAgentId());
        boolean newTarget = !representedTargets.contains(candidate.targetPosition());
        if (newAgent && newTarget) {
            return NEW_AGENT_AND_TARGET;
        }
        if (newTarget) {
            return NEW_TARGET;
        }
        return newAgent ? NEW_AGENT : ALREADY_REPRESENTED;
    }

    /** Candidates retained for one expanded state, split into their elite and diverse lanes. */
    record CandidatePortfolio(
            List<TeamTargetCandidate> selected, int eliteSelected, int diverseSelected) {

        CandidatePortfolio {
            Objects.requireNonNull(selected, "Selected candidates must not be null");
            selected = List.copyOf(selected);
            if (eliteSelected < 0 || diverseSelected < 0) {
                throw new IllegalArgumentException("Portfolio lane sizes must be non-negative");
            }
            if (eliteSelected + diverseSelected != selected.size()) {
                throw new IllegalArgumentException("Portfolio lanes must account for every candidate");
            }
        }

        static CandidatePortfolio empty() {
            return new CandidatePortfolio(List.of(), 0, 0);
        }
    }
}
