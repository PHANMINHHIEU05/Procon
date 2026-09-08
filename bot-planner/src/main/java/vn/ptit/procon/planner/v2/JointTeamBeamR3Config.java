package vn.ptit.procon.planner.v2;

/** Hard work and wall-clock limits for the opt-in bounded REFUEL-tour experiment. */
public record JointTeamBeamR3Config(
        long maxPlanningMillis,
        long planningSafetyMarginMillis,
        int terminalShortlistLimit,
        int maxFullTerminalEvaluations,
        int maxDepth1Candidates,
        int maxPartialToursPerDepth,
        int maxExtensionsPerPartial,
        int maxPartialToursGenerated,
        int maxSkeletonsConsidered,
        int maxSkeletonsValidated,
        int maxSkeletonsRetained,
        R3RootFamilyAuditMode rootFamilyAuditMode,
        CompetitiveTargetPolicy competitiveTargetPolicy,
        StageBRecallAuditMode stageBRecallAuditMode) {

    public JointTeamBeamR3Config(long maxPlanningMillis, long planningSafetyMarginMillis,
            int terminalShortlistLimit, int maxFullTerminalEvaluations, int maxDepth1Candidates,
            int maxPartialToursPerDepth, int maxExtensionsPerPartial, int maxPartialToursGenerated,
            int maxSkeletonsConsidered, int maxSkeletonsValidated, int maxSkeletonsRetained) {
        this(maxPlanningMillis, planningSafetyMarginMillis, terminalShortlistLimit, maxFullTerminalEvaluations,
                maxDepth1Candidates, maxPartialToursPerDepth, maxExtensionsPerPartial, maxPartialToursGenerated,
                maxSkeletonsConsidered, maxSkeletonsValidated, maxSkeletonsRetained, R3RootFamilyAuditMode.OFF,
                CompetitiveTargetPolicy.CURRENT, StageBRecallAuditMode.OFF);
    }

    public JointTeamBeamR3Config(long maxPlanningMillis, long planningSafetyMarginMillis,
            int terminalShortlistLimit, int maxFullTerminalEvaluations, int maxDepth1Candidates,
            int maxPartialToursPerDepth, int maxExtensionsPerPartial, int maxPartialToursGenerated,
            int maxSkeletonsConsidered, int maxSkeletonsValidated, int maxSkeletonsRetained,
            R3RootFamilyAuditMode rootFamilyAuditMode) {
        this(maxPlanningMillis, planningSafetyMarginMillis, terminalShortlistLimit, maxFullTerminalEvaluations,
                maxDepth1Candidates, maxPartialToursPerDepth, maxExtensionsPerPartial, maxPartialToursGenerated,
                maxSkeletonsConsidered, maxSkeletonsValidated, maxSkeletonsRetained, rootFamilyAuditMode,
                CompetitiveTargetPolicy.CURRENT, StageBRecallAuditMode.OFF);
    }

    public JointTeamBeamR3Config(long maxPlanningMillis, long planningSafetyMarginMillis,
            int terminalShortlistLimit, int maxFullTerminalEvaluations, int maxDepth1Candidates,
            int maxPartialToursPerDepth, int maxExtensionsPerPartial, int maxPartialToursGenerated,
            int maxSkeletonsConsidered, int maxSkeletonsValidated, int maxSkeletonsRetained,
            R3RootFamilyAuditMode rootFamilyAuditMode, CompetitiveTargetPolicy competitiveTargetPolicy) {
        this(maxPlanningMillis, planningSafetyMarginMillis, terminalShortlistLimit, maxFullTerminalEvaluations,
                maxDepth1Candidates, maxPartialToursPerDepth, maxExtensionsPerPartial, maxPartialToursGenerated,
                maxSkeletonsConsidered, maxSkeletonsValidated, maxSkeletonsRetained, rootFamilyAuditMode,
                competitiveTargetPolicy, StageBRecallAuditMode.OFF);
    }

    public static JointTeamBeamR3Config defaults() {
        String policyName = System.getProperty("procon.v2.competitive_policy",
                System.getenv("PROCON_V2_COMPETITIVE_POLICY") != null ? System.getenv("PROCON_V2_COMPETITIVE_POLICY") : "CURRENT");
        CompetitiveTargetPolicy policy = CompetitiveTargetPolicy.valueOf(policyName.trim().toUpperCase());
        int stageBLimit = Integer.getInteger("procon.v2.max_stage_b",
                System.getenv("PROCON_V2_MAX_STAGE_B") != null ? Integer.parseInt(System.getenv("PROCON_V2_MAX_STAGE_B")) : 16);
        return new JointTeamBeamR3Config(8_000, 750, 64, stageBLimit, 24, 24, 4, 216, 48, 24, 12,
                R3RootFamilyAuditMode.OFF, policy, StageBRecallAuditMode.OFF);
    }

    public JointTeamBeamR3Config {
        if (maxPlanningMillis <= planningSafetyMarginMillis || planningSafetyMarginMillis < 0
                || terminalShortlistLimit <= 0 || maxFullTerminalEvaluations <= 0
                || maxDepth1Candidates <= 0 || maxPartialToursPerDepth <= 0
                || maxExtensionsPerPartial <= 0 || maxPartialToursGenerated <= 0
                || maxSkeletonsConsidered <= 0 || maxSkeletonsValidated <= 0
                || maxSkeletonsRetained <= 0 || maxSkeletonsRetained > 12
                || maxSkeletonsValidated < maxSkeletonsRetained || rootFamilyAuditMode == null
                || competitiveTargetPolicy == null || stageBRecallAuditMode == null) {
            throw new IllegalArgumentException("Invalid bounded R3 planning configuration");
        }
    }

    long usablePlanningMillis() {
        return maxPlanningMillis - planningSafetyMarginMillis;
    }

    public JointTeamBeamR3Config withRootFamilyAuditMode(R3RootFamilyAuditMode mode) {
        return new JointTeamBeamR3Config(maxPlanningMillis, planningSafetyMarginMillis, terminalShortlistLimit,
                maxFullTerminalEvaluations, maxDepth1Candidates, maxPartialToursPerDepth,
                maxExtensionsPerPartial, maxPartialToursGenerated, maxSkeletonsConsidered,
                maxSkeletonsValidated, maxSkeletonsRetained, mode, competitiveTargetPolicy,
                stageBRecallAuditMode);
    }

    public JointTeamBeamR3Config withCompetitiveTargetPolicy(CompetitiveTargetPolicy policy) {
        return new JointTeamBeamR3Config(maxPlanningMillis, planningSafetyMarginMillis,
                terminalShortlistLimit, maxFullTerminalEvaluations, maxDepth1Candidates,
                maxPartialToursPerDepth, maxExtensionsPerPartial, maxPartialToursGenerated,
                maxSkeletonsConsidered, maxSkeletonsValidated, maxSkeletonsRetained,
                rootFamilyAuditMode, policy, stageBRecallAuditMode);
    }

    public JointTeamBeamR3Config withStageBRecallAuditMode(StageBRecallAuditMode mode) {
        return new JointTeamBeamR3Config(maxPlanningMillis, planningSafetyMarginMillis, terminalShortlistLimit,
                maxFullTerminalEvaluations, maxDepth1Candidates, maxPartialToursPerDepth,
                maxExtensionsPerPartial, maxPartialToursGenerated, maxSkeletonsConsidered,
                maxSkeletonsValidated, maxSkeletonsRetained, rootFamilyAuditMode,
                competitiveTargetPolicy, mode);
    }
}
