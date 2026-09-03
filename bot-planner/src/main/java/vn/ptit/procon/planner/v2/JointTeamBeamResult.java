package vn.ptit.procon.planner.v2;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.engine.TeamPlan;

/** Complete result of one Planner V2 search. */
public record JointTeamBeamResult(
        TeamPlan plan,
        JointTerminalEvaluation evaluation,
        JointTeamBeamStats stats,
        List<JointBeamDepthStats> depthSummaries,
        List<JointTerminalPortfolioEntry> terminalPortfolio,
        V2CollectionSearchAudit collectionAudit,
        CompetitiveSearchAudit competitiveAudit,
        StageBRecallAudit stageBRecallAudit,
        TerminalObjectiveAudit terminalObjectiveAudit) {

    public JointTeamBeamResult(
            TeamPlan plan,
            JointTerminalEvaluation evaluation,
            JointTeamBeamStats stats) {
        this(plan, evaluation, stats, List.of(), List.of(), V2CollectionSearchAudit.empty(),
                CompetitiveSearchAudit.empty(CompetitiveTargetPolicy.CURRENT),
                StageBRecallAudit.empty(StageBRecallAuditMode.OFF),
                TerminalObjectiveAudit.empty(StageBRecallAuditMode.OFF));
    }

    public JointTeamBeamResult(
            TeamPlan plan,
            JointTerminalEvaluation evaluation,
            JointTeamBeamStats stats,
            List<JointBeamDepthStats> depthSummaries,
            List<JointTerminalPortfolioEntry> terminalPortfolio) {
        this(plan, evaluation, stats, depthSummaries, terminalPortfolio, V2CollectionSearchAudit.empty(),
                CompetitiveSearchAudit.empty(CompetitiveTargetPolicy.CURRENT),
                StageBRecallAudit.empty(StageBRecallAuditMode.OFF),
                TerminalObjectiveAudit.empty(StageBRecallAuditMode.OFF));
    }

    public JointTeamBeamResult {
        Objects.requireNonNull(plan, "Plan must not be null");
        Objects.requireNonNull(evaluation, "Evaluation must not be null");
        Objects.requireNonNull(stats, "Statistics must not be null");
        Objects.requireNonNull(collectionAudit, "Collection audit must not be null");
        Objects.requireNonNull(competitiveAudit, "Competitive audit must not be null");
        Objects.requireNonNull(stageBRecallAudit, "Stage-B recall audit must not be null");
        Objects.requireNonNull(terminalObjectiveAudit, "Terminal objective audit must not be null");
        depthSummaries = List.copyOf(depthSummaries);
        terminalPortfolio = List.copyOf(terminalPortfolio);
    }
}
