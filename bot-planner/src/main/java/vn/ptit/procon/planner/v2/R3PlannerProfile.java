package vn.ptit.procon.planner.v2;

import java.util.Objects;
import vn.ptit.procon.planner.HybridScoringConfig;

/** Complete immutable R3 configuration selected for one map tier. */
public record R3PlannerProfile(
        String name,
        R3MapTier tier,
        JointTeamBeamR3Config support,
        int beamWidth,
        int maxExpandedStates,
        int maxChildren,
        int maxTargets,
        JointBeamTuning tuning) {

    public R3PlannerProfile {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Profile name must not be blank");
        Objects.requireNonNull(tier, "Map tier must not be null");
        Objects.requireNonNull(support, "Support configuration must not be null");
        Objects.requireNonNull(tuning, "Beam tuning must not be null");
        if (beamWidth <= 0 || maxExpandedStates < 0 || maxChildren <= 0 || maxTargets <= 0) {
            throw new IllegalArgumentException("Invalid profile search limits");
        }
    }

    public static R3PlannerProfile create(String name, R3MapTier tier, R3SearchPreset preset) {
        JointTeamBeamR3Config base = JointTeamBeamR3Config.defaults();
        JointTeamBeamR3Config support = new JointTeamBeamR3Config(
                base.maxPlanningMillis(), base.planningSafetyMarginMillis(), base.terminalShortlistLimit(),
                preset.maxStageB(), base.maxDepth1Candidates(), base.maxPartialToursPerDepth(),
                base.maxExtensionsPerPartial(), base.maxPartialToursGenerated(), base.maxSkeletonsConsidered(),
                base.maxSkeletonsValidated(), base.maxSkeletonsRetained(), base.rootFamilyAuditMode(),
                base.competitiveTargetPolicy(), base.stageBRecallAuditMode());
        return new R3PlannerProfile(name, tier, support, preset.beamWidth(), preset.maxExpandedStates(),
                preset.maxChildren(), preset.maxTargets(), JointBeamTuning.productionDefaults());
    }

    public R3PlannerProfile withRootFamilyAuditMode(R3RootFamilyAuditMode mode) {
        return new R3PlannerProfile(name, tier, support.withRootFamilyAuditMode(mode), beamWidth,
                maxExpandedStates, maxChildren, maxTargets, tuning);
    }

    /** Explicit environment values are experiment-wide overrides; absent values preserve the tier. */
    public R3PlannerProfile withEnvironmentOverrides() {
        int beam = intOverride("procon.v2.beam_width", "PROCON_V2_BEAM_WIDTH", beamWidth);
        int expanded = intOverride("procon.v2.max_expanded_states", "PROCON_V2_MAX_EXPANDED_STATES",
                maxExpandedStates);
        int children = intOverride("procon.v2.max_children", "PROCON_V2_MAX_CHILDREN", maxChildren);
        int targets = intOverride("procon.v2.max_targets", "PROCON_V2_MAX_TARGETS", maxTargets);
        int stageB = intOverride("procon.v2.max_stage_b", "PROCON_V2_MAX_STAGE_B",
                support.maxFullTerminalEvaluations());
        int stock = intOverride("procon.v2.stock_weight", "PROCON_V2_STOCK_WEIGHT",
                tuning.stockWeight());
        int baseWeight = intOverride("procon.hybrid.base_weight", "PROCON_HYBRID_BASE_WEIGHT",
                tuning.scoring().baseWeight());
        int coupledWeight = intOverride("procon.hybrid.coupled_weight", "PROCON_HYBRID_COUPLED_WEIGHT",
                tuning.scoring().coupledWeight());
        JointBeamTuning effectiveTuning = new JointBeamTuning(stock,
                booleanOverride("procon.v2.use_expected_stock", "PROCON_V2_USE_EXPECTED_STOCK",
                        tuning.useExpectedStock()),
                booleanOverride("procon.v2.fair_family_expansion", "PROCON_V2_FAIR_FAMILY_EXPANSION",
                        tuning.fairFamilyExpansion()),
                booleanOverride("procon.v2.stage_b_fair_allocation", "PROCON_V2_STAGE_B_FAIR_ALLOCATION",
                        tuning.stageBFairAllocation()),
                booleanOverride("procon.v2.gate_refuel_by_gain", "PROCON_V2_GATE_REFUEL_BY_GAIN",
                        tuning.gateRefuelByGain()),
                new HybridScoringConfig(baseWeight, coupledWeight,
                        booleanOverride("procon.prioritize_own", "PROCON_PRIORITIZE_OWN",
                                tuning.scoring().prioritizeOwn())));
        JointTeamBeamR3Config effectiveSupport = new JointTeamBeamR3Config(
                support.maxPlanningMillis(), support.planningSafetyMarginMillis(),
                support.terminalShortlistLimit(), stageB, support.maxDepth1Candidates(),
                support.maxPartialToursPerDepth(), support.maxExtensionsPerPartial(),
                support.maxPartialToursGenerated(), support.maxSkeletonsConsidered(),
                support.maxSkeletonsValidated(), support.maxSkeletonsRetained(),
                support.rootFamilyAuditMode(), support.competitiveTargetPolicy(), support.stageBRecallAuditMode());
        return new R3PlannerProfile(name, tier, effectiveSupport, beam, expanded, children, targets,
                effectiveTuning);
    }

    public String fingerprint() {
        return Integer.toHexString(Objects.hash(tier, support, beamWidth, maxExpandedStates,
                maxChildren, maxTargets, tuning));
    }

    private static int intOverride(String property, String environment, int fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static boolean booleanOverride(String property, String environment, boolean fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }
}
