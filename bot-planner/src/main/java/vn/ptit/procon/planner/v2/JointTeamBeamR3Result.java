package vn.ptit.procon.planner.v2;

import java.util.Objects;
import vn.ptit.procon.engine.TeamPlan;

/** Result of the opt-in R3 bounded support portfolio planner. */
public record JointTeamBeamR3Result(TeamPlan plan, JointTeamBeamResult beamResult, R3PlanningStats stats,
        R3RootFamilyAudit rootFamilyAudit) {
    public JointTeamBeamR3Result(TeamPlan plan, JointTeamBeamResult beamResult, R3PlanningStats stats) {
        this(plan, beamResult, stats, R3RootFamilyAudit.empty());
    }

    public JointTeamBeamR3Result {
        Objects.requireNonNull(plan, "Plan must not be null");
        Objects.requireNonNull(beamResult, "Beam result must not be null");
        Objects.requireNonNull(stats, "Statistics must not be null");
        Objects.requireNonNull(rootFamilyAudit, "Root-family audit must not be null");
    }
}
