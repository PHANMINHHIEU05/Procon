package vn.ptit.procon.planner;

import java.util.Objects;

/** M16.1 calibrated competitive objective: semi/baseline throughput with a bounded coupled signal. */
public record HybridCalibratedMarginEvaluation(
        SemiCommitmentAwarePlanEvaluation semiCommitment,
        CoupledCompetitiveRolloutResult coupled,
        TeamNextDayHarvestCapacity nextDayHarvestCapacity) {

    public static final int HYBRID_BASE_WEIGHT = Integer.parseInt(
            System.getProperty("procon.hybrid.base_weight",
                    System.getenv("PROCON_HYBRID_BASE_WEIGHT") != null
                            ? System.getenv("PROCON_HYBRID_BASE_WEIGHT")
                            : "3"));
    public static final int HYBRID_COUPLED_WEIGHT = Integer.parseInt(
            System.getProperty("procon.hybrid.coupled_weight",
                    System.getenv("PROCON_HYBRID_COUPLED_WEIGHT") != null
                            ? System.getenv("PROCON_HYBRID_COUPLED_WEIGHT")
                            : "1"));
    public static final int HYBRID_SCALE = HYBRID_BASE_WEIGHT + HYBRID_COUPLED_WEIGHT;

    public HybridCalibratedMarginEvaluation {
        Objects.requireNonNull(semiCommitment, "M12.1 evaluation must not be null");
        Objects.requireNonNull(coupled, "Coupled competitive rollout result must not be null");
        Objects.requireNonNull(nextDayHarvestCapacity, "M13.1 capacity must not be null");
    }

    public int ownSemiBrands() { return semiCommitment.semiCommitmentRealizableBrandCount(); }
    public int ownSemiCollections() { return semiCommitment.semiCommitmentRealizableCollections(); }
    public int coupledOwnBrands() { return coupled.coupledOwnBrands(); }
    public int coupledOwnCollections() { return coupled.coupledOwnCollections(); }
    public int opponentBaselineCollections() { return coupled.opponentBaselineCollections(); }
    public int coupledOpponentCollections() { return coupled.coupledOpponentCollections(); }

    public int hybridOwnScore4() { return hybridOwnScore(HybridScoringConfig.defaults()); }

    public int hybridOwnScore(HybridScoringConfig config) {
        return config.baseWeight() * ownSemiCollections()
                + config.coupledWeight() * coupledOwnCollections();
    }

    public int hybridOpponentScore4() { return hybridOpponentScore(HybridScoringConfig.defaults()); }

    public int hybridOpponentScore(HybridScoringConfig config) {
        return config.baseWeight() * opponentBaselineCollections()
                + config.coupledWeight() * coupledOpponentCollections();
    }

    public int hybridMarginScore4() { return hybridMarginScore(HybridScoringConfig.defaults()); }
    public int hybridMarginScore(HybridScoringConfig config) {
        return hybridOwnScore(config) - hybridOpponentScore(config);
    }
    public int plannedOwnOpportunityEvents() { return coupled.plannedOwnOpportunityEvents().size(); }
    public int opponentCollectionsRemovedVsBaseline() { return coupled.opponentCollectionsRemovedVsBaseline(); }
    public int ownPlannedEventsInvalidatedByOpponent() { return coupled.ownPlannedEventsInvalidatedByOpponent(); }
    public int ownPlannedEventsExhaustedByOwnTeam() { return coupled.ownPlannedEventsExhaustedByOwnTeam(); }
    public int opponentReplacementCollections() { return coupled.opponentReplacementCollections(); }
    public int equalStepContests() { return coupled.equalStepContests(); }
    public int semiScore() { return semiCommitment.adjustedCollectionScore().value(); }
    public int remainingFutureDays() { return nextDayHarvestCapacity.remainingFutureDays(); }
    public int minimumPatrolDistinctSpots() { return nextDayHarvestCapacity.minimumPatrolDistinctSpots(); }
    public int minimumPatrolDistinctBrands() { return nextDayHarvestCapacity.minimumPatrolDistinctBrands(); }
    public int totalPatrolDistinctSpotCapacity() { return nextDayHarvestCapacity.totalPatrolDistinctSpotCapacity(); }
    public int totalPatrolDistinctBrandCapacity() { return nextDayHarvestCapacity.totalPatrolDistinctBrandCapacity(); }

    public boolean betterThan(HybridCalibratedMarginEvaluation other) {
        return compare(this, Objects.requireNonNull(other, "Other evaluation must not be null")) < 0;
    }

    public String improvementCriterion(HybridCalibratedMarginEvaluation incumbent) {
        Objects.requireNonNull(incumbent, "Incumbent evaluation must not be null");
        if (ownSemiBrands() != incumbent.ownSemiBrands()) return "SEMI_BRANDS";
        if (hybridMarginScore4() != incumbent.hybridMarginScore4()) return "HYBRID_MARGIN";
        if (hybridOwnScore4() != incumbent.hybridOwnScore4()) return "HYBRID_OWN";
        if (coupledOwnBrands() != incumbent.coupledOwnBrands()) return "COUPLED_BRANDS";
        if (hasFutureHorizon(incumbent)) {
            if (nextDayHarvestCapacity.minimumPatrolDistinctSpots()
                    != incumbent.nextDayHarvestCapacity.minimumPatrolDistinctSpots()) return "HORIZON_MIN_SPOTS";
            if (nextDayHarvestCapacity.totalPatrolDistinctSpotCapacity()
                    != incumbent.nextDayHarvestCapacity.totalPatrolDistinctSpotCapacity()) return "HORIZON_TOTAL_SPOTS";
            if (nextDayHarvestCapacity.totalPatrolDistinctBrandCapacity()
                    != incumbent.nextDayHarvestCapacity.totalPatrolDistinctBrandCapacity()) return "HORIZON_TOTAL_BRANDS";
        }
        if (ownSemiCollections() != incumbent.ownSemiCollections()) return "SEMI_COLLECTIONS";
        if (coupledOwnCollections() != incumbent.coupledOwnCollections()) return "COUPLED_COLLECTIONS";
        if (semiCommitment.base().udonTotal() != incumbent.semiCommitment.base().udonTotal()) return "RAW_UDON";
        if (semiCommitment.base().remainingFuelTotal() != incumbent.semiCommitment.base().remainingFuelTotal()) return "FUEL";
        if (semiCommitment.base().movementSteps() != incumbent.semiCommitment.base().movementSteps()) return "MOVEMENT";
        return "SIGNATURE";
    }

    public static int compare(HybridCalibratedMarginEvaluation left,
            HybridCalibratedMarginEvaluation right) {
        return compare(left, right, HybridScoringConfig.defaults());
    }

    public static int compare(HybridCalibratedMarginEvaluation left,
            HybridCalibratedMarginEvaluation right, HybridScoringConfig config) {
        Objects.requireNonNull(config, "Hybrid scoring configuration must not be null");
        int compared = Integer.compare(right.ownSemiBrands(), left.ownSemiBrands());
        if (compared != 0) return compared;
        if (config.prioritizeOwn()) {
            compared = Integer.compare(right.ownSemiCollections(), left.ownSemiCollections());
            if (compared != 0) return compared;
            compared = Integer.compare(right.hybridOwnScore(config), left.hybridOwnScore(config));
            if (compared != 0) return compared;
            compared = Integer.compare(right.coupledOwnBrands(), left.coupledOwnBrands());
            if (compared != 0) return compared;
            if (hasFutureHorizon(left, right)) {
                compared = Integer.compare(right.nextDayHarvestCapacity.minimumPatrolDistinctSpots(), left.nextDayHarvestCapacity.minimumPatrolDistinctSpots());
                if (compared != 0) return compared;
                compared = Integer.compare(right.nextDayHarvestCapacity.totalPatrolDistinctSpotCapacity(), left.nextDayHarvestCapacity.totalPatrolDistinctSpotCapacity());
                if (compared != 0) return compared;
                compared = Integer.compare(right.nextDayHarvestCapacity.totalPatrolDistinctBrandCapacity(), left.nextDayHarvestCapacity.totalPatrolDistinctBrandCapacity());
                if (compared != 0) return compared;
            }
            compared = Integer.compare(right.hybridMarginScore(config), left.hybridMarginScore(config));
            if (compared != 0) return compared;
            compared = Integer.compare(right.coupledOwnCollections(), left.coupledOwnCollections());
            if (compared != 0) return compared;
            compared = Integer.compare(right.semiCommitment.base().udonTotal(), left.semiCommitment.base().udonTotal());
            if (compared != 0) return compared;
            compared = Integer.compare(right.semiCommitment.base().remainingFuelTotal(), left.semiCommitment.base().remainingFuelTotal());
            if (compared != 0) return compared;
            compared = Integer.compare(left.semiCommitment.base().movementSteps(), right.semiCommitment.base().movementSteps());
            return compared != 0 ? compared : left.semiCommitment.base().deterministicSignature().compareTo(right.semiCommitment.base().deterministicSignature());
        }
        compared = Integer.compare(right.hybridMarginScore(config), left.hybridMarginScore(config));
        if (compared != 0) return compared;
        compared = Integer.compare(right.hybridOwnScore(config), left.hybridOwnScore(config));
        if (compared != 0) return compared;
        compared = Integer.compare(right.coupledOwnBrands(), left.coupledOwnBrands());
        if (compared != 0) return compared;
        if (hasFutureHorizon(left, right)) {
            compared = Integer.compare(right.nextDayHarvestCapacity.minimumPatrolDistinctSpots(), left.nextDayHarvestCapacity.minimumPatrolDistinctSpots());
            if (compared != 0) return compared;
            compared = Integer.compare(right.nextDayHarvestCapacity.totalPatrolDistinctSpotCapacity(), left.nextDayHarvestCapacity.totalPatrolDistinctSpotCapacity());
            if (compared != 0) return compared;
            compared = Integer.compare(right.nextDayHarvestCapacity.totalPatrolDistinctBrandCapacity(), left.nextDayHarvestCapacity.totalPatrolDistinctBrandCapacity());
            if (compared != 0) return compared;
        }
        compared = Integer.compare(right.ownSemiCollections(), left.ownSemiCollections());
        if (compared != 0) return compared;
        compared = Integer.compare(right.coupledOwnCollections(), left.coupledOwnCollections());
        if (compared != 0) return compared;
        compared = Integer.compare(right.semiCommitment.base().udonTotal(), left.semiCommitment.base().udonTotal());
        if (compared != 0) return compared;
        compared = Integer.compare(right.semiCommitment.base().remainingFuelTotal(), left.semiCommitment.base().remainingFuelTotal());
        if (compared != 0) return compared;
        compared = Integer.compare(left.semiCommitment.base().movementSteps(), right.semiCommitment.base().movementSteps());
        return compared != 0 ? compared : left.semiCommitment.base().deterministicSignature().compareTo(right.semiCommitment.base().deterministicSignature());
    }

    public static java.util.Comparator<HybridCalibratedMarginEvaluation> preference() {
        return HybridCalibratedMarginEvaluation::compare;
    }

    private static boolean hasFutureHorizon(HybridCalibratedMarginEvaluation evaluation) {
        return evaluation.nextDayHarvestCapacity.remainingFutureDays() > 0;
    }

    private static boolean hasFutureHorizon(HybridCalibratedMarginEvaluation left,
            HybridCalibratedMarginEvaluation right) {
        return hasFutureHorizon(left) || hasFutureHorizon(right);
    }
}
