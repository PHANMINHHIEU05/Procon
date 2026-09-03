package vn.ptit.procon.planner.v2;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.planner.CoupledOwnEventResult;

/** Package-private collector for bounded target and conversion diagnostics. */
final class CompetitiveAuditAccumulator {
    private final CompetitiveTargetPolicy policy;
    private final List<CompetitiveTargetDecision> decisions = new ArrayList<>();

    CompetitiveAuditAccumulator(CompetitiveTargetPolicy policy) {
        this.policy = policy;
    }

    void decision(AgentId agent, int candidateCount, List<CompetitiveTargetChoice> selected) {
        decisions.add(new CompetitiveTargetDecision(agent, candidateCount, selected));
    }

    CompetitiveSearchAudit complete(JointTerminalEvaluator.StageATerminalCandidate candidate,
            JointTerminalEvaluation evaluation) {
        Set<Position> winning = new LinkedHashSet<>();
        for (CoupledOwnEventResult event : evaluation.hybrid().coupled().ownEventResults()) {
            if (event.collected()) winning.add(event.event().spot());
        }
        Set<Position> portfolio = new LinkedHashSet<>();
        decisions.stream().flatMap(value -> value.selected().stream())
                .map(CompetitiveTargetChoice::position).forEach(portfolio::add);
        int present = (int) winning.stream().filter(portfolio::contains).count();
        int uncontested = 0, raceWon = 0, shared = 0, ties = 0, lost = 0;
        for (CoupledOwnEventResult event : evaluation.hybrid().coupled().ownEventResults()) {
            if (event.collected()) {
                if (event.equalStepContest()) ties++;
                else if (evaluation.hybrid().coupled().coupledOpponentClaims().stream()
                        .noneMatch(claim -> claim.spot().equals(event.event().spot()))) uncontested++;
                else { raceWon++; shared++; }
            } else if (event.invalidatedByOpponent()) {
                if (event.equalStepContest()) ties++;
                else lost++;
            }
        }
        return new CompetitiveSearchAudit(policy, decisions, winning.size(), present,
                winning.isEmpty() ? 1.0 : (double) present / winning.size(),
                evaluation.hybrid().plannedOwnOpportunityEvents(),
                evaluation.hybrid().ownSemiCollections(), evaluation.hybrid().coupledOwnCollections(),
                uncontested, raceWon, shared, ties, lost);
    }
}
