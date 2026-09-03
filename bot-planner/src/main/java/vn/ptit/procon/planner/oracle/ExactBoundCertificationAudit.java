package vn.ptit.procon.planner.oracle;

/** Explicit provenance for the own-collection upper bound used by the oracle. */
public record ExactBoundCertificationAudit(String stateId, int currentCollections, int remainingStock,
        int trivialBound, int candidateTightBound, Integer actualContinuationOptimum,
        boolean candidateCertified, String boundSourceUsedForProof) {
    public ExactBoundCertificationAudit {
        if (stateId == null || boundSourceUsedForProof == null || currentCollections < 0
                || remainingStock < 0 || trivialBound < 0 || candidateTightBound < 0
                || actualContinuationOptimum != null && actualContinuationOptimum < 0) {
            throw new IllegalArgumentException("Invalid bound certification audit");
        }
    }
}
