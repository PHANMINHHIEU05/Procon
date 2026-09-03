package vn.ptit.procon.planner.v2;

/** Explicit bounded work limits for the Planner V2 joint team beam search. */
public record JointTeamBeamConfig(
        int beamWidth,
        int maxExpandedStates,
        int maxChildrenPerState,
        int maxNextTargetsPerAgent,
        int fullTerminalEvaluationLimit,
        V2SearchPolicy searchPolicy,
        V2CollectionAuditMode collectionAuditMode,
        CompetitiveTargetPolicy competitiveTargetPolicy,
        StageBRecallAuditMode stageBRecallAuditMode) {

    public static final int DEFAULT_BEAM_WIDTH = 48;
    public static final int DEFAULT_MAX_EXPANDED_STATES = 64;
    public static final int DEFAULT_MAX_CHILDREN_PER_STATE = 24;
    public static final int DEFAULT_MAX_NEXT_TARGETS_PER_AGENT = 4;
    /** Zero means evaluate every Stage-A terminal with the coupled rollout. */
    public static final int DEFAULT_FULL_TERMINAL_EVALUATION_LIMIT = 0;

    public JointTeamBeamConfig(
            int beamWidth,
            int maxExpandedStates,
            int maxChildrenPerState,
            int maxNextTargetsPerAgent) {
        this(beamWidth, maxExpandedStates, maxChildrenPerState, maxNextTargetsPerAgent,
                DEFAULT_FULL_TERMINAL_EVALUATION_LIMIT, V2SearchPolicy.R1_CONTROL,
                V2CollectionAuditMode.OFF, CompetitiveTargetPolicy.CURRENT, StageBRecallAuditMode.OFF);
    }

    public JointTeamBeamConfig(
            int beamWidth,
            int maxExpandedStates,
            int maxChildrenPerState,
            int maxNextTargetsPerAgent,
            int fullTerminalEvaluationLimit) {
        this(beamWidth, maxExpandedStates, maxChildrenPerState, maxNextTargetsPerAgent,
                fullTerminalEvaluationLimit, V2SearchPolicy.R1_CONTROL,
                V2CollectionAuditMode.OFF, CompetitiveTargetPolicy.CURRENT, StageBRecallAuditMode.OFF);
    }

    public JointTeamBeamConfig(
            int beamWidth,
            int maxExpandedStates,
            int maxChildrenPerState,
            int maxNextTargetsPerAgent,
            int fullTerminalEvaluationLimit,
            V2SearchPolicy searchPolicy) {
        this(beamWidth, maxExpandedStates, maxChildrenPerState, maxNextTargetsPerAgent,
                fullTerminalEvaluationLimit, searchPolicy, V2CollectionAuditMode.OFF);
    }

    public JointTeamBeamConfig(
            int beamWidth, int maxExpandedStates, int maxChildrenPerState, int maxNextTargetsPerAgent,
            int fullTerminalEvaluationLimit, V2SearchPolicy searchPolicy,
            V2CollectionAuditMode collectionAuditMode) {
        this(beamWidth, maxExpandedStates, maxChildrenPerState, maxNextTargetsPerAgent,
                fullTerminalEvaluationLimit, searchPolicy, collectionAuditMode,
                CompetitiveTargetPolicy.CURRENT, StageBRecallAuditMode.OFF);
    }

    public JointTeamBeamConfig(
            int beamWidth, int maxExpandedStates, int maxChildrenPerState, int maxNextTargetsPerAgent,
            int fullTerminalEvaluationLimit, V2SearchPolicy searchPolicy,
            V2CollectionAuditMode collectionAuditMode, CompetitiveTargetPolicy competitiveTargetPolicy) {
        this(beamWidth, maxExpandedStates, maxChildrenPerState, maxNextTargetsPerAgent,
                fullTerminalEvaluationLimit, searchPolicy, collectionAuditMode, competitiveTargetPolicy,
                StageBRecallAuditMode.OFF);
    }

    public JointTeamBeamConfig {
        if (beamWidth <= 0 || maxChildrenPerState <= 0 || maxNextTargetsPerAgent <= 0) {
            throw new IllegalArgumentException("Beam and child limits must be positive");
        }
        if (maxExpandedStates < 0) {
            throw new IllegalArgumentException("Maximum expanded states must not be negative");
        }
        if (fullTerminalEvaluationLimit < 0) {
            throw new IllegalArgumentException("Full terminal evaluation limit must not be negative");
        }
        if (searchPolicy == null) {
            throw new IllegalArgumentException("Search policy must not be null");
        }
        if (collectionAuditMode == null) {
            throw new IllegalArgumentException("Collection audit mode must not be null");
        }
        if (competitiveTargetPolicy == null) {
            throw new IllegalArgumentException("Competitive target policy must not be null");
        }
        if (stageBRecallAuditMode == null) {
            throw new IllegalArgumentException("Stage-B recall audit mode must not be null");
        }
    }

    public static JointTeamBeamConfig defaults() {
        return new JointTeamBeamConfig(
                DEFAULT_BEAM_WIDTH,
                DEFAULT_MAX_EXPANDED_STATES,
                DEFAULT_MAX_CHILDREN_PER_STATE,
                DEFAULT_MAX_NEXT_TARGETS_PER_AGENT,
                DEFAULT_FULL_TERMINAL_EVALUATION_LIMIT,
                V2SearchPolicy.R1_CONTROL,
                V2CollectionAuditMode.OFF,
                CompetitiveTargetPolicy.CURRENT,
                StageBRecallAuditMode.OFF);
    }

    public JointTeamBeamConfig withCollectionAuditMode(V2CollectionAuditMode mode) {
        return new JointTeamBeamConfig(beamWidth, maxExpandedStates, maxChildrenPerState,
                maxNextTargetsPerAgent, fullTerminalEvaluationLimit, searchPolicy, mode,
                competitiveTargetPolicy, stageBRecallAuditMode);
    }

    public JointTeamBeamConfig withCompetitiveTargetPolicy(CompetitiveTargetPolicy policy) {
        return new JointTeamBeamConfig(beamWidth, maxExpandedStates, maxChildrenPerState,
                maxNextTargetsPerAgent, fullTerminalEvaluationLimit, searchPolicy,
                collectionAuditMode, policy, stageBRecallAuditMode);
    }

    public JointTeamBeamConfig withStageBRecallAuditMode(StageBRecallAuditMode mode) {
        return new JointTeamBeamConfig(beamWidth, maxExpandedStates, maxChildrenPerState,
                maxNextTargetsPerAgent, fullTerminalEvaluationLimit, searchPolicy,
                collectionAuditMode, competitiveTargetPolicy, mode);
    }
}
