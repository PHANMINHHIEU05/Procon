package vn.ptit.procon.planner.v2;

import java.util.List;
import java.util.Objects;

/** Immutable bounded diagnostics for competitive target generation. */
public record CompetitiveSearchAudit(
        CompetitiveTargetPolicy policy,
        List<CompetitiveTargetDecision> decisions,
        int winningTargetsTotal,
        int winningTargetsPresentInProductionPortfolio,
        double winningTargetRecall,
        int rawPlannedCollections,
        int semiCollections,
        int coupledOwnCollections,
        int uncontestedWon,
        int raceWon,
        int stockShared,
        int ties,
        int lostToOpponent) {
    public CompetitiveSearchAudit {
        Objects.requireNonNull(policy, "Competitive policy must not be null");
        decisions = List.copyOf(Objects.requireNonNull(decisions, "Decisions must not be null"));
        if (winningTargetsTotal < 0 || winningTargetsPresentInProductionPortfolio < 0
                || winningTargetsPresentInProductionPortfolio > winningTargetsTotal
                || rawPlannedCollections < 0 || semiCollections < 0 || coupledOwnCollections < 0
                || uncontestedWon < 0 || raceWon < 0 || stockShared < 0 || ties < 0 || lostToOpponent < 0) {
            throw new IllegalArgumentException("Competitive audit values must be non-negative");
        }
    }

    public static CompetitiveSearchAudit empty(CompetitiveTargetPolicy policy) {
        return new CompetitiveSearchAudit(policy, List.of(), 0, 0, 1.0,
                0, 0, 0, 0, 0, 0, 0, 0);
    }
}
