package vn.ptit.procon.planner.oracle;

import java.util.List;
import java.util.OptionalInt;
import vn.ptit.procon.engine.TeamPlan;

/** Exact own-collection result plus an explicitly separate hybrid status. */
public record ExactOracleResult(TeamPlan bestPlan, int bestFoundOwnSemi, int bestFoundCoupledOwn,
        int bestFoundHybrid4, int provenUpperBound, int optimalityGap, boolean optimalityProven,
        boolean ownCollectionOptimalityProven, boolean hybridOptimalityProven, String bestPhysicalSignature,
        OptimalCollectionSkeleton bestCollectionSkeleton, ExactOracleDiagnostics diagnostics,
        List<TeamPlan> validLeafPlans, TeamPlan bestHybridPlan, String bestHybridPhysicalSignature,
        int bestHybridOwnSemi, ExactOracleAudit audit) {
    public ExactOracleResult {
        if (bestPlan == null || bestPhysicalSignature == null || bestCollectionSkeleton == null
                || diagnostics == null) throw new IllegalArgumentException("Exact result fields must not be null");
        validLeafPlans = List.copyOf(validLeafPlans);
        if (bestHybridPlan == null || bestHybridPhysicalSignature == null || bestHybridOwnSemi < 0) {
            throw new IllegalArgumentException("Hybrid result fields must not be null");
        }
        if (audit == null) throw new IllegalArgumentException("Oracle audit must not be null");
        if (bestFoundOwnSemi < 0 || provenUpperBound < bestFoundOwnSemi || optimalityGap < 0) {
            throw new IllegalArgumentException("Invalid exact result bounds");
        }
    }

    /** Explicit Phase 0.7 name; retained separately from the heuristic estimate. */
    public int certifiedUpperBoundOwn() { return audit.certifiedUpperBoundOwn(); }

    public int trivialCertifiedUpperBoundOwn() { return audit.trivialUpperBound(); }

    public int heuristicUpperBoundOwn() { return audit.heuristicUpperBoundOwn(); }

    /** Empty means the route-capacity candidate has not passed the exhaustive safety audit. */
    public OptionalInt tightCertifiedUpperBoundOwn() {
        return audit.tightBoundCertified() ? OptionalInt.of(audit.tightUpperBound()) : OptionalInt.empty();
    }

    public String boundSourceUsedForProof() { return audit.boundSourceUsedForProof(); }

    public ExactBoundCertificationAudit boundCertificationAudit() {
        return new ExactBoundCertificationAudit("ROOT", 0, certifiedUpperBoundOwn(),
                certifiedUpperBoundOwn(), heuristicUpperBoundOwn(), null,
                audit.tightBoundCertified(), audit.boundSourceUsedForProof());
    }

    public ExactOracleResult(TeamPlan bestPlan, int bestFoundOwnSemi, int bestFoundCoupledOwn,
            int bestFoundHybrid4, int provenUpperBound, int optimalityGap, boolean optimalityProven,
            boolean ownCollectionOptimalityProven, boolean hybridOptimalityProven, String bestPhysicalSignature,
            OptimalCollectionSkeleton bestCollectionSkeleton, ExactOracleDiagnostics diagnostics,
            List<TeamPlan> validLeafPlans) {
        this(bestPlan, bestFoundOwnSemi, bestFoundCoupledOwn, bestFoundHybrid4, provenUpperBound,
                optimalityGap, optimalityProven, ownCollectionOptimalityProven, hybridOptimalityProven,
                bestPhysicalSignature, bestCollectionSkeleton, diagnostics, validLeafPlans, bestPlan,
                bestPhysicalSignature, bestFoundOwnSemi, new ExactOracleAudit(bestFoundOwnSemi, bestFoundHybrid4,
                        0, 0, bestFoundOwnSemi, bestFoundHybrid4, "UNKNOWN", true, 0, 0, 0, 0,
                        bestFoundOwnSemi, bestFoundHybrid4, 0, 0, 0, 0, 0, bestFoundOwnSemi,
                        provenUpperBound, diagnostics.uniqueExactStates(), diagnostics.uniqueExactStates(),
                        diagnostics.canonicalMergeCount(), 0, 0, diagnostics.transitionsGenerated(), false,
                        0, "UNKNOWN"));
    }
}
